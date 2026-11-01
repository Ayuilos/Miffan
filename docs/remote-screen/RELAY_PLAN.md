# 中转连接方案（Miffan Relay）

状态：草案，2026-11-01。

## 1. 背景与目标

手机与电脑不在同一局域网时，目前只能通过 Tailscale 连接。对普通用户来说：

- 需要安装和配置 Tailscale，门槛高；
- 运营商 NAT 难以打洞时，Tailscale 会走 DERP 中转，但官方 DERP 不在中国大陆，延迟和稳定性都差；
- 出了问题，用户得自己排查网络。

目标：**电脑主动连出，手机经中转连入，用户不用配置网络。**

原则（2026-11-01 与用户确认）：

1. **官方中转与自部署中转是同一套开源代码。** 官方实例没有任何专属功能，区别只在配置取值。
2. **中转本身负责按带宽区分流量**：区分控制与画面、限速、配额。自部署用户同样需要控制画面流量的成本。
3. **体验一致**：App 连官方中转和连自建中转，流程、界面、降级行为完全相同，差异全部来自中转返回的策略。
4. **中转只转发密文**：中转运营方（包括我们自己）看不到内容，也无法冒充电脑。
5. **官方实例跑在 Cloudflare 免费方案上**（2026-11-01 用户决定），不部署香港或其他 VPS。它是免费服务，用户对它有相应的心理预期；不满意可以自己部署（Cloudflare 或 VPS）。因此"自己部署"必须是一条顺畅的一等路径（§7.2、§9.2）。

不在本方案内：账号体系、跨用户共享电脑、在中转上运行任何 AI 或业务逻辑。

## 2. 现状（与本方案相关的部分）

- App 与电脑之间的全部管理、AI 控制、VNC、Sunshine TCP 都复用**同一条 SSH 连接**（JSch，`workspace/.../NativeSshWorkspaceTransport.kt`）：
  - helper 命令、cua-driver MCP：SSH exec 通道；
  - VNC、Sunshine TCP（47984/47989/48010）：`openLoopbackStream`（direct-tcpip），不在手机上监听端口；
  - Cua Space：经主机 SSH 的 `ChannelSocketFactory`。
- 主机身份靠 `trustedHostKeySha256` 固定（首次连接 TOFU）。
- 多地址回退：`WorkspaceRepository` 用 `tryRemoteAddresses(host.connectionAddresses)` 依次尝试。
- **唯一的例外是 Sunshine 的视频、音频和输入**：UDP 47998–48000 直连，要求 UDP 可达（局域网、Tailscale 或公网 IP）；加密模式为强制。
- Windows 只有 Sunshine 一种画面方式（没有 VNC/RDP 回退）。Mac/Linux 有 VNC（Linux 还有 RDP）作为回退。

因此，**中转只要能转发 SSH 字节流，管理和 AI 控制就能全平台可用**；画面在 Mac/Linux 可以走 VNC，Windows 画面依赖 UDP 中转（R2）。

## 3. 总体架构

```
          ┌──────────── 电脑 ────────────┐
          │ sshd (127.0.0.1:22)          │
          │ Sunshine (UDP 47998-48000)   │
          │        ▲                     │
          │ miffan-connector（常驻）     │──── 主动外连 wss ────┐
          └──────────────────────────────┘                     │
                                                               ▼
                                              ┌───────── 中转 relay ─────────┐
                                              │ 按 hostId 配对两端             │
                                              │ 分通道限速、计量、配额        │
                                              │ 只转发密文                     │
                                              └───────────────────────────────┘
                                                               ▲
          ┌──────────── 手机 Miffan ─────────┐                  │
          │ 直连地址（局域网 / Tailscale）优先 │── 都失败时 ──────┘
          │ JSch over relay（控制通道）       │
          │ 第二条 SSH over relay（画面通道） │
          └──────────────────────────────────┘
```

组成：

| 组件 | 位置 | 语言 | 说明 |
|---|---|---|---|
| relay server | `relay/server/` | TypeScript | 一份核心，两个适配层：Cloudflare Worker + Durable Object；Node/Bun（VPS、Docker、单文件） |
| connector | `relay/connector/` | Go | 电脑端常驻，单个静态二进制，Mac/Linux/Windows |
| 协议文档 | `relay/PROTOCOL.md` | — | 版本化，三端共同遵守 |
| App 客户端 | `workspace` 模块新增 `relay` 包 | Kotlin | 基于 OkHttp WebSocket，实现 JSch `SocketFactory` |
| helper 命令 | `remote-helper/src` | sh / ps1 | `relay install / pair / unpair / status / uninstall` |

`relay/` 不是 Gradle 模块，与 `remote-helper/` 一样随仓库维护，但 server 和 connector 独立发版（见 §10）。

## 4. 通道与流量分级

中转看不懂 SSH 密文，只能靠**连接时由 App 声明的通道类型**来区分流量。每条中转连接都有一个类型：

| 通道 | 承载 | 中转对它的处理 |
|---|---|---|
| `control` | SSH：helper、cua-driver MCP（含截图）、文件、终端 | 低速率上限 + 允许短时突发；单独的小配额 |
| `media` | 第二条 SSH：VNC、Sunshine TCP | 按画面策略：可禁止、限码率、计入画面配额 |
| `media-dgram`（R2） | Sunshine UDP 数据报 | 同 `media`；承载方式见 §11 R2（`udp` 或 `ws`） |

**防伪装**：App 可能把画面流量标成 `control`（无论有意还是出 bug），但 `control` 有速率上限，伪装后的画面卡到无法使用，策略自然生效，中转不需要识别内容。

`control` 的默认上限要在 R0 实测后确定：既要让 AI 的截图（单张约 100–300 KB）不明显变慢，又要让画面在这个速率下无法使用。初步设想为持续 1 Mbps、突发 2 MB 的令牌桶。

App 侧的拆分：

- 直连时保持现状（一条 SSH 承载所有流量）。
- 经中转时，画面相关代码（`RemoteScreenRepository`、`RemoteStreamEngine` 的 TCP 连接器）改为通过新的 `RemoteMediaSessionProvider` 获取会话：直连时返回同一条控制会话，经中转时另开一条 `media` 通道上的 SSH 会话（同一主机密钥固定、同一私钥）。不需要引入新的加密层。

## 5. 中转策略与能力公告

### 5.1 策略配置

部署者通过一份配置文件（Worker 用环境变量 / `wrangler.toml`，VPS 用 `relay.toml`）设置：

```toml
[access]
mode = "open"              # open：任何 connector 可注册 | token：注册时需提供部署者设的访问码
registrations_per_ip_per_day = 5

[control]
rate_kbps = 1000
burst_kb = 2048
monthly_quota_mb = 2048    # 每台电脑

[media]
allowed = true
max_kbps = 4000            # App 据此选画质
monthly_quota_mb = 10240   # 每台电脑；0 = 不限
dgram = "auto"             # auto：Node 用 udp、Worker 用 ws | ws | off

[limits]
sessions_per_host = 4
idle_timeout_s = 300
host_daily_mb = 500        # 每台电脑每天（控制 + 画面）；0 = 不限
global_daily_mb = 0        # 整个中转每天的总预算；0 = 不限
global_media_reserve = 0.3 # 总预算剩余不足此比例时先停画面，只保留控制
```

`global_daily_mb` 与 `host_daily_mb` 主要是给 Cloudflare 免费方案用的（§7.3）：免费额度是整个账号共用的，用超后 Cloudflare 会直接拒绝所有请求，直到第二天额度重置。中转必须在这之前主动收紧，先停画面、再拒绝新会话，而不是让所有人一起断线。自部署 VPS 可以把它们设为 0。

官方实例就是这份配置的一套取值，写在部署仓库里公开。

### 5.2 能力公告

App 和 connector 连上中转后，中转先发送 `hello`：

```json
{
  "v": 1,
  "relay": { "impl": "worker", "version": "1.0.0" },
  "capabilities": { "dgram": "none" },
  "policy": {
    "control": { "rateKbps": 1000, "burstKb": 2048 },
    "media": { "allowed": true, "maxKbps": 4000 }
  },
  "usage": {
    "period": "2026-11",
    "control": { "usedMb": 120, "quotaMb": 2048 },
    "media": { "usedMb": 3100, "quotaMb": 10240 }
  }
}
```

App 根据它决定：

| 条件 | App 行为 |
|---|---|
| `media.allowed = false` | 画面页提示"这个中转不提供画面，连回家里的网络就能看"；伙伴照常操作电脑 |
| `media.maxKbps` | 画质档位按上限封顶（4 Mbps 内只给"省流"档） |
| 画面配额用完 | 同"不提供画面"，文案改为"本月中转画面流量已用完，X 日恢复" |
| `capabilities.dgram = "none"`（R2 之前，或部署者关闭） | Sunshine 只在 UDP 直连可达时使用；Mac/Linux 回退 VNC；Windows 在外不能看画面 |
| 控制配额用完 | 连接失败，原因明确显示，不当作"电脑离线" |

| 中转今天的总预算快用完（`relay_busy`） | 画面暂停，控制照常；提示"免费中转今天比较忙，可以用自己的中转" |
| Cloudflare 自身拒绝（免费额度已用超，Worker 根本没运行） | WebSocket 握手收到 Cloudflare 的错误响应（HTTP 429 / 错误页），App 识别后显示"免费中转今天的额度用完了，北京时间早上 8 点恢复，或者用自己的中转"，不当作"电脑离线" |

拒绝时一律返回机器可读的原因码（`media_disabled`、`quota_exhausted`、`relay_busy`、`host_offline`、`unauthorized`、`too_many_sessions` …），App 不靠解析文字判断。Cloudflare 平台层面的拒绝不经过我们的代码，App 单独识别（R0 记录实际的状态码和响应内容）。

### 5.3 计量

- 按 `hostId`、通道、自然月统计双向字节数，持久化（Durable Object SQLite / VPS 上的 SQLite 文件）。
- 只记录：hostId、设备公钥指纹、字节数、时间戳。不记录内容，不记录 IP（限流用的 IP 计数只放内存，不落盘）。
- App 设置页显示"本月经中转：控制 X MB / 画面 Y MB（上限 Z）"，官方和自建是同一个界面。

## 6. 身份、配对与安全

### 6.1 身份

- **hostId**：connector 首次运行时生成 Ed25519 密钥对，hostId = 公钥哈希（base32，不可猜）。私钥只存在电脑上，权限仅当前用户可读。
- **设备密钥**：手机在 Android Keystore 中生成不可导出的 P-256 密钥，每台手机一把，与 SSH 私钥分开。

### 6.2 配对（在局域网完成首次设置时）

1. 现有设置流程走到"准备就绪"后，App 通过 SSH 调用 `helper relay install`，安装并启动 connector，然后 `helper relay pair <设备公钥> <中转地址>`。
2. connector 把设备公钥写入自己的允许列表，并返回 hostId 和中转地址；App 将它们作为该电脑的一个"中转地址"保存。
3. 之后在外面时，`connectionAddresses` 的最后一项就是中转：直连地址都失败后才走中转。

不在局域网时（比如电脑在公司），仍然可以通过已有的 SSH 地址（Tailscale 等）完成同样的步骤。没有任何可用地址时，不支持首次配对（R1 不做扫码配对，见 §12）。

### 6.3 连接时的验证

```
App ──hello──▶ relay ──▶ connector：有设备 <fp> 要连 control 通道
connector ──challenge(nonce)──▶ App
App ──sign(nonce, hostId, channel)──▶ connector
connector：设备在允许列表且签名正确 → 打开 127.0.0.1:<sshd 端口> 并开始转发
          否则 → 关闭，计入失败次数（连续失败后对该设备指纹退避）
```

- 不在允许列表里的人即使知道 hostId，**也接触不到 sshd 的登录入口**。
- 之后的 SSH 仍然校验固定的主机密钥，中转无法中间人攻击。
- connector **只会**连接本机 sshd 端口和 Sunshine 的 UDP 端口，不接受其他目标，避免成为开放代理。
- 经中转登录时，App 只使用私钥登录（不发送密码）。

### 6.4 威胁模型

| 对手 | 能做什么 | 不能做什么 |
|---|---|---|
| 中转运营方（含我们） | 看到 hostId、设备指纹、流量大小和时间；拒绝服务 | 看内容；冒充电脑；登录电脑 |
| 知道 hostId 的陌生人 | 让 connector 收到连接请求（会被退避限流） | 接触到 sshd |
| 手机丢失 | 用设备密钥 + SSH 私钥连回电脑 | — 处置：在另一台设备上或回到局域网时 `relay unpair`，并在 App 中提供"移除这台手机" |
| 偷到 connector 私钥 | 冒充 hostId 注册，抢占中转上的该电脑 | 通过 SSH 主机密钥校验（App 会报主机密钥不匹配） |

## 7. relay server

### 7.1 一份核心，两个适配层

```
relay/server/
  core/         协议帧、hello、策略、令牌桶、计量、原因码（无平台依赖，单元测试覆盖）
  worker/       Cloudflare：Worker 路由 + 每个 hostId 一个 Durable Object
  node/         Node/Bun：HTTP/WS 服务 + SQLite +（R2）UDP 数据报转发
  test/         两个适配层共用的一致性测试（同一套用例分别跑）
```

- **Worker 适配**：每个 hostId 对应一个 Durable Object，connector 与 App 的 WebSocket 都落到同一个对象里，转发天然在一处完成。免费方案只能使用 SQLite 存储的 Durable Object，计量也存在里面。不支持 UDP，数据报只能装进 WebSocket 帧里传（§11 R2）。
- **Node 适配**：给自部署 VPS 用，支持真正的 UDP 数据报转发（R2）。发布形式：Docker 镜像，以及 `bun build --compile` 生成的单文件。
- **省消息数**：Cloudflare 按 WebSocket 消息数计入请求额度，所以两端都把小块数据攒成较大的帧再发（例如最多 64 KB 或 10 ms，以先到者为准），控制通道的交互延迟在 R0 里确认不受影响。这一规则写进协议，Node 适配层同样遵守。
- **一致性测试**是硬性要求：策略、原因码、计量在两个适配层上必须得到相同结果。

### 7.2 部署方式

官方实例不满意时，用户的出路就是自己部署，所以这一节按"普通用户照着做也能成功"的标准来写：

- Cloudflare：仓库内 `wrangler.toml` + "Deploy to Cloudflare" 按钮；中文图文教程（注册账号 → 一键部署 → 绑定域名 → 在 App 里填地址）。教程明确说明 `workers.dev` 在国内不可用，需要绑定自己的域名。个人部署只服务自己的几台电脑，免费额度远比官方实例宽裕。
- VPS：`docker run` 一行命令，或下载单文件 + systemd unit；自动 TLS 的说明（Caddy 反代，或证书配置项）。
- 部署完成后，中转首页显示一个"添加到 Miffan"的二维码（中转地址 + 访问码），App 扫码即可添加，不需要手抄。

### 7.3 Cloudflare 免费方案的限制（R0 核实）

官方实例就跑在这里，以下几点直接决定官方实例的策略取值：

- **每日免费额度由整个账号共用**：Workers 请求数、Durable Object 的请求数和运行时长都有每日上限。持续转发时 Durable Object 无法休眠，运行时长会一直累计。R0 要实测"控制通道 1 小时"和"画面 1 小时"各消耗多少额度，据此算出官方实例大约能服务多少使用时长，再设定 `host_daily_mb`、`global_daily_mb` 和画面是否开放。
- 额度用超后 Cloudflare 直接拒绝，额度在 UTC 0 点（北京时间 8 点）重置（§5.2 的 App 处理）。
- 从国内访问的延迟和晚高峰稳定性：免费方案通常不走国内节点。
- 长时间转发视频流量是否符合服务条款。
- 官方实例需要一个绑定在 Cloudflare 上的自有域名（`workers.dev` 在国内不可用）：使用 `relay.miffan.ayuilos.me`。

如果实测下来免费额度撑不起画面，官方实例就设 `media.allowed = false`，只做"伙伴在外操作电脑"；想在外看画面的用户自己部署。这同样只是一项策略，不需要特殊代码。

## 8. connector

- Go 单个静态二进制：darwin-arm64/amd64、linux-amd64/arm64、windows-amd64。
- 职责：注册 hostId → 维持到中转的长连接（心跳、断线指数退避重连）→ 验证设备 → 转发到本机 sshd /（R2）Sunshine UDP。
- 配置文件：`relay url`、hostId 私钥路径、设备允许列表、访问码（可选）。
- 常驻方式（由 helper 安装）：

| 平台 | 方式 | 说明 |
|---|---|---|
| macOS | 用户级 LaunchAgent | 登录后运行；电脑睡眠时连不上（与现状相同） |
| Linux | `systemd --user` 服务 | 尝试 `loginctl enable-linger` 以便开机后未登录时也能运行，失败则在 App 中提示"登录后才能连接" |
| Windows | 当前用户的登录计划任务（与 cua bridge 同一模式） | sshd 是系统服务，loopback 不受 Miffan-SSH 防火墙规则来源网段限制 |

- 分发与校验：二进制放在 GitHub Release + 现有 R2 镜像；helper 下载后按 `remote-helper/versions.lock` 同样的方式校验 SHA-256，校验失败即中止。通过 SSH 安装不会带 macOS 隔离属性，不需要公证；Windows Defender/SmartScreen 的误报在 R0 里验证。
- 升级：App 带一个期望的 connector 版本，`relay status` 发现版本过旧就提示更新（与 cua-driver 更新的交互一致）。

## 9. App 改动

### 9.1 数据与连接

- 主机新增"中转地址"类型（中转 URL、hostId、访问码），参与 `connectionAddresses`，始终排在直连地址之后。
- `workspace` 新增 `RelaySocketFactory`：OkHttp WebSocket → JSch `SocketFactory`（管道化的输入输出流），完成 hello / challenge / 签名。
- `RemoteAddressFallback` 识别中转的原因码：`host_offline` 等同"连不上"，`unauthorized` 是"这台手机未授权"，`quota_exhausted` 单独展示，都不按网络故障重试。
- `RemoteMediaSessionProvider`（§4）。
- 画质档位读取中转的 `media.maxKbps`，在现有"按网络类型记忆"之上再取上限。

### 9.2 界面（简易模式优先，UI 由我做）

- **设置流程**：在"准备就绪"之后增加一屏"出门在外也能用"，默认开启、默认使用官方中转；"其他中转"放在详情里（扫码添加 / 手填地址 + 访问码，或关闭）。用平实的文案，不出现 relay、connector、hostId 等术语。
- **官方中转的说明**：明确写"免费的公共中转，高峰时可能变慢或暂停看画面"，并附"自己搭一个"的教程入口。官方中转收紧（`relay_busy`、额度用完、画面未开放）时，提示里都带这个入口。
- **电脑详情**：中转开关、当前使用的中转、本月用量、"移除这台手机"。
- **画面页状态行**：`已连接 · 经中转 · 省流`；画面不可用时显示 §5.2 的文案；Windows 在 R2 之前显示"在外暂时只能让伙伴操作，看画面要回到家里的网络"。
- **流量提醒**：沿用现有"按流量计费网络默认省流 + 一次性 GB/小时提示"，中转下同样适用。

## 10. 仓库与发版

- `relay/server`、`relay/connector`、`relay/PROTOCOL.md` 放在本仓库，许可证与 Miffan 相同（AGPLv3，待确认）。
- 发版：connector 和 server 有各自的版本号与 tag（`relay-v1.0.0`）。App 版本里记录兼容的协议版本号和 connector 期望版本。协议向后兼容：中转和 connector 必须接受比自己旧的 App。
- CI：server 核心单元测试 + 两个适配层的一致性测试；connector 交叉编译和单元测试；App 侧 `RelaySocketFactory` 用本地 Node 中转跑集成测试。

## 11. 分期

### R0 验证（约 1 周）

- 最小 Worker+DO 和 Node 中转（只转发字节）、最小 connector、JSch over WebSocket。
- 测量：国内手机（Wi-Fi 和移动数据，白天和晚高峰）→ Cloudflare 免费方案的延迟和吞吐；AI 一次操作（截图 + 点击）的耗时；VNC 在 1/2/4 Mbps 下的体验；控制 1 小时、画面 1 小时各消耗多少免费额度（请求数、Durable Object 时长）；攒帧对额度和交互延迟的影响。
- 记录 Cloudflare 免费额度用超后 WebSocket 握手的实际响应，供 App 识别。
- 核实：Cloudflare 服务条款；Windows Defender 对 connector 的反应；`loginctl enable-linger` 在常见发行版上能否不用 root。
- Sunshine UDP 小实验：connector 从 127.0.0.1 发 UDP 时，Sunshine 能否正常建立会话（lan/wan 判定、加密模式）；把 UDP 装进 WebSocket 帧经 Worker 转发时，画面在轻度丢包网络下的延迟表现。
- 产出：`docs/remote-screen/research/RELAY_R0.md`，定下 `control` 默认上限，以及官方实例的每日预算和画面是否开放。

### R1 控制 + Mac/Linux 画面（主体）

- server 核心 + 两个适配层 + 策略/计量/原因码 + 一致性测试。
- connector + helper `relay` 命令（sh + ps1）。
- App：中转地址、`RelaySocketFactory`、设备密钥与配对、`RemoteMediaSessionProvider`、设置流程和用量界面。
- 结果：所有平台在外都能让伙伴操作电脑；Mac/Linux 在外可以看 VNC 画面（受策略约束）。

### R2 Sunshine 走中转

- 协议增加数据报帧，有两种承载方式，`hello` 中的 `capabilities.dgram` 公告当前中转用的是哪一种：
  - `udp`（Node 适配）：真正的 UDP 转发（类似 TURN），丢包时不阻塞后面的包。
  - `ws`（Worker 适配）：数据报装进 WebSocket 帧。丢包会被 TCP 重传成延迟抖动，属于降级模式；是否对官方实例开放，由 R0 的实测决定。
- `stream` 模块给 common-c 增加 UDP 套接字钩子（与现有 TCP 的 connected-fd 钩子同一思路，用 `socketpair(SOCK_DGRAM)`），由 Kotlin 层泵送到中转。
- connector 转发到本机 Sunshine UDP 端口。
- 结果：Windows 在外可以看画面；Mac/Linux 在外可以用高性能模式（官方实例上受画面策略和降级模式限制）。

### R3 直连优先（打洞）

- 借中转交换地址候选，尝试 UDP 打洞；成功后画面直连，不消耗中转流量。
- 对官方实例来说这一期价值最大：打洞成功的会话不消耗 Cloudflare 免费额度，中转只承担信令和打不通的那部分连接。
- 届时评估自研和 iroh 一类现成方案的取舍。

### 官方实例

- 与 R1 一起上线，使用同一份代码，部署在 Cloudflare 免费方案（Worker 适配），配置公开。
- 地址：`relay.miffan.ayuilos.me`，放在 Miffan 项目站 `miffan.ayuilos.me`（使用指南、帮助文档）之下，因为中转协议是 Miffan 专用的。用 Worker 自定义域名绑定：Cloudflare 会为这个多级子域名单独签发证书，免费方案即可，不需要 Advanced Certificate Manager（2026-11-01 查 Cloudflare 文档确认；该子域名当时尚未使用）。R0 用 `relay-dev.miffan.ayuilos.me`，不占用正式地址。
- 不部署 VPS；需要真正的 UDP 转发、更多额度或更低延迟的用户自己部署。
- 防滥用全部依靠通用策略：按 IP 的注册频率、每台电脑每日/每月配额、全局每日预算、并发会话上限。另加一个运营用的全局开关（关闭新注册）。

## 12. 待决定

1. 官方实例的默认策略取值：是否开放画面、码率上限、每台电脑每日/每月配额、全局每日预算（R0 实测后定）。
2. 官方实例是否完全不需要账号（本方案的前提：无账号，按电脑计配额）。
3. connector 用 Go 是否可以。
4. `relay/` 的许可证（AGPLv3 与主仓库一致？）。
5. 是否需要在 R1 之后补充"不在同一网络时扫码完成首次配对"（需要临时配对码 + 电脑端运行安装脚本，类似现有 Windows 设置脚本）。

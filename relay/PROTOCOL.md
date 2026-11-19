# Miffan Relay 协议 v1

本文是中转服务器（`relay/server`）、电脑端 connector（`relay/connector`）与 App（`workspace` 模块）三方的契约。设计背景见 [RELAY_PLAN.md](../docs/remote-screen/RELAY_PLAN.md)，测量依据见 [RELAY_R0.md](../docs/remote-screen/research/RELAY_R0.md)。

关键词"必须 / 不得 / 应当"按 RFC 2119 理解。

## 1. 角色与信任

| 角色 | 说明 | 信任 |
|---|---|---|
| relay | 配对 connector 与 App 的 WebSocket，转发帧，执行策略与计量 | **不可信**：看不到明文，不能冒充电脑或手机 |
| connector | 电脑上常驻，主动连 relay；把通过验证的会话接到本机 sshd | 持有电脑身份私钥 |
| App | 手机；经 relay 建 SSH | 持有设备私钥（Android Keystore，不可导出） |

端到端安全由两层保证，都不依赖 relay：

1. **会话层**：connector 在把字节交给 sshd 之前，用设备公钥验证 App 的签名（§5）。
2. **SSH 层**：App 校验固定的 SSH 主机密钥，SSH 本身加密。

relay 只做两类判断：访问码（部署者是否允许这个 connector/App 使用本中转）和策略（限速、配额）。

## 2. 标识

| 名称 | 定义 |
|---|---|
| host key | Ed25519 密钥对，connector 首次 `init` 时生成，私钥只存在电脑上 |
| `hostPub` | host key 公钥的 32 字节原始值 |
| `hostId` | `base32(SHA-256(hostPub))` 的前 26 个字符，小写、无填充（RFC 4648 字母表）。正则 `^[a-z2-7]{26}$` |
| device key | ECDSA P-256 密钥对，App 在 Android Keystore 中生成 |
| `devicePub` | device key 公钥的 SubjectPublicKeyInfo DER |
| `deviceId` | `base32(SHA-256(devicePub))` 的前 26 个字符，小写、无填充 |
| `sid` | relay 生成的会话 ID，16 个随机字节的小写十六进制（32 字符） |
| `nonce` | 32 个随机字节 |

二进制值在 JSON 和 URL 中一律用 **base64url 无填充** 编码（下文记作 `b64u`）。

## 3. HTTP 端点

relay 的基础地址记作 `BASE`（例如 `https://relay.miffan.ayuilos.me`），WebSocket 用对应的 `wss://`。

| 方法与路径 | 鉴权 | 用途 |
|---|---|---|
| `GET /v1/info` | 无 | 中转的能力、公开策略、入口候选 |
| `GET` (WS) 或 `POST` (Stream) `/v1/host` | 访问码（若需要）+ 主机签名 | connector 控制连接 |
| `GET` (WS) 或 `POST` (Stream) `/v1/host/{hostId}/session/{sid}` | `sid`（只有 connector 通过控制连接知道） | connector 的会话数据连接 |
| `GET` (WS) 或 `POST` (Stream) `/v1/connect/{hostId}?ch={control\|media\|media-dgram}` | 访问码（若需要） | App 的会话连接 |
| `GET /v1/admin/stats` | `Authorization: Bearer <ADMIN_TOKEN>` | 部署者查看全局用量 |

访问码：`/v1/info` 的 `access` 为 `"token"` 时，connector 和 App 的 WebSocket 请求都必须带 `Authorization: Bearer <访问码>`，否则返回 401 `unauthorized`。`access` 为 `"open"` 时忽略该头。

### 3.1 `GET /v1/info`

```json
{
  "v": 1,
  "impl": "worker",
  "version": "1.0.0",
  "access": "open",
  "capabilities": { "dgram": "none", "stream": true },
  "policy": { …§6.1 的策略对象… },
  "edges": ["104.17.5.5", "104.19.8.8"]
}
```

- `impl`：`"worker"`（Cloudflare）或 `"bun"`（VPS）。
- `capabilities.dgram`：`"none"`（未开启数据报转发）或 `"udp"`（提供 §5.5 原生 UDP 数据报转发）。当为 `"udp"` 时，还包含 `"udpPort"`（`uint16`，服务端监听的 UDP 端口）以及可选 `"udpHost"`（字符串，未提供时取中转 URL 主机名）。
- `capabilities.stream`：可选布尔值；为 `true` 时表示服务端支持 §3.3 的 HTTP 双向流传输（主要用于 HTTP/3 over QUIC / UDP，无需 WebSocket `Upgrade`）。
- `edges`：入口 IP 候选（IPv4 或 IPv6 文本），可为空。客户端按 §8 使用。

### 3.2 错误响应

WebSocket 升级或 HTTP 流建立前的拒绝一律是 HTTP 错误，正文为 JSON：

```json
{ "reason": "host_offline", "retryAfter": 30 }
```

`retryAfter`（秒）可选。原因码见 §9。连接建立之后的拒绝用关闭码（§9）。

客户端必须把**不是 JSON 或没有 `reason` 的错误响应**视为平台层拒绝（例如 Cloudflare 免费额度用完时的 1027 错误页），不得当作"电脑离线"。

### 3.3 双向 HTTP 流传输（HTTP/3 / QUIC 兼容）

为支持在 HTTP/3（QUIC over UDP）上直接建立全双工信道（无需 Extended CONNECT RFC 9220 或 WebSocket 升级头），当 `capabilities.stream` 为 `true` 时，三个连接端点同时支持 `POST` 双向字节流：

- **请求**：方法为 `POST`，请求头 `Content-Type: application/x-miffan-relay-v1`。
- **响应**：接受连接时返回 `200 OK`，`Content-Type: application/x-miffan-relay-v1`（及 `Cache-Control: no-store, no-transform`）；被拒绝时按 §3.2 返回非 200 HTTP 状态码与 JSON 错误体，不进入帧流。
- **连接前奏与反向代理冲洗**：客户端与服务端应当在建流后立即发送 5 字节前奏帧（type `0x00`，length `0`），使反向代理和 TLS/HTTP/3 栈立刻冲洗响应头，而不等待第一条应用帧。
- **帧格式**：请求体与响应体均由连续的帧组成，每帧为 5 字节头 + 载荷：
  - `byte 0`：帧类型
    - `0x00`：前奏 / 空操作帧（长度必须为 `0`；非 `0` 视为协议错误 `1008` 关闭）
    - `0x01`：UTF-8 文本帧（长度 `<= 4,096`；超长以 `4413` 关闭，非法 UTF-8 以 `1008` 关闭）
    - `0x02`：二进制帧（长度 `<= 65,536`；超长以 `4413` 关闭）
    - `0x08`：关闭帧（载荷为 `0` 字节，或 `2..125` 字节：`uint16 BE` 关闭码 + UTF-8 `reason`）
  - `bytes 1..4`：`uint32 BE` 载荷长度
- 同一会话的 connector 侧与 App 侧可以分别使用 WebSocket 或 HTTP 流（由 relay 透明桥接），上层握手、限速、保活与关闭码语义完全一致。

## 4. connector 控制连接

```
connector                          relay
   │ WS GET /v1/host?pub=<b64u hostPub>[&hint=<locationHint>]
   │ ───────────────────────────────▶
   │ ◀── {"t":"challenge","nonce":"<b64u>"}
   │ ── {"t":"auth","sig":"<b64u>"[,"dgram":true]} ──▶
   │ ◀── {"t":"hello", …}            （或关闭 4401）
   │ ◀── {"t":"open", …}             （每个新会话一条）
   │ ── "ping"（每 30 秒）──▶  ◀── "pong"
```

- `pub`：`b64u(hostPub)`。relay 由它计算 `hostId`。
- `hint`：可选，只在该 `hostId` 的状态第一次创建时有意义（Cloudflare Durable Object 的 `locationHint`；Bun 实现忽略）。
- `签名`：`sig = Ed25519(hostKey, UTF8("miffan-relay/v1 host\n" + host + "\n" + nonce))`，其中 `host` 是请求的 `Host` 头的值（小写），`nonce` 是 challenge 中的 b64u 文本原样。支持 `ch=media-dgram` 的 connector 在 `auth` 中附带 `"dgram": true`。
- relay 必须在 10 秒内收到正确签名，否则关闭 4401。
- 同一 `hostId` 只保留一条控制连接：新连接**验签通过后**，旧连接以 4000 `replaced` 关闭。
- 首次出现的 `hostId` 受按来源 IP 的注册频率限制（策略 `access.registrationsPerIpPerDay`）：升级前检查，超出返回 429 `too_many_registrations`；只有**验签通过**后才计数并为它建立状态，未通过验签的连接不留下任何记录。IPv6 地址按 /64 前缀计数，IPv4 映射地址按其 IPv4 地址计数。

`hello`：

```json
{
  "t": "hello",
  "hostId": "…",
  "policy": { …§6.1… },
  "usage": { …§6.3… },
  "capabilities": { "dgram": "none", "stream": true }
}
```

`open`：

```json
{
  "t": "open",
  "sid": "…",
  "ch": "control",
  "pace": { "kbps": 1000, "burstKb": 2048 },
  "udp": { "port": 8444, "host": "relay.example.com", "token": "<b64u 16B hostToken>" }
}
```

仅当 `ch` 为 `"media-dgram"` 时携带 `udp` 对象（`host` 可选）。收到 `open` 后，connector 必须在 10 秒内打开会话数据连接（§5.1），否则 relay 以 4408 `host_timeout` 关闭 App 一侧。

relay 还可能发送：

```json
{ "t": "usage", "usage": { …§6.3… } }
```

在用量变化跨过阈值或预算状态改变时推送，connector 只用于状态显示。

未知的 `t` 必须忽略。文本 `ping` 由 relay 回复 `pong`，不得要求 connector 处理。

认证之后，connector 在控制连接上除文本 `ping` 外不得发送任何消息；relay 收到其他消息时以 1008 `bad_request` 关闭控制连接。

## 5. 会话

### 5.1 建立

```
App                         relay                         connector
 │ WS /v1/connect/{hostId}?ch=control
 │ ─────────────────────────▶ │  {"t":"open",sid,ch,pace} ─▶ │
 │                            │ ◀─ WS /v1/host/{hostId}/session/{sid}
 │ ◀─ {"t":"ready", …}        │                               │
 │ ◀──────────── 端到端握手（文本帧，relay 原样转发） ───────▶ │
 │ ◀══════════════ 二进制帧：SSH 字节 ═════════════════════▶ │ ⇄ 127.0.0.1:22
```

`ready`（relay 发给 App，之后 relay 不再主动向该会话发送文本）：

```json
{
  "t": "ready",
  "sid": "…",
  "ch": "control",
  "pace": { "kbps": 1000, "burstKb": 2048 },
  "capabilities": { "dgram": "none", "stream": true },
  "policy": { …§6.1… },
  "usage": { …§6.3… },
  "udp": { "port": 8444, "host": "relay.example.com", "token": "<b64u 16B clientToken>" }
}
```

- `capabilities`：与 `hello` 相同的能力对象（可选；旧版中转可省略，新客户端可据此立即感知新开启的 UDP 能力）。
- `udp`：仅当 `ch` 为 `"media-dgram"` 时提供（`host` 可选；未提供时使用中转 URL 的主机名）。
- `ch` 为 `"media-dgram"` 时，§5.2 的端到端握手仍在控制连接（WS / HTTP 流）上完成，`auth` 签名中的 `ch` 字段即为 `"media-dgram"`；但之后的音视频与输入控制数据不再走 WS 二进制帧，而是通过 §5.5 的原生 UDP 数据报通道转发，控制连接仅用于端到端握手、`ping`/`pong` 保活与关闭通知。

`ready` 之后，relay **原样转发**两侧的文本帧和二进制帧，唯一例外是文本 `ping`：由 relay 直接回复 `pong`，不转发。

文本帧只用于端到端握手（§5.2）：除 `ping` 外，每一侧在整个会话中最多发送 2 条文本帧（App 只需要 `auth`；connector 需要 `challenge` 和 `ok`/`denied`）。超出时 relay 以 1008 `bad_request` 关闭这一侧，并以 1000 `peer_closed` 关闭另一侧。转发的文本帧计入字节数，但不算活动（§5.3 空闲、§6.3 活跃分钟）。

### 5.2 端到端握手

在任何二进制帧之前：

1. connector → App：`{"t":"challenge","v":1,"nonce":"<b64u>"}`
2. App → connector：

   ```json
   { "t": "auth", "device": "<b64u devicePub>", "sig": "<b64u>" }
   ```

   `sig` 为 ECDSA P-256 / SHA-256 的 DER 签名，签名内容：

   ```
   UTF8("miffan-relay/v1 session\n" + hostId + "\n" + sid + "\n" + ch + "\n" + nonce)
   ```

3. connector 验证：`deviceId(device)` 在本机允许列表中，且签名正确。
   - 通过：`{"t":"ok"}`，然后连接本机目标（§7）并开始双向转发二进制帧。
   - 不通过：`{"t":"denied","reason":"unknown_device"}` 或 `"bad_signature"`，然后以 4403 关闭。
4. App 在收到 `ok` 之前不得发送二进制帧；connector 在发出 `ok` 之前收到二进制帧，必须以 4403 关闭。
5. connector 必须对失败做退避，但退避**只能拒绝本来就会失败的请求**：允许列表中的设备只要签名正确，就必须放行，不受任何失败计数影响（设备公钥和 `hostId` 都不是秘密，否则任何人都能靠故意失败把主人锁在门外）。
   - 全部请求一分钟内失败 20 次后，未知设备和无法解析的 `auth` 一律拒绝 1 分钟。
   - 同一已配对 `deviceId` 一分钟内签名错误 5 次后，该设备名下的错误签名拒绝 5 分钟。
   - 退避期间的拒绝以 `{"t":"denied","reason":"busy"}` 回复。
6. connector 发出 `challenge` 后最多等 5 秒；收不到 `auth` 就关闭会话（它在 relay 上占着该电脑的一个会话名额）。

`sid` 由 relay 随机生成并进入签名，因此签名不能在其他会话中重放；`nonce` 由 connector 生成，relay 无法预先构造。

### 5.3 转发与帧

- 二进制帧不超过 65,536 字节，文本帧不超过 4,096 字节；超出时 relay 以 4413 关闭。
- 每个方向在任意 10 秒内最多 6,000 帧（文本和二进制合计，`ping` 不算）；超出时 relay 以 4429 `rate_limited` 关闭整条会话（§6.2）。
- 发送方应当把小块数据攒成较大的帧（平台按帧计费，见 §6.2），同时不拖慢交互：
  - 满 16 KiB 立即发出；
  - 链路空闲后的第一批数据（例如按键回显）：连续 2 ms 没有新数据就立即发出，最晚在第一个字节到达后 10 ms 发出；
  - 数据连续流动时（距上一帧不到 25 ms），每帧最多再攒 40 ms。
  - 旧版发送方"少于 16 KiB 时最多再等 10 ms"的做法仍然合规，只是帧数更多。
- 一侧关闭（任意关闭码），relay 必须以 1000 `peer_closed` 关闭另一侧。
- 会话连续 `policy.limits.idleTimeoutS` 秒没有二进制帧时，relay 以 1000 `idle` 关闭（文本 `ping` 不算活动）。

### 5.4 保活

- 两侧都应当每 30 秒发送一次文本 `ping`。relay 必须在不唤醒会话状态的前提下回复 `pong`（Cloudflare 实现使用 WebSocket auto-response）。
- **存活判定**：一条连接超过一定时间既没有 `ping` 也没有二进制帧，relay 就把它视为已消失（电脑睡眠、手机切换网络时，TCP 连接往往不会正常关闭）。电脑一侧（控制连接，以及会话中 connector 的数据连接）为 **75 秒**；会话中 App 一侧为 **150 秒**，因为手机锁屏休眠时不发 `ping`，锁屏两分钟左右回来时会话应当还在。relay 只在本来就醒着的时候检查（新会话请求、已到期的 alarm），不为此额外唤醒：
  - 控制连接：视为电脑离线，以 4404 `host_offline` 关闭；新会话请求直接得到 404 `host_offline`，而不是等 10 秒后的 `host_timeout`。
  - 会话：任一侧消失，就以 1000 `peer_closed` 关闭整条会话，并且不再占用 `sessionsPerHost` 的名额。
  - 因此连接两侧的 `ping` 间隔不得超过 30 秒（电脑一侧的 75 秒允许丢一次）。
- App 在经 relay 的 SSH 会话上不应当启用 SSH 层保活（JSch `ServerAliveInterval`）。

### 5.5 原生 UDP 数据报转发（`ch=media-dgram`）

当服务端宣告 `capabilities.dgram == "udp"` 且在线 connector 在 `auth` 中宣告 `"dgram": true` 时，App 可请求 `ch=media-dgram` 会话用于 Sunshine 高性能串流。

#### 报文格式

每个发往或来自中转 UDP 端口（`capabilities.udpPort`）的数据报格式为：

```text
[0..15]  16 字节会话令牌（App 持 clientToken，connector 持 hostToken）
[16]     1 字节流标识 flow
[17..N]  载荷字节（总包长 17..2048 字节）
```

`flow` 取值：

| `flow` | 方向 / 用途 | 电脑端本机目标（仅 `127.0.0.1`） |
|---|---|---|
| `0x01` | Sunshine 视频 RTP / FEC 数据与回包 | `127.0.0.1:47998` |
| `0x02` | Sunshine ENet 控制 / 键鼠输入 | `127.0.0.1:47999` |
| `0x03` | Sunshine 音频 RTP / ping | `127.0.0.1:48000` |
| `0xFF` | 绑定 / 探测 / NAT 保活（载荷 `0..16` 字节，由客户端发给 relay） | 不转发 |
| `0xFE` | 探测回显（由 relay 发回发送端，原样带回 `0xFF` 的令牌与 `0..16` 字节载荷） | 不转发 |

#### 绑定、防放大与转发规则

- **地址绑定与防放大**：relay 不根据控制连接的来源 IP 预设数据报目标地址。当且仅当携带合法 `clientToken` 或 `hostToken` 的数据报（`0xFF` 或 `0x01..0x03`）到达时，relay 记录该侧最新的 `(address, port)`（允许手机切网或 NAT 重绑定后无缝切换）。对端尚未绑定地址前，发往该对端的数据报静默丢弃；未知令牌静默丢弃，不产生任何响应字节。
- **零放大探测**：`0xFF` 载荷上限 16 字节，relay 原地将 `flow` 改写为 `0xFE` 后直接发回原 `(address, port)`，响应字节数与请求完全相等（最多 33 字节）。App 与 connector 均应当通过 `0xFF`/`0xFE` 验证 UDP 端口可达性后再开始媒体流传输，并每 15 秒发送一次空的 `0xFF` 维持 NAT 映射。
- **原地令牌替换与非阻塞转发**：转发 `0x01`/`0x02`/`0x03` 时，relay 将报文头部的发送方 16 字节令牌原地替换为对端令牌，经 `media` 通道令牌桶监督后转发给对端已绑定的 `(address, port)`。
- **限速、计量与生命周期**：
  - 字节率限制与 `ch=media` 共用 `policy.media` 的 `kbps` / `burstKb` 与 1.5 倍突发窗口，但帧率上限为 `0`（不限 UDP 包数）。**超速的数据报直接静默丢弃**，不关闭会话控制连接。
  - 仅 `0x01`/`0x02`/`0x03` 成功转发的数据报计入该电脑 `media` 流量与活跃分钟并重置 `idleTimeoutS`（不计入 `hostDailyMessages` / `globalDailyRequests`）；`0xFF`/`0xFE` 探测与保活不计流量，也不重置 `idleTimeoutS`。
  - 会话控制连接关闭、空闲超时、或 `media` 配额 / 全局预算耗尽时，relay 关闭控制连接并立即注销 `clientToken` 与 `hostToken`。

## 6. 策略、限速与计量

### 6.1 策略对象

`/v1/info`、`hello`、`ready` 中的 `policy` 结构相同：

```json
{
  "access": { "mode": "open", "registrationsPerIpPerDay": 5 },
  "control": { "kbps": 1000, "burstKb": 2048 },
  "media": { "allowed": true, "kbps": 4000, "burstKb": 4096 },
  "limits": { "sessionsPerHost": 4, "idleTimeoutS": 900 },
  "quotas": {
    "hostDailyMb": 500,
    "hostDailyActiveMin": 0,
    "hostMonthlyControlMb": 2048,
    "hostMonthlyMediaMb": 10240,
    "hostDailyMessages": 0
  },
  "budget": { "globalDailyMb": 0, "globalDailyActiveHours": 0, "globalDailyRequests": 0, "mediaReserve": 0.3 }
}
```

数值 `0` 表示不限。字段缺失按不限处理；客户端必须忽略未知字段。

`quotas.hostDailyMessages` 是每台电脑每个 UTC 日的消息配额：该电脑所有会话中 relay 收到的帧（文本和二进制，`ping` 除外）合计。

`budget.globalDailyRequests` 是全局每日"平台请求数"预算，按 Cloudflare 的计费口径由 relay 自行估算：收到的 WebSocket 消息（`ping` 除外）每 20 条计 1 次，每次接受的 WebSocket 升级、每次 alarm、每次对全局预算服务的调用各计 1 次。与其他全局预算一样，剩余低于 `mediaReserve` 时进入 `media_paused`，用完时为 `closed`（§6.4）。

### 6.2 限速

- **发送方自行限速**：App 对上行、connector 对下行，按 `pace`（控制通道取 `control`，画面通道取 `media`）用令牌桶限速。
- **relay 监督**：对每个会话的每个方向统计最近 10 秒转发的二进制字节数；超过 `(kbps × 10 / 8 × 1000) + burstKb × 1024` 的 1.5 倍时，以 4429 `rate_limited` 关闭该会话。同一窗口内的帧数（文本和二进制合计）超过 6,000 时同样关闭：平台对每条收到的消息计费，帧数和字节数一样需要上限。
- 因此把画面流量标成 `control` 的会话，要么被发送方限速到不可用，要么被 relay 关闭。

### 6.3 计量与用量对象

relay 按 `hostId` 记录：

- 每个 UTC 日、每个通道的转发字节数（两个方向合计）；
- 每个 UTC 日的**活跃分钟数**：该电脑任一会话转发过二进制帧的不同 UTC 分钟数；
- 每个 UTC 日的**消息数**：该电脑所有会话中 relay 收到的帧数（`ping` 除外）；
- 每个 UTC 月、每个通道的字节数。

日与月都按 UTC 划分（与 Cloudflare 免费额度的重置时间一致，北京时间 8 点）。

```json
{
  "day": "2026-11-01",
  "month": "2026-11",
  "dayMb": 120.5,
  "dayActiveMin": 14,
  "monthControlMb": 300.2,
  "monthMediaMb": 3100.0,
  "dayMessages": 182000,
  "budget": "ok",
  "resetsAt": "2026-11-02T00:00:00Z"
}
```

`dayMessages` 是当天的消息数（较旧的 relay 不提供此字段）。`budget`：`"ok"`、`"media_paused"`（任一全局预算——流量、活跃时长、请求数——剩余低于 `mediaReserve`）、`"closed"`（任一全局预算用完）。

relay 只记录 `hostId`、字节数、分钟数与时间。不得记录帧内容；来源 IP 只用于注册频率限制，不得落盘超过一天。

### 6.4 执行

新会话请求按以下顺序检查，命中即拒绝：

| 条件 | 拒绝 |
|---|---|
| 访问码不符 | 401 `unauthorized` |
| `ch` 非法 | 400 `bad_request` |
| 电脑不在线 | 404 `host_offline` |
| `ch` 为 `media` / `media-dgram` 且 `media.allowed=false` | 403 `media_disabled` |
| `ch=media-dgram` 且中转未启用 `dgram="udp"` | 403 `dgram_disabled` |
| `ch=media-dgram` 且在线 connector 未声明 `dgram: true` | 403 `dgram_unsupported` |
| 全局预算 `closed` | 503 `relay_busy` |
| `ch` 为 `media` / `media-dgram` 且全局预算 `media_paused` | 503 `relay_busy` |
| 该电脑任一日/月配额用完 | 429 `quota_exhausted`（`retryAfter` 到重置时间） |
| 该电脑会话数已达上限 | 429 `too_many_sessions` |

会话进行中配额或预算耗尽：画面会话（`media` 与 `media-dgram`）以 4429 `quota_exhausted` / 4503 `relay_busy` 关闭并立即注销对应 UDP 令牌；控制会话允许继续到自然结束，但不得开新会话。例外是 `hostDailyMessages`：它用完时，该电脑**所有**正在进行的会话（控制和画面）都以 4429 `quota_exhausted` 关闭，超出配额的那一帧不转发；只影响这台电脑，控制连接保持在线并收到 `usage` 推送。

## 7. connector 的转发目标

- TCP 流会话（`control`、`media`）只能转发到 connector 配置的目标，默认 `127.0.0.1:22`。
- UDP 数据报会话（`media-dgram`）仅转发到本机 `127.0.0.1` 的 Sunshine UDP 端口：`47998`（视频 `flow=0x01`）、`47999`（控制 `flow=0x02`）与 `48000`（音频 `flow=0x03`）。
- connector 不得根据 relay 或 App 发来的任何内容改变目标；v1 没有"指定目标"的消息。
- 经 `control` / `media` 转发的 VNC、RDP 及 Sunshine 控制信令/RTSP（TCP 47984/47989/48010）仍通过 SSH direct-tcpip 通道到达本机服务。

## 8. 入口优选

`/v1/info.edges` 非空时：

1. 客户端把每个候选 IP 与默认 DNS 结果放在一起，并行测量到 `BASE` 主机名的 TCP + TLS 握手时间（SNI 与证书校验都使用 `BASE` 的主机名）。
2. 选最快者建立连接，失败时按测速顺序回退，最后回退到默认 DNS。
3. 结果缓存不超过 1 小时，网络变化（Wi-Fi ↔ 移动数据）时立即作废。
4. connector 第一次注册（`hostId` 尚无状态）前必须先做入口优选，并按选中入口所在区域带上 `hint`；区域由一次 `GET /cdn-cgi/trace` 的 `colo` 映射得到（实现自带映射表，未知时不带 `hint`）。

## 9. 原因码与关闭码

| reason | HTTP | WS 关闭码 | 含义 |
|---|---|---|---|
| `bad_request` | 400 | 1008 | 路径、参数或消息格式错误；WebSocket 上：`ready` 之前发帧、握手之外的文本帧、已认证控制连接上的非 `ping` 消息 |
| `unauthorized` | 401 | 4401 | 访问码错误，或主机签名错误/超时 |
| `media_disabled` | 403 | — | 本中转不提供画面通道 |
| `dgram_disabled` | 403 | — | 本中转未开启 UDP 数据报转发 |
| `dgram_unsupported` | 403 | — | 在线电脑的 connector 版本过旧，不支持 `media-dgram` |
| `denied` | — | 4403 | connector 拒绝了设备（端到端握手失败） |
| `host_offline` | 404 | 4404 | 电脑的 connector 不在线；WebSocket 上：控制连接超过 75 秒没有 `ping`（§5.4） |
| `host_timeout` | — | 4408 | connector 没有及时接起会话 |
| `frame_too_large` | — | 4413 | 帧超出大小限制 |
| `quota_exhausted` | 429 | 4429 | 该电脑的配额用完 |
| `too_many_sessions` | 429 | — | 该电脑会话数已达上限 |
| `too_many_registrations` | 429 | — | 来源 IP 注册新电脑过于频繁 |
| `rate_limited` | — | 4429 | 会话超出限速 |
| `relay_busy` | 503 | 4503 | 全局预算收紧或用完 |
| `replaced` | — | 4000 | 同一电脑的新控制连接取代了旧连接 |
| `backpressure` | — | 1013 | 接收方读得太慢，relay 为它缓冲的数据超过上限（Bun 实现为每条连接 4 MiB）；另一侧随后以 1000 `peer_closed` 关闭 |
| `peer_closed` / `idle` | — | 1000 | 对端关闭 / 会话空闲超时 |

WebSocket 关闭时，`reason` 字段携带上表中的原因码文本。

## 10. 版本

- 路径前缀 `/v1` 标识协议主版本；不兼容的修改使用 `/v2`，relay 应当同时支持旧版本一段时间。
- 同一主版本内只做向后兼容的增加：新增字段、新增消息类型、新增原因码。接收方必须忽略未知字段和未知消息类型，未知原因码按"连接失败"处理并原样显示。

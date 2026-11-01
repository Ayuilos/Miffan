# R1c 规格：电脑端 connector 与 helper 的 relay 命令

负责人：herdr agent（Claude Sonnet，high）。审核：主会话（安全相关，逐行审）。

先读：[relay/PROTOCOL.md](../../relay/PROTOCOL.md)（契约，以它为准）、[RELAY_PLAN.md](RELAY_PLAN.md) §6、§8、[research/RELAY_R0.md](research/RELAY_R0.md)、R0 原型 `relay/spike/connector/main.go`（WebSocket 收发、攒帧、`-connect-ip` 保留 TLS 名称的拨号写法）、`remote-helper/README.md`、`remote-helper/PROTOCOL.md`、helper 源码 `remote-helper/src/miffan.sh` 与 `remote-helper/src/windows/miffan.ps1`（看现有命令、JSON 输出、计划任务/服务的写法，例如 cua bridge 的 Windows 计划任务）。

## 1. 交付范围

1. `relay/connector/`：Go 实现的 connector（协议 v1 中 connector 一侧的全部行为）。
2. helper 新增 `relay` 命令组（POSIX 与 Windows 两份），负责下载校验、安装常驻服务、配对、状态。
3. `.github/workflows/relay-connector.yml`：在 tag `relay-connector-v*` 上交叉编译并发布 GitHub Release（含 `SHA256SUMS`）。只写工作流，不打 tag、不推送。
4. 测试（见 §5）与说明 `docs/remote-screen/RELAY_R1C_NOTES.md`。

## 2. connector

### 2.1 构建

- Go ≥ 1.22（本机若是旧版 Go，提示用户 `brew upgrade go`，由用户批准）；依赖尽量少（WebSocket 库一个即可，例如 `github.com/coder/websocket` 或 `gorilla/websocket`）。
- 目标：`darwin-arm64`、`darwin-amd64`、`linux-amd64`、`linux-arm64`、`windows-amd64`，`CGO_ENABLED=0`，`-trimpath -ldflags "-s -w -X main.version=…"`。
- 产物名：`miffan-connector-<os>-<arch>[.exe]`。

### 2.2 文件

| 平台 | 二进制 | 数据目录 |
|---|---|---|
| macOS / Linux | `~/.miffan/bin/miffan-connector` | `~/.miffan/connector/` |
| Windows | `%LOCALAPPDATA%\Miffan\bin\miffan-connector.exe` | `%LOCALAPPDATA%\Miffan\connector\` |

数据目录内：

- `host.key`：Ed25519 私钥种子（32 字节，原始二进制），权限 0600（Windows：仅当前用户可读的 ACL）。
- `config.json`：

  ```json
  {
    "relay": "https://relay.miffan.ayuilos.me",
    "accessToken": "",
    "target": "127.0.0.1:22",
    "devices": [ { "id": "<deviceId>", "pub": "<b64u SPKI>", "name": "PGEM10", "addedAt": "2026-11-01T12:00:00Z" } ]
  }
  ```

  原子写入（临时文件 + rename），权限 0600。`accessToken` 是部署者的访问码，不是秘密级别最高的东西，但同样不得打印到日志。
- `state.json`：运行状态（是否已连上中转、最近错误的原因码、最近连接时间、选中的入口），供 `status` 读取。
- `run.lock`：单实例锁。

`target` 由 `init` 根据本机 sshd 端口确定（POSIX 读 `sshd -T` 或 `/etc/ssh/sshd_config` 的 `Port`，Windows 读 `%ProgramData%\ssh\sshd_config`；读不到则 22），运行时只能来自配置文件，不得来自网络。

### 2.3 命令行

所有命令输出单行 JSON 到 stdout（便于 helper 转发），错误时 `{"success":false,"reason":"…","detail":"…"}` 且退出码非 0。

| 命令 | 行为 |
|---|---|
| `init --relay URL [--token T] [--target HOST:PORT]` | 不存在 `host.key` 时生成；写/更新 `config.json`；输出 `{"hostId","relay"}`。已存在密钥时不重新生成。 |
| `pair --device <b64u SPKI> --name <名称>` | 校验是合法的 P-256 SPKI；按 `deviceId` 去重加入允许列表；输出 `{"hostId","relay","deviceId"}`。 |
| `unpair --device-id <id>` | 从允许列表移除；输出剩余设备列表。 |
| `status` | 输出 `{"version","hostId","relay","running","connected","lastError","lastConnectedAt","edge","devices":[{"id","name","addedAt"}]}`；`running` 由锁文件判断。 |
| `run` | 前台运行（由服务管理器启动）。 |
| `version` | 输出版本。 |

`run` 运行期间，`pair`/`unpair` 修改的配置必须在下一次端到端握手时生效（每次握手读取允许列表，或监视文件变化）。

### 2.4 运行逻辑

按 PROTOCOL 实现，要点：

- **入口优选（§8）**：启动时 `GET /v1/info` 取 `edges`，与默认 DNS 一起并行测 TCP+TLS 握手（SNI/证书校验用中转主机名），选最快；失败按顺序回退；结果最多用 1 小时，连接断开重连时重新测。
- **首次注册的位置提示**：`state.json` 中没有"已注册过"标记时，用选中入口做一次 `GET /cdn-cgi/trace` 取 `colo`，按内置映射表得到 `hint`（至少覆盖北美西部 → `wnam`、北美东部 → `enam`、欧洲 → `weur`/`eeur`、亚太 → `apac`；未知则不带）。注册成功后写入标记，此后不再带 `hint`。
- **控制连接**：鉴权、`hello`、`open`、`usage`；每 30 秒文本 `ping`；断线后指数退避重连（1 秒起，上限 60 秒，加 ±20% 抖动）；收到 4000 `replaced` 时记录并按退避重连（另一个实例接管的情况由单实例锁避免）；收到 4401 时写入 `lastError` 并以较长间隔（5 分钟）重试。
- **会话**：每个 `open` 开一个数据连接 → 发送 `challenge` → 验证 `auth`（设备在允许列表、ECDSA P-256 DER 签名、签名内容严格按 PROTOCOL §5.2）→ `ok` → 连接 `target` → 双向转发。握手 10 秒超时。失败退避按 §5.2 第 5 条。
- **下行限速**：按 `open.pace` 用令牌桶限速；攒帧规则按 §5.3（不足 16 KiB 时最多再等 10 ms）；单帧不超过 64 KiB。
- **会话保活**：每 30 秒文本 `ping`；不得发送其他保活数据。
- **安全**：只连接 `config.target`；忽略任何试图改变目标的输入；日志不记录帧内容、签名、密钥、访问码；日志只写 stderr（服务管理器负责收集），并限制频率，避免被刷爆。

## 3. helper `relay` 命令

POSIX（`miffan.sh`）与 Windows（`miffan.ps1`）各实现一套，输入输出遵循 `remote-helper/PROTOCOL.md` 的现有约定（参数为固定词，用户数据走 stdin JSON，输出单行 JSON），并把新命令补进 PROTOCOL.md。helper 版本号各加 1，更新 `remote-helper/versions.lock`（按 README 的流程）。

| 命令 | stdin | 行为 |
|---|---|---|
| `relay status` | — | 未安装时 `{"installed":false}`；否则调用 `miffan-connector status` 并补充 `{"installed":true,"service":"running|stopped|missing","pinnedVersion"}` |
| `relay install` | `{"relay","accessToken"?}` | 下载本平台二进制 → 校验 SHA-256 → 安装 → `init` → 安装并启动常驻服务；已安装同版本则跳过下载；输出 `status` 的结果 |
| `relay pair` | `{"device","name"}` | 调用 `pair`，输出其结果 |
| `relay unpair` | `{"deviceId"}` | 调用 `unpair` |
| `relay uninstall` | — | 停止并移除服务、二进制；**保留** `host.key`（下次安装沿用同一个 hostId，已配对手机不受影响），除非 stdin 为 `{"forgetIdentity":true}` |

下载与校验：

- helper 内置固定的 connector 版本号，以及每个平台产物的 SHA-256（常量，随 helper 发版更新）。下载地址按顺序尝试：GitHub Release（`https://github.com/Ayuilos/Miffan/releases/download/relay-connector-v<版本>/<产物名>`），以及预留的镜像地址常量（先留空数组）。
- 校验失败必须删除下载文件并以 `reason:"checksum_mismatch"` 失败，不得安装。
- 测试与开发：支持环境变量 `MIFFAN_CONNECTOR_FILE=<本地路径>` 跳过下载（仍校验 SHA-256，测试时可同时用 `MIFFAN_CONNECTOR_SHA256` 覆盖期望值）。正式发版前 SHA-256 常量可以是占位值，在 NOTES 里写明。

常驻服务（随用户登录运行，不需要管理员权限）：

| 平台 | 方式 |
|---|---|
| macOS | `~/Library/LaunchAgents/me.ayuilos.miffan.connector.plist`，`KeepAlive`，`RunAtLoad`，`launchctl bootstrap gui/$UID` |
| Linux | `~/.config/systemd/user/miffan-connector.service`（`Restart=always`），`systemctl --user enable --now`；尝试 `loginctl enable-linger "$USER"`，失败不算错误，在 `status` 中报告 `linger:false` |
| Windows | 当前用户的登录计划任务（参考 helper 现有 cua 计划任务的创建方式与命名规则），隐藏窗口运行 `miffan-connector.exe run`，失败自动重启 |

## 4. GitHub 工作流

`.github/workflows/relay-connector.yml`：在 `relay-connector-v*` tag 上运行；`go test ./...`；交叉编译 5 个产物；生成 `SHA256SUMS`；创建 GitHub Release（prerelease）并上传。用 GitHub 托管的 runner。只提交文件，不触发。

## 5. 验收

1. `go vet ./...`、`go test ./...` 通过。测试至少包括：
   - 签名内容与验证（用 Go 生成 P-256 密钥模拟 App 签名；错误签名、未知设备、错 sid / ch / nonce 都失败）；
   - 失败退避（单设备 5 次、全局 20 次）；
   - hostId 计算（与 PROTOCOL 定义一致，给出一个固定向量，并写进 NOTES 供其他实现对照）；
   - 令牌桶限速与攒帧（数据量、帧大小上限）；
   - 一个 Go 写的最小假中转（只实现本测试需要的协议子集），端到端：注册 → open → 握手 → 转发到本地回显服务 → 关闭传播；
   - 入口优选：两个本地 TLS 服务、不同延迟，选中较快者；全部失败回退默认地址。
2. 交叉编译 5 个目标都成功。
3. helper：
   - `shellcheck`（README 里的方式）通过；按 `remote-helper/tests/` 现有模式为 `relay` 命令补 Python 测试（本地命令 stub、临时目录，不访问网络、不真的安装服务）；`python3 remote-helper/tests/*.py` 全部通过。
   - Windows 部分没有 pwsh 时，在 NOTES 中说明未运行的测试。
4. `./gradlew :app:assembleRemoteHelper` 通过（helper 资源能生成）。
5. NOTES：实现概要、hostId 测试向量、与协议的偏差（应为零）、未验证项、需要主会话或用户在真机上验证的步骤。

## 6. 规则

- 工作目录：worktree `.claude/worktrees/relay-connector`，分支 `feature/relay-connector`（基于 `feature/relay`）。只改 `relay/connector/`、`remote-helper/`、`.github/workflows/relay-connector.yml`、`docs/remote-screen/RELAY_R1C_NOTES.md`。
- 可以在本分支提交（提交信息末尾加 `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`），不得合并、推送、打 tag。
- 不得在本机安装常驻服务（不运行 `relay install` 的真实安装路径、不 `launchctl bootstrap`、不改 `~/Library/LaunchAgents`）；不得改 `~/.ssh`。真机安装由主会话负责。
- 不得复制任何签名配置、凭据、`local.properties` 到 worktree。

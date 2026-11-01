# R1c 实现说明：电脑端 connector 与 helper 的 relay 命令

分支 `feature/relay-connector`（基于 `feature/relay`），规格见 [RELAY_R1C_CONNECTOR_SPEC.md](RELAY_R1C_CONNECTOR_SPEC.md)。
没有合并、推送、打 tag；没有在本机安装常驻服务，没有动 `~/.ssh` 和 `~/Library/LaunchAgents`。

## 1. 交付物

| 路径 | 内容 |
|---|---|
| `relay/connector/` | Go connector（协议 v1 的 connector 一侧）、45 个测试、README |
| `.github/workflows/relay-connector.yml` | `relay-connector-v*` tag 上测试、交叉编译 5 个产物、生成 `SHA256SUMS`、创建 prerelease |
| `remote-helper/src/miffan.sh` | 新增 `relay` 命令组，helper 32 → **33** |
| `remote-helper/src/windows/Relay.ps1`（新）、`miffan.ps1` | Windows 的 `relay` 命令组（`#include Relay.ps1`），helper 14 → **15** |
| `remote-helper/versions.lock` | 末尾新增 `miffan.sh 33`、`miffan.ps1 15`（对展开后的文件计算） |
| `remote-helper/PROTOCOL.md`、`README.md` | 补 `relay` 命令协议与测试说明 |
| `remote-helper/fixtures/{sh,ps1}/probe*.json` | probe 里的 `helper` 版本号 32→33、14→15 |
| `remote-helper/tests/test_relay_helper.py`（新） | POSIX `relay` 命令测试 |
| `remote-helper/tests/windows/Test-Relay.ps1`（新）、`stage.py`、`README.md` | Windows 离线测试及接入 |

## 2. connector 实现概要

按文件：

- `identity.go`：hostId / deviceId（`base32(SHA-256)` 前 26 位小写）、Ed25519 host key（`host.key` 为 32 字节种子）、主机签名（`miffan-relay/v1 host\n<Host>\n<nonce>`）、设备 SPKI 解析（只收 ECDSA P-256，且必须是规范 DER）、会话签名验证（ECDSA P-256/SHA-256 DER，内容严格按 PROTOCOL §5.2）。
- `config.go`：数据目录（`~/.miffan/connector/` 或 `%LOCALAPPDATA%\Miffan\connector\`；环境变量 `MIFFAN_CONNECTOR_HOME` 仅供测试覆盖）、`config.json` / `state.json` 原子写（临时文件 + rename，0600；Windows 用 `icacls` 按 SID 限制为当前用户 + SYSTEM，rename 遇占用重试）。
- `commands.go`、`main.go`：`init / pair / unpair / status / run / version`，每条命令 stdout 一行 JSON，失败 `{"success":false,"reason","detail"}` 退出非 0；`pair`/`unpair`/`init` 用 `config.lock` 串行化读改写。
- `control.go`：控制连接。入口优选 → （首次注册时）`GET /cdn-cgi/trace` 取 colo 得 `hint` → `challenge`/`auth`/`hello` → `open`/`usage`；30 秒文本 `ping`；100 秒没有任何帧视为断线；指数退避 1→60 秒 ±20% 抖动，连接存活 ≥30 秒后复位；4000 `replaced` 按退避重连；4401 与 HTTP 401 / 平台层拒绝（非 relay JSON）按 5 分钟重试；`too_many_registrations` 等待 `retryAfter`（默认 1 小时）。`lastError` 写原因码。
- `relayclient.go`：到 relay 的全部网络访问。入口 IP 只改变拨号目标，TLS 的 SNI 与证书校验始终用 relay 主机名；`/v1/info`、`/cdn-cgi/trace`、WebSocket 拨号、并行 TCP+TLS 测速（每个候选 5 秒上限，最多 16 个）、结果缓存 1 小时（连接失败或断开后重测）、colo → `hint` 映射表（北美西 `wnam`、北美东 `enam`、西欧 `weur`、东欧 `eeur`、亚太 `apac`，另含 `sam/oc/afr/me`，未知不带）。
- `session.go`：每个 `open` 一个数据连接（10 秒握手期限）→ `challenge`（32 随机字节）→ 验证 `auth` → `ok` → 连接 `config.target` → 双向转发；二进制帧早于 `ok` 一律 4403；关闭向两侧传播。
- `handshake.go`：设备允许列表校验与失败退避（同一 deviceId 一分钟 5 次 → 拒绝 5 分钟；全局一分钟 20 次 → 拒绝所有新会话 1 分钟，回复 `denied/busy`）。每次握手重新读 `config.json`，所以 `pair`/`unpair` 对下一次握手立即生效。
- `pace.go`：令牌桶（`kbps` 千比特/秒，`burstKb` KiB，起始满，可透支一帧再还）与攒帧（读到不足 16 KiB 时再等至多 10 ms，单帧 ≤64 KiB；速率很低时帧长不超过 burst）。
- `logging.go`：只写 stderr，令牌桶限频（突发 20 行，之后每 2 秒一行，被丢弃的行合并成一行提示）；日志不含帧内容、签名、密钥、访问码，会话 ID 只打前 8 位。
- `sshport.go`：`init` 未指定 `--target` 时，先试 `sshd -T`，再读 `sshd_config`（跟随 `Include`，遇到 `Match` 停止，支持 `Port=N`），都不行用 22。
- `lock_*.go`、`perm_*.go`：单实例锁（Unix `flock`，Windows 以共享模式 0 打开文件）、权限限制。

## 3. helper 实现概要

两个平台命令、stdin、输出一致（见 `remote-helper/PROTOCOL.md` 新章节）。要点：

- 用户数据只走 stdin 并**原样**交给 connector（`init --stdin`、`pair --stdin`），由 Go 的 JSON 解析器处理，helper 里没有手写 JSON 解析，也没有任何用户数据进入命令行或 `eval`。`unpair` 的 id 在 helper 里先按 `^[a-z2-7]{26}$` 校验，才以 `--device-id=<id>` 传入。
- 下载：固定版本 + 每个平台的 SHA-256 常量；顺序尝试 GitHub Release 与镜像常量（空）；先下载到 `~/.miffan/bin/` 里的临时文件，校验通过才 `mv` 到位；失败删除临时文件并返回 `checksum_mismatch`。已安装的版本等于内置版本时跳过下载。下载沿用 cua 下载使用的电脑自己的代理。
- 服务：macOS LaunchAgent（沿用 task 的 `task_launchd_load`：`bootout` + `bootstrap gui/<uid>`），Linux `systemd --user`（`enable --now`，更换二进制后 `restart`，尝试 `loginctl enable-linger`，失败只在 `status` 里报 `linger:false`），Windows 计划任务 `Miffan-connector`（与 cua 守护任务同一模式：当前用户、交互登录、`RunLevel Limited`；失败自动重启）。Windows 任务运行 `powershell.exe -WindowStyle Hidden … miffan.ps1 relay run`，由它无控制台窗口地启动 `miffan-connector.exe run` 并把 stderr 追加到 `connector.log`。
- `uninstall` 默认保留 `host.key` **和 `config.json`**（规格只要求保留 `host.key`；允许列表一起保留才能做到“已配对手机不受影响”）。`{"forgetIdentity":true}` 删除整个数据目录。

## 4. 测试向量（供其他实现对照）

已用 Python（`hashlib` + `base64`）独立核对，与 Go 实现一致。

| 项 | 值 |
|---|---|
| Ed25519 种子（32 字节） | `000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f` |
| `hostPub`（32 字节，hex） | `03a107bff3ce10be1d70dd18e74bc09967e4d6309ba50d5f1ddc8664125531b8` |
| **`hostId`** | `kzdvvj2umnduyauf35o36k6kw4` |
| 设备 SPKI DER（P-256，hex） | `3059301306072a8648ce3d020106082a8648ce3d03010703420004b47cbd7bb4106d67a11c5cfcbc6631b0c84f3145674751846de5fb1d5aff7597be0c853ea088ea2b9d0710415a29c5c4941b98e30e9886f61c8388f7fb91c0c7` |
| 同上，b64u | `MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEtHy9e7QQbWehHFz8vGYxsMhPMUVnR1GEbeX7HVr_dZe-DIU-oIjqK50HEEFaKcXElBuY4w6YhvYcg4j3-5HAxw` |
| **`deviceId`** | `do6p7c6wsq3x7aus7ovinattgo` |

会话签名的待签内容（测试里 `sessionMessage`）：`"miffan-relay/v1 session\n" + hostId + "\n" + sid + "\n" + ch + "\n" + nonce`，`nonce` 为 challenge 里的 b64u 文本原样。ECDSA 签名本身带随机数，所以没有固定的签名向量；`handshake_test.go` 用现生成的 P-256 密钥覆盖正确、错 sid / ch / nonce / host、别的会话重放、别的密钥冒名、未知设备、乱码签名、Ed25519 冒充设备等情形。

## 5. 与协议的偏差

**协议条文上没有偏差。** 协议没有规定、由实现自行决定的地方（请主会话审阅）：

1. **设备被锁定（5 次失败后的 5 分钟）的回复**：协议只对全局锁定规定了 `denied/busy`。设备锁定也回 `{"t":"denied","reason":"busy"}` + 4403，不泄露“设备已知”。
2. **数据连接不带访问码**：协议 §3 表中会话数据连接的鉴权只是 `sid`，所以只有控制连接带 `Authorization`。（R0 原型两处都带。）
3. **失败计数**：只数“握手被拒”（格式错、未知设备、签名错、早到的二进制）。10 秒内没完成握手不计，避免手机中途断网把自己锁住。
4. **输出里多出的字段**：所有成功输出多一个 `"success":true`（helper 的退出码逻辑依赖它）；`init` 多 `target`；`status` 多 `target`、`usage`（relay 推来的最近用量，未连接时 null）；`status` 里 `connected`/`edge` 在 `running:false` 时强制为 false/null（`state.json` 可能是上次崩溃留下的）。App 按“忽略未知字段”解析即可。
5. **收紧**（规格未写，偏安全）：`target` 只接受回环地址（`127.0.0.0/8`、`::1`、`localhost`），`init --target` 与运行期读到的 `config.json` 都校验，不合格时不连接 relay、`lastError=bad_config`；`sid` 必须是 32 位小写 hex、`ch` 只能是 `control|media`，否则忽略该 `open`（防路径注入）；最多同时 16 个会话；relay 地址必须是 https，仅回环地址允许 http（开发）；设备名 1–64 字符且无控制字符；设备 SPKI 必须是规范 DER。
6. **代理**：若环境变量指定了到 relay 的代理，入口优选整个跳过（`edge` 显示 `proxy`）。服务管理器启动时一般没有这些环境变量，所以通常不起作用。
7. **Windows ARM64** 使用 amd64 产物（x64 仿真），没有单独的 arm64 构建。
8. **额外的 stdin 入口**：`init --token-stdin` / `init --stdin`、`pair --stdin`，让访问码和设备公钥不进命令行。规格里的 `--token`、`--device`、`--name` 照常可用。

## 6. 实际跑过的验证

- Go 1.27.3（本机原有的 Go 是 1.19.2，`go.mod` 要求 ≥1.22；我没有升级系统的 Go，而是把官方 `go1.27.3` 和 `go1.22.12` 压缩包下载到临时目录并核对了 go.dev 发布的 SHA-256，只在命令里临时使用。需要升级系统 Go 时仍由用户批准 `brew upgrade go`）。
  - `go vet ./...`、`gofmt -l` 无输出；`go test -race -count=1 ./...` 通过（45 个测试，反复跑 6 次稳定）。
  - Go 1.22.12 下 `go vet` 通过，测试需要 `-ldflags=-linkmode=external` 才能在本机（新版 macOS）启动测试二进制（旧 Go 的已知 `missing LC_UUID` 问题，与代码无关），加上后全部通过。
  - 5 个目标 `CGO_ENABLED=0 -trimpath -ldflags "-s -w -X main.version=…"` 交叉编译成功；darwin/arm64 产物带链接器 ad-hoc 签名，不需要公证（通过 SSH 下载也没有隔离属性）。
  - 另在 Windows/Linux 下 `go vet` 通过，Windows 的测试二进制可编译（未运行）。
- 覆盖的测试：hostId/deviceId 向量；主机与会话签名内容；错误签名/未知设备/错 sid、ch、nonce；失败退避（单设备 5 次/5 分钟、全局 20 次/1 分钟、窗口外的失败不累计、成功清零）；令牌桶（突发、持续速率、不超过 relay 的 1.5 倍监督线、取消）；攒帧（小写合并、孤立按键 10 ms 内发出、大数据不被拖延且帧 ≤64 KiB、EOF 前的数据不丢）；入口优选（两个不同延迟的本地 TLS 服务选较快者、证书名不对的“更快入口”被丢弃、全部失败回退默认地址、1 小时缓存与强制重测、`/v1/info` 取候选）；colo→hint；以及一个 Go 写的假中转的端到端：注册（含主机签名校验、首次带 `hint`、重连不带）→ `open` → 握手 → 转发到本地回显服务（1 MiB 校验一致，最大帧 ≤64 KiB）→ 双向关闭传播；访问码只发给控制连接；错误访问码与平台层拒绝（非 JSON 429）写入 `lastError`；运行中 `pair`/`unpair` 立即生效；畸形 `open`（路径穿越、非法 ch、带“目标”字段）被忽略；`config.json` 里的非回环 `target` 导致不注册。
- `./gradlew :app:assembleRemoteHelper` 通过（生成的 `miffan.sh`/`miffan.ps1` 与 `versions.lock` 新增两行一致）。
- helper：`python3 remote-helper/tests/test_relay_helper.py`（18 个用例）在 `/bin/sh` 与 `/bin/dash` 下都通过；原有的 `test_helper_safety.py`、`test_task_helper.py`、`test_sunshine_helper.py`、`test_space_helper.py` 通过。
- Windows：用 pwsh 7.6.9（macOS，官方发行包，已核对 SHA-256）对生成的 `miffan.ps1` 和 `Test-Relay.ps1` 做了语法解析（0 个错误），并运行了 `Test-Relay.ps1` 的 11 个离线用例（见下文第 7 点的限制）。

## 7. 没有验证的项

- **ShellCheck 没有在本机运行。** 本机没有；下载 ShellCheck 二进制的操作被权限分类器拒绝，我没有绕过。`sh -n` / `dash -n` 语法检查通过，并人工按 SC2086/SC2155/SC2034 一类检查过。CI 的 `check_shellcheck.py` 按“种类计数”比较（baseline 为 3 个 SC2155），请以 CI 结果为准。
- **Windows PowerShell 5.1 上一行都没有运行。** 只在 pwsh 7（macOS）做了语法解析和 `Test-Relay.ps1` 的离线用例（这些用例不碰计划任务、进程与网络，计划任务相关 cmdlet 被同名函数遮蔽）。以下全部**未执行**：`Register-ScheduledTask` 等计划任务参数（`-RestartCount`、`-StartWhenAvailable` 等）、`ProcessStartInfo` 的 stdin 字节写入与 stderr 读取、`relay run` 包装、下载（`Invoke-WebRequest`）、`Stop-RelayConnector` 与替换运行中的 exe、Windows 上 Go 代码的实际运行（`tryLock` 的共享冲突、`icacls` ACL、`rename` 覆盖）。`Test-Helper.ps1` 也没有运行。
- 没有在真实的 launchd / systemd / 计划任务上安装过（规格禁止）；`test_relay_helper.py` 里这些都是 stub。
- 没有连接过真实 relay（R0 的 dev relay 需要口令，未使用），也没有测过真实 Cloudflare 入口优选与 `/cdn-cgi/trace`；入口优选与 hint 只在本地 TLS 服务和假中转上验证。
- 令牌桶限速只做了单元验证，没有在端到端里测吞吐。
- `RemoteHelperVersionTest`、`RemoteHelperContractTest`（Gradle JVM 测试）**没有运行**：本 worktree 缺少 `material3` 依赖的子模块和 `web-ui/node_modules`，`:material3:compileDebugKotlin` 在改动之前就失败，与本任务无关。已手工核对 `generated helpers match their immutable version locks` 的逻辑：版本递增、两行锁的 SHA-256 与展开后的文件一致。

## 8. 需要主会话处理的事

1. **改 `app/src/test/.../RemoteHelperVersionTest.kt`**（规格不允许我改 `app/`）：第一个用例把 `"31" / "32"`（POSIX）和 `"13" / "14"`（Windows）分别改为 `"32" / "33"` 和 `"14" / "15"`，用例名里的 “POSIX 32 and Windows 14” 改为 “POSIX 33 and Windows 15”。不改的话版本升级后这个用例会失败（32 < 33 会触发刷新）。
2. **CI**：`.github/workflows/ci.yml` 里加 `python3 remote-helper/tests/test_relay_helper.py`，并加 connector 的 `go vet ./... && go test ./...`（`relay-connector.yml` 只在 tag 上运行）。规格没有让我改 `ci.yml`。
3. **发版流程**：
   1. 合并后打 tag `relay-connector-v1.0.0` → 工作流产出 5 个二进制与 `SHA256SUMS`（prerelease）；
   2. 把 `SHA256SUMS` 里 4 个 POSIX 产物的摘要填进 `miffan.sh` 的 `MIFFAN_CONNECTOR_SHA256_*`，`windows-amd64` 的摘要填进 `Relay.ps1` 的 `$script:RelayConnectorSha256`（现在全是 64 个 0 的占位值，**占位值不可能匹配，所以现在装不上任何东西**）；
   3. helper 版本再各加 1，`versions.lock` 追加新行（README 的流程）。
   工作流里的 `-X main.version=` 取自 tag，`relay-connector-v1.0.0` 对应 helper 常量 `1.0.0`；两者不一致时 helper 会每次都重新下载。
4. `actions/setup-go@v6`、`actions/upload-artifact@v7` 的版本号沿用仓库里其他工作流的写法，没有在 GitHub 上跑过；首次打 tag 前可以先用 `workflow_dispatch`（只构建并保留产物，不发布）试一次。

## 9. 真机上建议验证的步骤

1. 本机 macOS：用本地构建的二进制 + `MIFFAN_CONNECTOR_FILE` / `MIFFAN_CONNECTOR_SHA256` 跑 `miffan.sh relay install`（stdin 为 `{"relay":"…"}`），确认 LaunchAgent 装入、`relay status` 里 `service:"running"`、重启登录后自动运行、`relay uninstall` 后文件清干净而 `host.key` 保留。
2. Linux：同上，并看 `linger` 的值；在没有 `loginctl enable-linger` 权限的发行版上确认 `linger:false` 且安装仍成功。
3. Windows（PS 5.1）：先跑 `Test-Helper.ps1` 与 `Test-Relay.ps1`（`stage.py` 已接入），再真装一次：计划任务是否出现、登录后是否自动启动、**登录时是否闪出控制台窗口**、`connector.log` 是否有内容、替换运行中的 exe（再装一次）是否成功、Windows Defender 对二进制的反应（R0 遗留项）。
4. 对着 R0 的 dev relay 或本地起的 relay：`relay pair` 一台模拟设备后，用 Go 测试里的模拟 App（或后续 Kotlin 客户端）走一遍握手，确认 `hello`、`open`、数据转发和 `usage` 推送在真实 relay 上的表现，特别是 Cloudflare 的 WebSocket 自动 `pong` 与 100 秒“无帧即断线”的判断是否相容。
5. 手机移动数据下的入口优选耗时（R0 遗留项）。

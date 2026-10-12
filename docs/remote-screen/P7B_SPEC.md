# P7b：Windows 辅助脚本 `miffan.ps1`（Codex 开发任务）

先读：`docs/remote-screen/P7_WINDOWS_PLAN.md`（已确定的决策）、`docs/remote-screen/research/WINDOWS_SPIKE.md`（A 部分 Sunshine、A4 防火墙、A6 helper 能力、C1 编码），以及现有的 `app/src/main/assets/remote/miffan.sh`（`probe`、`sunshine`、`clip` 的输出格式）和 `RemoteScreenRepository.kt` 中对这些输出的解析。

## 目标

写出 Windows 版辅助脚本 `app/src/main/assets/remote/miffan.ps1`。它的命令和 JSON 输出要和 `miffan.sh` 对齐，App 解析时不需要区分平台。本阶段只交付脚本和测试，**不接入 App**：接入要等 P7a 的 Windows 执行层合并之后，作为 P7b 的第二部分另行安排。

## 运行方式（与 P7a 的约定）

- App 会通过 P7a 的执行层调用 PowerShell 5.1。脚本以 UTF-8（带 BOM，PowerShell 5.1 依赖它识别编码）安装到 `%LOCALAPPDATA%\Miffan\bin\miffan.ps1`，调用形式为 `& "$env:LOCALAPPDATA\Miffan\bin\miffan.ps1" <命令> <参数>`。
- 输出 UTF-8，每条命令的结果是一行朴素 JSON。不能依赖 PowerShell 的对象格式化，ETS 扩展属性会把输出撑得很大。
- 秘密（密码、PIN）只能从 stdin 读取，不能出现在参数里。

## 命令

| 命令 | 内容 |
| --- | --- |
| `version` | 输出脚本版本号（与 `miffan.sh` 一样用整数，从 1 开始） |
| `probe` | `os=windows`、Windows 版本与版本号、当前登录的控制台用户和会话（`quser` 不可靠时用 WTS API）、是否锁屏（尽力而为，不确定时报 `unknown`）、显示器数量与分辨率、DPI 缩放、GPU 名称 |
| `sunshine probe` | 是否安装、版本（从文件版本信息读，**不要运行** `sunshine.exe --version`，它会轮转正在运行实例的日志）、服务状态、`sunshine.exe` 进程所在会话、端口监听、主机证书 DER 的 SHA-256（从 `cacert.pem` 解析成 X509 后再算，不能直接对 PEM 文本算）、`lan/wan_encryption_mode` 当前值。字段名与 `miffan.sh sunshine probe` 一致 |
| `sunshine start` | 服务未运行时启动，已运行时不做任何事 |
| `sunshine enforce-encryption` | 当前没有串流时，备份 `sunshine.conf`，只把两项加密设置改成 2，保留其他配置，原子替换后重启 `SunshineService`。有串流时拒绝 |
| `sunshine firewall check` | 列出与 `sunshine.exe` 相关、实际生效的入站规则。判断是否存在“TCP 对外开放”或“来源不限”的放行规则，并给出建议的变更（禁用哪些规则、新增哪条规则） |
| `sunshine firewall apply` | 从 stdin 读取 App 下发的变更（要禁用的规则 ID、允许的来源网段），先备份，再执行，执行完重新 check 并输出结果。只能禁用 `sunshine.exe` 的规则和管理名为 `Miffan-*` 的规则，不碰其他规则 |
| `clip` | 从 stdin 读 UTF-8 文本，写入**用户桌面会话（Session 1）**的剪贴板。SSH 进程在 Session 0，直接调 `Set-Clipboard` 写到的不是用户的剪贴板。参考实验里的“交互式计划任务”做法：用一个以普通权限运行在用户会话里的一次性任务完成写入，数据经过 ACL 只允许该用户访问的临时文件传递，用完删除。实测必须读回确认 |

不要实现 `cua` 相关命令，那是 P7c 的范围。

## 安全要求

- 修改任何配置或规则前先备份，备份放在 `%LOCALAPPDATA%\Miffan\backup\`，输出里带上备份路径。
- 防火墙变更只允许 UDP 47998–48000，来源只能是调用方给出的网段。任何 TCP 放行都拒绝。
- 计划任务的名字统一用 `Miffan-` 前缀，一次性任务用完即删。

## 测试机

- 连接信息在 `/Users/chenxiansheng/miffan/.deps/windows-spike/p7-test.properties`（测试专用私钥的路径在 `key`）。**不要读取或输出私钥内容。**
- Sunshine v2026.914 已安装，加密已经是 2 / 2。防火墙的现状见 `P7_WINDOWS_PLAN.md` 的“测试机当前状态”，已经是目标状态。
- 用户可能正在使用这台电脑。允许的操作：
  - 所有只读命令；
  - `sunshine start` 和 `sunshine enforce-encryption`（在测试机上应当都不产生变化）；
  - `sunshine firewall apply` **只能在变更为空、结果与现状一致的情况下运行**，用来验证幂等性；
  - `clip` 可以写入测试文本，但要在笔记里写明它会覆盖用户当前的剪贴板。每次运行前通过 herdr 告诉 Claude。
- 实际修改防火墙的那条路径，用一台 Windows 虚拟环境、模拟数据，或者用纯函数的单元测试（把“当前规则”和“计划变更”的计算写成可以离线测试的函数）来验证。**不要在测试机上实际禁用或新增规则。**
- 不要启动串流（Sunshine 同时只支持一个会话），需要串流测试时先问 Claude。

## 验收

- 用 Pester 或简单的 PowerShell 测试脚本，覆盖解析和计算逻辑：配置文件改写、规则判断、证书指纹、JSON 输出。测试放在 `app/src/test/resources/remote/` 或新目录 `tools/windows-helper-tests/`，并写明怎么在测试机上运行。
- 对测试机逐条运行只读命令，把输出贴进笔记（不含秘密）。
- 写 `docs/remote-screen/P7B_NOTES.md`：每条命令的输入输出格式、与 `miffan.sh` 字段的对照、测试结果，以及给 App 接入（P7b 第二部分）的说明。

## 交付

在本 worktree 的 `feature/p7b-windows-helper` 分支提交，不要合并到 `feature/im-4.0`。完成或遇到需要决定的问题时，用 `herdr pane send-text wJ:p1 "..."` 通知 Claude。

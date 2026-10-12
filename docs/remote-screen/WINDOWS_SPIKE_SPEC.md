# Windows 支持验证（Codex 实验任务）

## 背景

Miffan 目前的远程工作区、远程屏幕、伙伴操作电脑都只支持 macOS / Linux。2026-10-12 实测确认，App 连接 Windows 时第一步就会失败：

- 远程工作区连上 SSH 后会执行 `pwd`、`printf '%s' "$HOME"`（`WorkspaceRepository.kt:555`、`:613`）。Windows OpenSSH 的默认 shell 是 cmd，这些命令都会报“不是内部或外部命令”。
- 远程屏幕的 RDP 只能通过 bash 辅助脚本 `miffan.sh` 启动（`RemoteScreenRepository.kt:375`、`:423`）。Sunshine 高性能模式也明确限定了 Linux / macOS（`RemoteScreenRepository.kt:389`）。
- 伙伴操作电脑要靠辅助脚本拉起 cua-driver（`miffan cua`）。

用户决定先做验证，再写正式计划。初步方向：

- **画面用 Sunshine**。Windows 是 Sunshine 支持最好的平台，采集的是真实控制台桌面，和本机屏幕共享。Windows 专业版的 RDP 一旦远程登录就会锁住本机屏幕，和“用户与伙伴看同一块真实桌面”的设计冲突，只作备选。
- **伙伴操作用 cua-driver 的 Windows 版**。

先读这些了解现状：`docs/REMOTE_SCREEN_PLAN.md`、`docs/remote-screen/P6_SUNSHINE_PLAN.md`、`P6A_NOTES.md`、`P6B_NOTES.md`、`P6C_NOTES.md`、`P6F_NOTES.md`、`tools/p6a-probe/README.md`、`stream/scripts/test-emulator.py`、`app/src/main/assets/remote/miffan.sh`（`sunshine`、`cua`、`clip` 部分）、`app/src/main/java/me/ayuilos/miffan/data/ai/tools/ComputerTools.kt`。cua-driver 的 Windows 说明见 `~/.claude/skills/cua-driver/WINDOWS.md`（只读）。

## 测试机

- Windows 11 专业版 26200，主机名 DESKTOP-QHRKJUQ，本地管理员账号 `ekk`。局域网地址 `192.168.31.61`（WLAN，网络类型为“公用”），**目前没有 Tailscale**。
- SSH 命令：`ssh -i ~/.ssh/id_ed25519_new ekk@192.168.31.61`。主机 key 已在 known_hosts 里。登录后是**提升权限的管理员**令牌，默认 shell 是 cmd，控制台代码页是 936（GBK）。输出中文时先切换到 UTF-8（例如 PowerShell 里设置 `[Console]::OutputEncoding`），否则会乱码。
- 防火墙：用户加了一条 `Miffan-SSH-LAN` 规则，只允许 192.168.31.0/24 访问 TCP 22。系统的“远程桌面”入站规则组已关闭；RDP 服务开着，但只能经 SSH 隧道访问。
- 用户本人登录在控制台会话（Session 1），**可能正在使用这台电脑**。
- Mac（本机）和 Windows 在同一局域网。注意：P6a 时发现 macOS 的“本地网络”隐私限制会挡住 ad-hoc 签名命令行程序发往局域网的 UDP，所以 `p6a-probe` 从 Mac 直连 `192.168.31.61` 可能收不到媒体流（详见 `P6A_NOTES.md`）。

## 允许做的事

- 在 Windows 上安装 **Sunshine 最新稳定版**（LizardByte 官方发布，winget 或 GitHub 安装包都可以），并安装 **cua-driver**（官方 `install.ps1`，按用户安装），注册它的**按用户**自启动计划任务。
- 修改 Sunshine 配置：`lan_encryption_mode=2`、`wan_encryption_mode=2`。为测试设置 Sunshine 管理账号：密码随机生成，只存放在 `/Users/chenxiansheng/miffan/.deps/windows-spike/`（目录权限 0700，文件 0600，已被 git 忽略），不得写进命令行参数、笔记或日志。配对一个测试客户端，名字用 `miffan-win-spike`。
- 修改任何配置之前先备份，并在笔记里记下每项改动和撤销方法。
- 只操作实验自己打开的窗口（例如新开的记事本），用完关掉。
- 可以编译、运行仓库里已有的 `tools/p6a-probe`，以及 `stream` 模块的测试 APK（只用模拟器 emulator-5560，AVD `RikkaHub_API_35_16K`，不要碰 emulator-5554）。

## 不允许做的事

- **不改 Windows 防火墙**。安装完 Sunshine 后，**立刻检查**安装程序加了哪些入站规则。如果有 TCP 端口（47984 / 47989 / 47990 / 48010）对局域网开放，马上停下来，通过 herdr 告诉我（Claude 的 pane `wJ:p1`）。规则怎么收紧由我和用户决定。
- 不重启、不注销、不锁屏，不改 RDP 和 OpenSSH 的设置，不关闭或操作用户自己的窗口和程序。
- 遇到需要在桌面上点击才能继续的情况（UAC 弹窗、SmartScreen、Sunshine 首次运行的界面等），停下来通过 herdr 问我，不要用 cua-driver 去点。
- 不改 App 代码，不往 `feature/im-4.0` 提交。只在本 worktree 的 `research/windows-spike` 分支上提交笔记和实验脚本。

## 要回答的问题

每条都给出结论、实际命令或输出作为证据，以及参考出处。

### A. Sunshine

1. **安装与运行方式**：版本、安装路径、配置文件和凭据的位置。服务模型是什么（`SunshineService` 运行在 Session 0，再把 `sunshine.exe` 启动到控制台会话里？）。完全通过 SSH 管理时，能否抓到用户已登录的真实桌面。锁屏、UAC 安全桌面、开机后还没登录时分别是什么表现（只读源码和文档，或观察当前状态；不要主动锁屏）。
2. **编码**：这台电脑的 GPU 和 Sunshine 实际选用的编码器（NVENC / AMF / QSV / 软件编码），是 HEVC 还是 H.264。
3. **用现有栈连通**：TCP 经 SSH 转发、UDP 直连、强制加密（`ENCFLG_ALL` + `requireEncryptedStreams` 补丁），用 `p6a-probe` 或 `stream` 测试 APK 实测。记录编码、分辨率、帧率和是否加密。如果 Mac 的本地网络限制导致 UDP 收不到，先试模拟器。两种都不通的话，告诉我，我请用户在 Windows 上装 Tailscale。
4. **防火墙**：安装程序加了哪些规则。要符合“TCP 只走 SSH、UDP 47998–48000 只对局域网 / VPN 开放”的模型，最少需要哪些规则（给出建议命令，不要执行）。
5. **输入**：Windows VK 键码直接可用（Win、Alt、F 键、方向键）；绝对坐标在 DPI 缩放和多显示器下是否准确。**中文输入**：现有方案是先经 SSH 写剪贴板，再发 Ctrl+V。但 SSH 会话里的进程不在用户的桌面会话，用 `Set-Clipboard` 写到的不是用户的剪贴板。找出可行办法（例如通过 cua-driver 的 `clipboard_write`，或者在用户会话里运行的计划任务），并实测把“中文测试”粘贴进记事本。
6. **辅助脚本需要的能力**：用 PowerShell 实现 `sunshine probe / start / enforce-encryption / wake` 时，各需要哪些命令（服务状态、版本、端口、证书 SHA-256、加密配置、显示器唤醒）。列出命令和输出格式，不必写完整脚本。

### B. cua-driver

1. 安装的版本、路径，自启动计划任务的名称和登录类型。确认 daemon 运行在 Session 1 而不是 Session 0。
2. 从 SSH 运行 `cua-driver mcp --socket \\.\pipe\cua-driver` 能否用 stdio MCP 驱动用户桌面：工具列表和参数结构，并与 `ComputerTools.kt` 里用到的工具名对比，列出差异。注意 SSH 会话是高完整性级别的管理员令牌，而 daemon 是普通用户权限，命名管道能否访问。
3. 实测：`list_apps` / `list_windows`、截图、启动记事本、在记事本里输入中英文、点击、`clipboard_write` / `clipboard_read`，默认后台投递（不抢前台）。完成后关掉记事本，不保存。
4. Sunshine 串流和 cua-driver 同时工作时有没有冲突。
5. 用户桌面锁屏时 cua-driver 是什么表现（只查文档，不实测）。

### C. 命令层

1. Windows OpenSSH 的 exec 语义：默认 shell 是 cmd。比较几种执行方式：`powershell -NoProfile -NonInteractive -EncodedCommand`、通过 stdin 传脚本，以及“把 OpenSSH 默认 shell 改成 PowerShell 或 Git Bash”（只调研，**不要改**）。各自在退出码、stdin、输出编码、换行符上的表现。查一下这台电脑有没有 Git Bash 或 WSL。
2. 列出 App 里依赖 POSIX shell 的地方（工作区、文件和终端工具、远程屏幕、电脑接入引导、辅助脚本的安装），注明文件和行号，并标出哪些可以改用平台相关的命令适配层。
3. 用户主目录和路径：`$HOME` 对应的值（`C:\Users\Ekk`），工作区路径应该用 `C:/...` 还是 `/C:/...`。SFTP 在 Windows OpenSSH 上的路径形式。

## 交付

- `docs/remote-screen/research/WINDOWS_SPIKE.md`（中文），结构如下：
  - 先写结论和建议：Windows 支持采用什么架构，以及各部分工作量的粗略估计。
  - 逐题回答。
  - Windows 上所做改动的清单和撤销方法。
  - 风险清单。
  - 需要用户决定的问题。
- 实验用的脚本放在 `tools/windows-spike/`。
- 在 `research/windows-spike` 分支提交。完成后通过 herdr 通知我（pane `wJ:p1`）。
- 测试凭据、配对身份、下载的安装包都放在 `.deps/windows-spike/`（主 checkout 下，已被 git 忽略）或 `/private/tmp`，不要进 git。笔记里写明这些东西放在哪里。

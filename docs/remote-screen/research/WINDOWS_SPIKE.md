# Windows 支持验证（暂停记录，未完成验收）

日期：2026-10-12。分支：`research/windows-spike`。
依据：[WINDOWS_SPIKE_SPEC.md](../WINDOWS_SPIKE_SPEC.md)。

## 结论与建议

**本次验证尚未完成。安装 Sunshine 后立即触发规定的防火墙暂停条件，已通知 Claude `wJ:p1`，没有继续操作 Windows。** 不应据此宣布 Windows 串流、伙伴操作或中文粘贴可用。

建议架构仍采用 Sunshine 采集真实控制台桌面，TCP 管理/配对/RTSP 经 SSH，
媒体及控制 UDP 直连 LAN/VPN；伙伴操作通过 SSH stdio MCP 连接 Session 1 中的
cua-driver 命名管道。这是待实测的方向，协议依据来自 [P6a](../P6A_NOTES.md)、
[P6b](../P6B_NOTES.md)，不是本次 Windows 连通结果。

命令层需要显式 Windows 适配，不能直接执行当前 POSIX helper。SFTP 路径与
PowerShell 本地路径应分别处理。SSH 中的进程实际在 Session 0，不能把在那里
启动的 GUI/剪贴板进程等同于用户 Session 1 的桌面。

初步工作量（单人工作日，尚无 Windows GUI/串流验收，误差较大）：

| 部分 | 估计 | 主要内容 |
| --- | --- | --- |
| Windows 命令与路径适配 | 3–5 天 | 平台探测、UTF-8 stdin/stdout、退出码、SFTP 路径、工作目录 |
| PowerShell helper / 接入引导 | 3–5 天 | 安装、服务/任务探测、备份、安全加密配置、权限/防火墙提示 |
| Sunshine 接入 | 2–4 天 | 复用 stream，Windows 路由、编码、DPI/多屏、恢复验证 |
| cua-driver / 剪贴板桥 | 2–4 天 | 交互任务、管道权限、schema、用户会话中文粘贴 |
| 端到端验收 | 3–5 天 | LAN/VPN、控制权、锁屏诊断、不同机器/缩放 |

合计约 13–23 天；前提是后续 spike 验证方向成立。不是正式开发计划。

## 阅读范围与证据

开始远端操作前已通读规格列出的 REMOTE_SCREEN_PLAN、P6_SUNSHINE_PLAN、
P6A/B/C/F_NOTES、p6a-probe README、stream test-emulator.py、miffan.sh 的
sunshine/cua/clip 部分、ComputerTools.kt，以及只读
`~/.claude/skills/cua-driver/WINDOWS.md` 和其核心 SKILL.md。

本地原始证据（不含密码/PIN/私钥，不进 git）：

- `/private/tmp/windows-spike-baseline.json`
- `/private/tmp/windows-spike-command-probe.jsonl`
- `/private/tmp/windows-spike-release.json`
- `/private/tmp/windows-spike-install-firewall.json`
- `/private/tmp/windows-spike-tree.json`、`windows-spike-installer.wxs`、
  `windows-spike-setup.ps1`、`windows-spike-add-firewall.bat`：官方稳定版源码参考。

远端入口为 `ssh -T -i ~/.ssh/id_ed25519_new ekk@192.168.31.61`。
具体非秘密 PowerShell 命令见 [实验脚本](../../../../tools/windows-spike/README.md)。

## A. Sunshine

### A1 安装与运行

官方 GitHub `/releases/latest` 返回稳定版 `v2026.914.233613`，发布时间
`2026-09-15T01:17:17Z`，`prerelease=false`。使用官方 AMD64 MSI：
[发布页](https://github.com/LizardByte/Sunshine/releases/tag/v2026.914.233613)。

安装包 SHA-256：`1d7fed8beecd5889dc7ff14cf9f42d6d38f37c3066c13c6c2a5f4e91847e0ccf`。
本地及远端运行前均核对；远端 `Get-AuthenticodeSignature` 返回 `Valid`。
通过 `msiexec /i <文件> /qn /norestart REBOOT=ReallySuppress` 安装，随后立即查规则。
MSI 进程退出等待已返回，但 PowerShell 采集的 `InstallerExit` 是 null；
因此没有可引用的 MSI 成功退出码，需恢复后核实注册版本/服务/文件。
防火墙实际程序路径为 `C:\Program Files\Sunshine\sunshine.exe`。

[稳定版 WiX 安装器](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/cmake/packaging/wix_resources/sunshine-installer.wxs)
在静默模式运行 `sunshine-setup.ps1 -Action install -Silent`；
[安装脚本](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/src_assets/windows/misc/sunshine-setup.ps1)
包含 PATH、配置迁移、防火墙、服务、自启动步骤。没有自己重复执行这些脚本。

**待验证**：已安装文件/配置/凭据位置、服务实际令牌和 Session、SSH 管理下抓取真实桌面、
锁屏/UAC 安全桌面/未登录状态的版本源码依据。未锁屏、注销或重启，未观察安全桌面。

### A2 编码

基线命令 `Get-CimInstance Win32_VideoController` 输出：

```text
Name = NVIDIA GeForce RTX 3060
DriverVersion = 32.0.16.1088
VideoModeDescription = 2560 x 1440 x 4294967296 colors
```

NVENC 是候选编码器；**没有读取 Sunshine 编码器日志或串流，不宣称实际选中了 NVENC、HEVC 或 H.264**。

### A3 现有栈连通

**未进行** TCP 转发、配对、p6a-probe 或模拟器串流。尚无编码/尺寸/fps/加密结果。
没有测试 UDP，不能判断是否需要 Tailscale；Mac 与模拟器都失败后才通知 Claude 请求 Tailscale。
现有严格策略见 P6A/B_NOTES 的 `ENCFLG_ALL` 与 `requireEncryptedStreams`。

### A4 防火墙（实际暂停原因）

安装前相关 Sunshine 规则为空。安装后立即执行：

```powershell
Get-NetFirewallRule -Direction Inbound | Where-Object {
    $_.DisplayName -match 'Sunshine' -or $_.Name -match 'Sunshine'
}
# 对每条读取 PortFilter、AddressFilter、ApplicationFilter
```

实测新增规则：

| Name | Protocol | Enabled / Action | Profile | LocalPort | RemoteAddress |
| --- | --- | --- | --- | --- | --- |
| `{E64063E9-6A00-4427-BC96-58CCC37AE7AF}` | TCP | True / Allow | Any | Any | Any |
| `{6A386F92-DB45-4BA6-BEDB-38569C64A5E7}` | UDP | True / Allow | Any | Any | Any |

两条 DisplayName 都为 `Sunshine`，Program 都为 `C:\Program Files\Sunshine\sunshine.exe`。
TCP 程序许可没有限定端口/来源/网络配置文件，包含 47984/47989/47990/48010；
与目标模型冲突。**这是规则层开放结论，尚未从 LAN 测试监听/握手可达性**。
源码证据：[add-firewall-rule.bat](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/src_assets/windows/misc/firewall/add-firewall-rule.bat)
用 `netsh ... action=allow protocol=tcp/udp program=... enable=yes`，没有端口或来源限制。

建议由 Claude/用户审阅后处理，以下命令**没有执行**：

```powershell
# 先备份这两条当前规则及各 filter。禁用宽泛规则而非增加另一条窄规则。
Disable-NetFirewallRule -Name '{E64063E9-6A00-4427-BC96-58CCC37AE7AF}'
Disable-NetFirewallRule -Name '{6A386F92-DB45-4BA6-BEDB-38569C64A5E7}'
New-NetFirewallRule -Name 'Miffan-Sunshine-UDP-LAN' -DisplayName 'Miffan Sunshine UDP LAN' `
    -Direction Inbound -Action Allow -Enabled True -Profile Any `
    -Program 'C:\Program Files\Sunshine\sunshine.exe' -Protocol UDP `
    -LocalPort 47998-48000 -RemoteAddress 192.168.31.0/24
```

不加任何 Sunshine TCP 对外 Allow。先确认各配置文件的默认入站阻断和已有叠加规则，
再核实 SSH 到主机 loopback TCP 可达、LAN 到上述 TCP 不可达。
将来 VPN 另加精确来源范围/接口限制；当前没有 Tailscale，不能照抄 VPN 配置。

### A5 输入及中文

**未测试** VK 按键、绝对坐标、DPI、多屏、中文粘贴。没有启动记事本。
用户会话剪贴板桥的候选是 `cua-driver clipboard_write`，但必须先核实实际安装版 schema、
命名管道权限、Session 1 写入以及 Ctrl+V 在实验窗口内的结果。
也不能以 SSH `Set-Clipboard` 成功替代用户桌面剪贴板验收。

### A6 PowerShell helper 能力

以下只为待验证命令设计，**不是已实测输出契约**：

| 操作 | 候选命令/输出 |
| --- | --- |
| probe | `Get-CimInstance Win32_Service`、`Get-Process` 读取路径/Session；文件 `VersionInfo`，不运行 Sunshine --version |
| 端口/活动 | `Get-NetTCPConnection`、`Get-NetUDPEndpoint` 按 Sunshine PID 过滤；未知状态要保守拒绝重启 |
| 证书 | 只读公共 cacert.pem，以 X509Certificate2.RawData 的 SHA-256 计算 DER 指纹，不能对 PEM 文本直接哈希 |
| 配置 | 验证实际配置位置、无 override、无流后，备份并原子替换 LAN/WAN 两项为 2，保留其他项 |
| start | 验证服务模型后用 `Start-Service`，已运行时幂等，未知服务/会话不猜测 |
| wake | 需要进一步查 Windows 版本/用户会话的可靠方案；不把 Session 0 的电源 API 当作屏幕唤醒证明 |

JSON 尽量兼容现有 `installed/version/running/lan_encryption_mode/wan_encryption_mode/`
`certificate_sha256/active_stream/candidates`，增加控制台会话信息；未知值输出 null。

## B. cua-driver

**B1–B5 全部待实测/版本文档核实**：没有安装、注册或启动 cua-driver。
基线 `CuaInstalled=false`，没有相关进程或服务。

已阅读的 Windows guide 要求 daemon 在交互 Session 1+，通过按用户 Interactive
计划任务启动；SSH 应使用 `cua-driver mcp --socket \\.\pipe\cua-driver`，
不能使用 bare mcp 的 Session 0 运行时。这是文档约束，尚无本机管道访问证据。

待验收清单：版本/安装路径/任务名和登录类型、普通用户 daemon Session 1、
高完整性 SSH 对命名管道的权限、tools/list schema、list_apps/list_windows/截图、
新记事本中英文与点击、clipboard_write/read、默认后台无焦点抢占、无保存关闭、
与 Sunshine 同时运行、锁屏文档行为。

App 的 `ComputerTools.kt:39`、`:45`、`:51` 精选 observe/action/foreground 工具；
现有列表没有 `clipboard_write`/`clipboard_read`。其余工具在拿到远端 tools/list 前
不能称为兼容。App 动态使用远端 schema，隐藏 session/输出文件参数并限制截图尺寸。

## C. 命令层

### C1 SSH exec / stdin / 编码

实际基线：PowerShell `5.1.26100.9444`；SSH PowerShell ProcessSession=0；
`quser` 用户 ekk、console、Session 1、Active。cmd `cd` 输出 `C:\Users\Ekk\r\n`。

`command-probe.py` 首轮实测：

| 方式 | 退出码 | stdout / 结论 |
| --- | ---: | --- |
| cmd `pwd` | 1 | stderr 报不识别命令，CP936 解码为 UTF-8 时乱码 |
| cmd `printf %s "$HOME"` | 1 | 同上，POSIX 命令不可用 |
| EncodedCommand，设置 UTF-8 stdout，`Write-Output "中文测试"; exit 7` | 7 | 正确 `中文测试\r\n`，hex `e4b8ade69687e6b58be8af950d0a` |
| EncodedCommand，`[Console]::In.ReadToEnd()` 读 UTF-8 stdin，exit 9 | 9 | 中文变为 `涓枃娴嬭瘯`，stdout 编码正确也不能补救 stdin 解码 |
| `powershell -NoProfile -NonInteractive -Command -` 通过 stdin 传 UTF-8 脚本，exit 11 | 11 | 中文同样乱码，脚本输入先按默认编码解码 |
| `wsl.exe --status` | 0 | 输出为 UTF-16LE，显示默认版本 2；仅证明命令可用，未查询/启动发行版 |

推荐显式 EncodedCommand（UTF-16LE Base64）执行**非秘密**固定引导代码，再用
明确 UTF-8 的二进制/StreamReader stdin 接收参数数据。EncodedCommand 可出现在
进程参数中，不能承载密码或 PIN。PowerShell 错误/进度可能是 CLIXML；初次基线出现，
runner 后来设置 `$ProgressPreference='SilentlyContinue'` 抑制进度。native 子程序
可能仍输出不同编码，需各自适配，不能只设置 `$OutputEncoding` 就宣称全部 UTF-8。

新增 `[Console]::InputEncoding=UTF8` 对照用例**暂停后没有再运行**。
没有修改 OpenSSH 默认 shell；注册表查询没有给出 DefaultShell 值，实测命令行为与
默认 cmd 一致。Git Bash 检查 `C:\Program Files\Git\bin\bash.exe=True`。
更换默认 shell / 显式 Git Bash / WSL 的兼容性调研尚未完成，不能把安装 Git Bash
当作 Windows 桌面能力适配完成。

### C2 当前 POSIX 依赖（已定位，尚未穷尽 UI 引导）

| 文件与行号 | 依赖 | 适配建议 |
| --- | --- | --- |
| `WorkspaceRepository.kt:555` / `:613` | `printf "$HOME"` / `pwd` | 平台 home/cwd 探测 |
| `RemoteScreenRepository.kt:382` | `uname -s` | SSH shell 独立平台探测 |
| `RemoteScreenRepository.kt:561` / `:562` / `:631` | mkdir、cat、chmod、mv、`$HOME/.miffan/bin` | 按平台 helper 安装/路径 |
| `RemoteScreenRepository.kt:619` / `:632` | bash + curl install.sh | Windows install.ps1 与用户任务流程 |
| `NativeSshWorkspaceTransport.kt:261` / `:772` | cd + POSIX 单引号 | exec 适配层与 shell 路径转换 |
| 同文件 `:307` / `:308` | stty、env、exec、/bin/sh、SHELL | Windows PTY/持久 shell 协议，单独设计 |
| 同文件 `:459` | SFTP chmod | Windows ACL/权限语义与写入回滚验证 |
| `RemoteTerminalCommandSpec.kt:15` | exec /bin/sh -c | 平台终端命令包装 |
| `PersistentShellProtocol.kt:75` / `:103` / `:106` | printf 控制符、shell 状态变量 | PowerShell 协议实现，不能仅替换命令名 |
| `miffan.sh:25` / `:111` / `:313` / `:638` / `:1040` | uname、POSIX 路径、clip、Sunshine、cua | Windows PowerShell helper |

文件/目录操作主要已经使用 SFTP，不宜统一改成 shell；应检查路径根、junction/reparse
point、大小写和 ACL 等 Windows 语义。电脑接入引导的 authorized_keys 命令、完整
平台/guide 路由及工具使用点仍需续查。没有改 App 代码。

### C3 主目录与路径

基线 `$HOME` 与 USERPROFILE 均为 `C:\Users\Ekk`。
SFTP 实测（仅 pwd/cd，不读取用户文件）：

```text
pwd                 -> Remote working directory: /C:/Users/Ekk
cd C:/Users/Ekk      -> stat remote: No such file or directory (exit 1)
cd /C:/Users/Ekk     -> 成功，pwd=/C:/Users/Ekk (exit 0)
```

本机 OpenSSH SFTP 的规范根应使用 `/C:/...`；传给 PowerShell/.NET 的本地路径使用
`C:\...` 或经核实的 `C:/...`。避免把 SFTP 根 `/C:/...` 原样交给 PowerShell `cd`。
还没有实测带空格/中文文件名、跨驱动器、junction、UNC 和写入流程。

## Windows 改动与撤销

| 实际改动 | 备份/证据 | 撤销方式（均未执行） |
| --- | --- | --- |
| 创建本实验 `%TEMP%\miffan-windows-spike`，导出全量 firewall rules 和 port filters | `firewall-before.clixml` / `firewall-ports-before.clixml` | 实验结束后仅删除该目录；导出不改变防火墙 |
| 安装 Sunshine 官方 MSI；安装器添加上面的两条 Allow 规则 | 官方 hash/signature，安装后的规则 JSON | 由 Claude/用户决定卸载或保留；卸载可能也删规则，不自行运行 |
| MSI 传入 Windows 临时目录 | `Sunshine-Windows-AMD64-installer.msi` | 恢复后按实验路径精确删除 |

安装器还设计了 PATH、服务和自启动改动；**安装后即停，尚未复核实际落地细节**。
用户自身 Windows/SSH/RDP 配置未由实验修改；没有手动修改防火墙。
没有生成 Sunshine 管理密码、PIN、客户端身份或会话密钥，没有配对客户端，未创建 cua 任务。

本地安装包在 `/private/tmp/windows-spike-sunshine-proxy.msi`；没有放入 git。
最初 Windows 全量 CIM 导出超过本地 180 秒等待；确认实验准备进程已自行退出后，
改用 Mac 现有代理下载及 scp，未改 Windows 网络/代理设置。直连下载超时。

## 风险与用户决定

1. 当前安装器 TCP/UDP 规则都过宽；未经收紧不得继续配对/串流验证。
2. Session 0 与 Session 1 隔离是核心问题；cua 管道权限、剪贴板和 UI 未验收。
3. 中文 stdin、native 输出编码、CLIXML 不能沿用 POSIX UTF-8 假设。
4. GPU 存在不证明硬件编码初始化成功；模拟器结果也不能替代真机性能。
5. 不操作用户窗口、不锁屏/UAC 测试；前台输入/多屏验证应限定实验窗口与协调后的时段。

需要 Claude 与用户决定：禁用/收紧安装器规则的具体方案、LAN 来源范围、后续 VPN
范围（若实际 UDP 双端失败再讨论 Tailscale）、是否继续保留 Sunshine 安装。
防火墙处理完成并通知恢复后，才能继续 A/B 与 C 的剩余验证。

已经通过 `herdr pane send-text wJ:p1` 成功发送暂停原因、规则 ID、证据位置和未做操作。
本记录是进度检查点，**不是完成通知或最终验收**。

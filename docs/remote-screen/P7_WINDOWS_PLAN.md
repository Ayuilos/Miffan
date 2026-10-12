# P7 Windows 支持计划

## 目标

让用户自己的 Windows 10 / 11 电脑和 Linux、Mac 一样接入 Miffan：

- 能作为远程工作区：文件工具、终端命令。
- 能看到并操作真实桌面：屏幕页。
- 伙伴能操作这台电脑：cua-driver。

连接方式不变：一切经已验证主机 key 的 SSH，手机上不监听端口。

依据：`research/WINDOWS_SPIKE.md`（2026-10-12 在用户的 Windows 11 专业版 + RTX 3060 上实测）。实验结论：

- Sunshine 能采集已登录的真实控制台桌面。TCP 走 SSH、UDP 直连、严格加密这一套成立：HEVC / H.264 都用 NVENC，持续打字时约 60 fps，RTT 19 ms。原生键盘、鼠标（坐标误差 1 像素）和中文粘贴都通过。
- cua-driver 0.34 的 59 个工具里，包含 App 用到的全部 23 个。从 SSH 能驱动用户桌面，但 SSH 会话是高完整性级别的管理员令牌，连不上普通权限的 daemon（见“伙伴操作”一节）。
- 现在的 App 在 Windows 上第一步就会失败：工作区执行 `pwd`、`printf "$HOME"`，屏幕和伙伴操作都依赖 bash 辅助脚本。

## 范围

第一版包含：

- Windows 10 / 11 家庭版和专业版。Sunshine 与 OpenSSH 在家庭版上都可用，这也是不选 RDP 的原因之一。
- 本地管理员账号或普通账号，用密钥登录。
- 单屏和多屏、任意 DPI 缩放（需在 P7e 验收）。

不包含：

- RDP：Windows 专业版的 RDP 一旦远程登录就会锁住本机屏幕，和“用户与伙伴看同一块真实桌面”的设计冲突；家庭版也没有 RDP 服务端。
- VNC。
- Windows Server 和多用户远程桌面会话。
- 操作锁屏、UAC 安全桌面和登录界面。

## 架构

```text
手机 ── SSH（固定主机 key，Windows OpenSSH，默认 shell cmd，不改）
  │     ├── PowerShell 执行层：EncodedCommand 只放固定的引导代码，数据走 UTF-8 stdin
  │     ├── SFTP：路径形如 /C:/Users/<用户>/...
  │     ├── miffan.ps1：探测、Sunshine 管理、防火墙检查、剪贴板、cua 桥
  │     ├── direct-tcpip → 127.0.0.1:47984 / 47989 / 48010（Sunshine HTTPS、配对、RTSP）
  │     └── cua 桥（用户会话里的普通权限进程）→ cua-driver daemon
  └── UDP 直连 → 局域网 / Tailscale 地址:47998–48000（Sunshine 画面、控制、输入）
```

### 1. 平台识别与命令层

- 主机记录 `platform = windows`。识别办法：SFTP 的 `realpath .` 返回 `/X:/...` 形式，或者 POSIX 探测命令失败后，用固定的 PowerShell 探测命令确认。识别结果和现有的 `screenPlatform` 一起保存。
- 新增 `RemoteShell` 抽象，分成 POSIX 和 Windows PowerShell 两种实现。所有 `execute(...)` 调用都经过它，不再直接拼 shell 字符串。Windows 实现的要求：
  - 进程参数里只放固定的 `-EncodedCommand` 引导代码。引导代码设置 UTF-8 的 InputEncoding、OutputEncoding，并关闭进度输出。
  - 参数和数据走 UTF-8 stdin。实测直接用 `-Command -` 传脚本时，中文会先按 CP936 解码而损坏。
  - 密码、PIN 不能出现在命令行参数里。
  - 输出用朴素的 JSON，不能依赖 PowerShell 的对象格式化。
- 不修改 OpenSSH 的默认 shell。这是主机级的设置，会影响用户自己的其他 SSH 用法。

### 2. 工作区

- 主目录与当前目录：用 PowerShell 获取 `$HOME` 和当前目录。SFTP 路径和本地路径要分开处理：传给 SFTP 的写成 `/C:/...`，传给 PowerShell 的写成 `C:\...`。
- 文件工具继续用 SFTP。`chmod` 在 Windows 上跳过；写入、回滚、中文文件名和带空格的路径要补测。
- 伙伴的终端命令：第一版每条命令单独执行一次 PowerShell，退出码、超时、输出截断与现有工具一致。持久 shell 协议（`PersistentShellProtocol`）和终端页的 PTY 放到后续版本。工具描述里写明这是 Windows PowerShell 5.1，避免模型写出 bash 语法。

### 3. 辅助脚本 `miffan.ps1`

- 安装到 `%LOCALAPPDATA%\Miffan\bin\miffan.ps1`。**不放在 TEMP**：实验里 cua 自启动任务依赖 TEMP 下的脚本，一旦清理就会坏。版本号和升级方式与 `miffan.sh` 一致。
- 命令与 `miffan.sh` 对齐：
  - `version`、`probe`（系统版本、会话、显示器、DPI）
  - `sunshine probe | start | enforce-encryption | firewall`
  - `clip`（写入用户会话的剪贴板）
  - `cua status | install | bridge`
- Windows 上 SSH 进程运行在 Session 0，用户桌面在 Session 1。凡是要碰用户桌面的操作（剪贴板、cua），都要经过运行在用户会话里的进程，不能直接在 SSH 会话里调 `Set-Clipboard` 之类的命令。

### 4. 屏幕：只用 Sunshine

- 复用现有的 `stream` 模块与 `StreamDesktopSession`。去掉 `RemoteScreenRepository` 里“只支持 Linux / macOS”的判断，Windows 的探测、加密设置、证书核对都走 `miffan.ps1`。
- 只有 Sunshine 一条路，没有 VNC / RDP 回退。UDP 不通时，屏幕页说明原因（同一局域网，或配好 Tailscale 并放行 UDP 47998–48000）。
- **防火墙**：Sunshine 安装或升级时，会给 `sunshine.exe` 加两条“所有端口、所有来源”的放行规则，TCP 管理端口因此暴露在局域网里。实测新装后，局域网内任何人都能访问还没设账号的管理网页。`miffan.ps1 sunshine firewall` 负责：
  - 检测实际生效的规则；
  - 在 App 里列出要做的修改，取得用户同意后执行：禁用这两条宽泛规则，新增只允许 UDP 47998–48000 的规则，来源限于局域网网段和 Tailscale 网段；
  - 每次连接前复查，发现宽泛规则被重新打开时提示用户。
- 输入：键码直接用 Windows VK，Win 键对应 `VK_LWIN`。屏幕页的平台快捷键在 Windows 上用 Ctrl，Mac 专用按钮（调度中心等）隐藏，按需增加 Win 键、Alt+Tab、任务视图等入口。
- 中文：通过 `miffan.ps1 clip` 写入用户会话的剪贴板，再经 Sunshine 发送 Ctrl+V。实测可用。
- 锁屏、UAC 安全桌面：Sunshine 服务以 LocalSystem 运行，可能还能采集画面，但不承诺。屏幕页如实显示，伙伴则停止操作。

### 5. 伙伴操作：cua-driver

问题：SSH 会话是高完整性级别的管理员令牌，cua-driver 的 daemon 默认以普通权限运行在用户会话里，两者直连时 daemon 读不到客户端的令牌，会断开连接。实验验证了两种可行做法：

| 做法 | 优点 | 代价 |
| --- | --- | --- |
| **A. 普通权限 daemon + 普通权限桥**（推荐） | 桌面运行时保持普通权限；伙伴不能操作以管理员身份运行的程序，也操作不了安全桌面 | 要多写一个桥：`miffan.ps1` 在用户会话里注册一个普通权限的计划任务，SSH 端通过带认证的本地通道和它通信 |
| B. 以最高权限运行 daemon，SSH 直连 | 实现简单，实验已跑通 | daemon 持有管理员令牌。它的命名管道只校验调用者是不是同一个用户，**同一用户下的任何普通程序（包括恶意软件）都能借它以管理员权限操作桌面**，相当于给这台电脑开了一个绕过 UAC 的口子，而且不管 Miffan 是否在用都存在 |

推荐 A。伙伴本来就能经工作区终端以管理员身份执行命令（每次都要用户批准），B 带来的额外风险主要落在 Miffan 之外：电脑上的其他程序也能借这条通道提权。

其他要求：

- 实验里的文件桥只用于诊断，没有认证，也没有防重放。正式的桥要限定调用者、会话和目录权限，并使用一次性令牌。
- 后台模式下的 Ctrl+V 只会输入一个字母 `v`，粘贴必须走前台模式。伙伴输入中文时优先用 `type_text`（后台可用），需要粘贴时按现有授权规则申请前台操作。
- 隐私：Windows 11 的记事本会恢复上次的文档，实验中伙伴读窗口内容时读到了用户的历史文档。Windows 版的伙伴指引要写明：读取窗口内容只限于任务需要的窗口；打开应用时不要假设会得到一个空白窗口。
- cua-driver 安装用官方的 `install.ps1`，固定版本，按用户安装；自启动任务以**普通权限**注册。注意官方安装器默认会注册最高权限的任务，要显式改成普通权限。

### 6. 接入引导（轻松模式“连接电脑”）

实验里用户手动配置时遇到的问题，引导都要处理：

- `Add-WindowsCapability` 安装 OpenSSH 很慢，有时会卡住。备选办法是安装微软官方 Win32-OpenSSH 的 MSI 安装包。
- 授权公钥：管理员账号要写到 `C:\ProgramData\ssh\administrators_authorized_keys`，并用 SID 设置 ACL（中文系统的用户组名是本地化的），普通账号写到 `~\.ssh\authorized_keys`。
- 新连接的 Wi-Fi 默认是“公用网络”，而 OpenSSH 自带的防火墙规则只对“专用网络”生效。引导里用一条规则放行 TCP 22，来源只限局域网网段，网络类型不限。
- 做法：App 生成一段 PowerShell 脚本，用户以管理员身份粘贴运行一次。脚本完成上面所有步骤，最后输出连接信息（局域网 IP、Tailscale IP、用户名）。
- 接着在 App 里依次完成：识别主机 key、安装 Sunshine、收紧防火墙（需确认）、配对（PIN）、安装 cua-driver。

## 阶段

| 阶段 | 内容 | 负责 |
| --- | --- | --- |
| P7a 命令层与工作区 | 平台识别、`RemoteShell` 抽象与 PowerShell 实现、主目录与路径、SFTP 路径、单次执行的终端工具、单元测试，以及对测试机的实测 | Codex |
| P7b `miffan.ps1` 与 Sunshine | 辅助脚本安装与升级、探测、加密设置、防火墙检测与收紧、剪贴板桥；`RemoteScreenRepository` 的 Windows 分支 | Codex |
| P7c cua 桥 | 普通权限的桥任务与认证通道、`RemoteComputerRegistry` 接入、Windows 版伙伴指引 | Codex |
| P7d 界面 | Windows 接入引导与脚本页、防火墙确认、屏幕页的 Windows 快捷键、平台图标与文案 | Claude |
| P7e 验收 | 真机：手机经局域网和 Tailscale 连接、150% 缩放、多屏、锁屏与解锁、睡眠唤醒、Sunshine 升级后的防火墙复查、中文输入、伙伴操作 | Claude + 用户 |

粗估（实验的估计）：13–23 个工作日。P7a 和 P7b 可以并行；P7c 依赖 P7b 的辅助脚本；P7d 在 P7a 完成后开始。

## 已确定的决策（2026-10-12）

1. **伙伴操作用方案 A**：普通权限的 daemon 加普通权限的桥，不提升 cua-driver 的权限。
2. **屏幕只用 Sunshine**，不做 RDP 备选。
3. **第一版终端只做伙伴的单次 PowerShell 命令**，交互式终端页以后再做。
4. **防火墙由 App 经 SSH 修改**，每次修改前弹窗列出具体变化，用户确认后才执行。
5. **测试机的 cua-driver 已恢复为普通权限**（2026-10-12 17:46，按实验备份的 Limited XML 恢复，恢复前的最高权限配置另存在 `%TEMP%\miffan-windows-spike\`）。

## 测试机当前状态（2026-10-12 实验结束后）

- **Sunshine** v2026.914：服务运行中，加密设置为 2 / 2；配对了测试客户端 `miffan-win-spike`，凭据在主 checkout 的 `.deps/windows-spike/`。
- **防火墙**：Sunshine 的两条宽泛规则已禁用；`Miffan-Sunshine-UDP-LAN` 只放行 UDP 47998–48000，来源为 192.168.31.0/24 和 100.64.0.0/10；`Miffan-SSH-LAN` 只允许局域网访问 TCP 22；“远程桌面”规则组已关闭，RDP 服务开着，但只能经 SSH 隧道访问。
- **Tailscale**：已加入，地址 100.64.0.8。Headscale 新增了 `win-desktop` 主机和一条授权：手机、Mac 可访问它的 UDP 47998–48000，原策略备份为 `policy.hujson.bak-windows-sunshine-*`。
- **cua-driver** 0.34：已加入用户 PATH，`cua-driver-serve` 任务为普通权限、交互式登录，直接运行 `cua-driver.exe serve`。
- **剪贴板**：最后一次写入的是“中文测试”。

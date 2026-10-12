# P7d：Windows 接入脚本（Codex 开发任务）

先读：
- `docs/remote-screen/P7_WINDOWS_PLAN.md` 第 6 节“接入引导”。
- `docs/remote-screen/research/WINDOWS_SPIKE.md` 里用户手动配置 OpenSSH、公钥、防火墙时遇到的问题。
- `app/src/main/assets/remote/miffan.ps1`：Sunshine UDP 规则的来源网段是怎么算的（`100.64.0.0/10`、局域网网段，**不用** `LocalSubnet` 和 `Any`）。接入脚本的 SSH 规则用同样的算法。
- `app/src/main/java/me/ayuilos/miffan/ui/im/computer/ComputerAddressStep.kt`：Mac / Linux 现在怎么授权 App 的公钥（`computerPublicKeyCommand`）。Windows 要一个对应的东西。

## 目标

用户在 App 里添加 Windows 电脑时，App 给出一段 PowerShell 脚本。用户在 Windows 上**以管理员身份**打开 PowerShell，粘贴，回车，运行一次。运行完，手机就能用 App 的密钥通过 SSH 登录这台电脑，脚本最后打印出 App 里要填的信息。

## 脚本要做的事

1. **检查管理员权限**。不是管理员时打印一句清楚的话（“请右键 PowerShell，选‘以管理员身份运行’”的英文），然后停止，不做任何修改。
2. **OpenSSH 服务器**：
   - 没装时用 `Add-WindowsCapability -Online -Name OpenSSH.Server~~~~0.0.1.0` 安装。安装前打印“可能需要几分钟”。
   - 安装失败时打印微软官方 Win32-OpenSSH MSI 的下载页（`https://github.com/PowerShell/Win32-OpenSSH/releases`），说明装好后重新运行本脚本，然后停止。**不要在脚本里自动下载安装包。**
   - `sshd` 设为自动启动并启动。第一次启动会生成主机密钥。
   - **不改** `sshd_config`，不改默认 shell，不关密码登录。这些是主机级设置，会影响用户自己的其他用法。
3. **授权 App 的公钥**（运行脚本的这个账号）：
   - 账号在 Administrators 组里、且 `sshd_config` 里有 `Match Group administrators` 那一段（默认有）时，写到 `%ProgramData%\ssh\administrators_authorized_keys`，ACL 用 SID 设置：只给 `*S-1-5-32-544`（Administrators）和 `*S-1-5-18`（SYSTEM）完全控制，去掉继承。中文系统的组名是本地化的，不能写组名。
   - 否则写到 `%USERPROFILE%\.ssh\authorized_keys`，ACL 只给本人、SYSTEM、Administrators。
   - 幂等：同一个公钥（按密钥类型和 base64 比较，忽略注释）已经在文件里时不再追加。
   - 文件编码：UTF-8 **无 BOM**，行尾保证有换行。文件原本有 BOM 或是 UTF-16 时要查清 sshd 是否还能读，在笔记里说明你的处理。
4. **防火墙**：
   - 读 `sshd_config` 里的 `Port`（默认 22）。
   - 建一条自己的入站规则，名字固定（例如 `Miffan-SSH`），重复运行时更新这条规则而不是新增。TCP、该端口、程序限定为 sshd.exe、网络类型不限（新连的 Wi-Fi 默认是“公用网络”，OpenSSH 自带的规则只对“专用网络”生效）。
   - 来源只限：当前已连接网络的私有 IPv4 网段（RFC1918，按网卡前缀算），以及电脑有 Tailscale 地址（`100.64.0.0/10` 内）时加上 `100.64.0.0/10`。算法和 `miffan.ps1` 的 Sunshine 规则一致；能复用就复用同一段代码的思路，但接入脚本必须能独立运行（用户是粘贴进去的，不依赖 `miffan.ps1`）。
   - 不动用户已有的规则，也不动 OpenSSH 自带的规则。
5. **最后打印连接信息**，格式固定、好读，例如：
   ```
   Miffan: this computer is ready.
     Name:        DESKTOP-QHRKJUQ
     Username:    ekk
     Address:     192.168.31.61   (Wi-Fi)
     Address:     100.64.0.8      (Tailscale)
     Port:        22
     Fingerprint: SHA256:...      (ED25519)
   ```
   - 用户名就是 SSH 登录要填的那个。域账号或微软账号时要确认 SSH 实际接受的写法，必要时打印 `domain\user` 形式，在笔记里写清楚。
   - 主机密钥指纹的格式要和 App 验证步骤显示的一致（`RemoteHostKey.sha256Fingerprint`），用户要拿它和 App 上的对照。

## 形式

- 脚本模板放在 `app/src/main/assets/remote/windows-setup.ps1`，公钥位置用占位符。
- Kotlin：`app/src/main/java/me/ayuilos/miffan/data/repository/WindowsSetupScript.kt`，提供 `render(template, publicKey)` 和一个从 assets 读模板的入口，供 UI 调用。
  - 公钥校验：单行，类型限于 `ssh-ed25519`、`ssh-rsa`、`ecdsa-sha2-nistp256/384/521`，base64 部分只能是 base64 字符。注释部分要么去掉要么安全转义；插入的位置必须是 PowerShell 单引号字符串。校验不过就抛异常，不生成脚本。
- 整段脚本包在 `& { ... }` 里，`$ErrorActionPreference = 'Stop'`，粘贴进 PowerShell 5.1 控制台（PSReadLine）后一次执行完。中途失败时打印出错的那一步，已经做过的步骤保持原样。
- 脚本输出只用英文，不要中文（避免控制台代码页问题）。
- 脚本要短，用户可能会看一眼。别写进度条和花哨输出。

## 测试

- **不要在测试机上真正运行这个脚本**：它会改防火墙和授权文件，按规定这些只能由用户自己做。最后由用户在测试机上真跑一次。
- 可以在测试机上做的：
  - 用 PowerShell 的解析器检查语法（`[System.Management.Automation.Language.Parser]::ParseInput`）。
  - 离线测试：在 `%LOCALAPPDATA%\Miffan\p7d-tests\` 下的临时目录里运行，用同名函数覆盖会改系统的命令（`Add-WindowsCapability`、`Set-Service`、`Start-Service`、`New-NetFirewallRule`、`Set-NetFirewallRule`、`icacls` 等），把 `ProgramData`、`USERPROFILE` 指到临时目录。覆盖：首次运行、重复运行（不重复追加公钥、更新同一条规则）、非管理员、非管理员组账号、OpenSSH 安装失败、自定义端口、有无 Tailscale、已有 BOM 的授权文件。测试文件放在 `tools/windows-helper-tests/Test-WindowsSetup.ps1`。测完删掉临时目录。
  - 只读查看：sshd 状态、`sshd_config`、防火墙规则列表、网卡地址。
- JVM 单元测试：`render` 的公钥校验和转义，模板里的占位符被替换且只出现一次。
- `./gradlew :app:testDebugUnitTest` 通过。

## 测试机

- 连接信息在 `/Users/chenxiansheng/miffan/.deps/windows-spike/p7-test.properties`，其中含测试专用私钥的路径。**不要读取或输出私钥内容。**
- 允许：在 `%LOCALAPPDATA%\Miffan\p7d-tests\` 下读写；只读地查看系统状态。
- 不允许：改防火墙、改 `sshd_config`、改任何 `authorized_keys`、重启 sshd、装卸软件。

## 不做

- 不改 `ui/` 下的 Compose 文件和 ViewModel。UI 由 Claude 做，需要的接口和文案写进笔记。
- 不做 Tailscale 或 Headscale 的配置。

## 交付与沟通

- 写 `docs/remote-screen/P7D_ONBOARD_NOTES.md`：脚本做了什么、安全边界、测试结果、给 UI 的接口、用户真跑时要看的东西、已知限制。
- 在本 worktree 的 `feature/p7d-windows-onboard` 分支提交，不要合并到 `feature/im-4.0`。
- 通知 Claude：先 `~/.local/bin/herdr pane send-text wJ:p1 "消息"`，**再** `~/.local/bin/herdr pane send-keys wJ:p1 enter`。

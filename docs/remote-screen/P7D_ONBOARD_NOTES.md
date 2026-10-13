# P7d Windows 接入脚本交付

2026-10-13，分支 `feature/p7d-windows-onboard`。未修改 Compose / ViewModel，未合并到主开发分支。

## UI 接口

```kotlin
val script = withContext(Dispatchers.IO) {
    WindowsSetupScript.fromAssets(context.assets, selectedKey.publicKey)
}
// 纯函数，方便测试：
WindowsSetupScript.render(template, publicKey)
```

包名 `me.ayuilos.miffan.data.repository`，模板 `remote/windows-setup.ps1`。
非法公钥、缺失/重复/非单引号占位符抛 `IllegalArgumentException`。仅接受规格中的五种类型和 Base64 字符；拒绝 CR、LF、NUL；移除注释，所以引号、反引号、`$()` 不进入脚本。这里验证文本格式，不验证密钥二进制结构，调用者应传入 App 已生成的公钥。

建议引导文案：在要连接的 Windows 电脑上，以管理员身份打开 PowerShell，粘贴整段脚本并回车。安装 OpenSSH 可能需要几分钟。将脚本最后显示的 Username、Address、Port 填回 App，并按 App 显示的主机密钥类型核对对应 Fingerprint。

必须提示账号边界：脚本授权的是**运行管理员 PowerShell 的账号**。普通用户在 UAC 中输入另一个管理员的凭据后，授权的是那个管理员，不是原桌面用户。普通账号路径逻辑已模拟覆盖；脚本不代替管理员为其他账号开户/授权。

## 行为与安全边界

- 无管理员权限立即返回，尚未执行安装、文件写入或服务操作。
- `sshd` 服务不存在才安装 Windows capability；失败打印实际原因和微软 Win32-OpenSSH releases 地址，用户自行装 MSI 后重跑。已有 MSI 服务直接复用；从服务 PathName 获取 `sshd.exe`，支持带空格路径。
- 将服务设为 Automatic 并 Start-Service，不 Restart-Service；不编辑 `sshd_config`、默认 shell、密码登录设置。
- 支持默认配置与单个自定义 Port。默认管理员 Match 指向共享授权文件时使用该路径，否则使用当前 USERPROFILE 的 `.ssh/authorized_keys`。
- 新建受保护 DACL，清除继承和旧显式 ACE；管理员文件仅 Administrators / SYSTEM 完全控制，普通文件另加本人。直接构造 SID 对象，不依赖本地化组名；.NET 的 `S-1-5-32-544` / `S-1-5-18` 等价于 icacls 参数中的 `*S-1-5-32-544` / `*S-1-5-18`。
- 保留已有密钥和注释；按类型/Base64 去重，也识别带 authorized_keys 选项的已有密钥，避免重新追加一条不带限制的同公钥。拒绝直接 key 文件或 `.ssh` 目录为 reparse point。
- 只创建/更新 PersistentStore 中固定名 `Miffan-SSH`：TCP、读取的端口、实际 sshd.exe、Profile Any。其他规则不动。沿用 Sunshine 的 Up 网卡、IPv4 默认网关、RFC1918、实际前缀计算；仅发现在线 Tailscale 网卡的 CGNAT 地址时加入 `100.64.0.0/10`。另外拒绝跨出 RFC1918 的过宽前缀；无来源停止，不退回 Any/LocalSubnet。
- 失败保留已完成步骤，打印步骤、实际 Exception.Message 与重跑提示。自有提示都是英文；按 Claude 审阅要求保留系统异常原文，系统本地化异常可能不是英文。
- 管理员默认授权文件是全体管理员共享的，密钥可用于管理员组账号，这是 Windows 默认配置的边界，并非每用户隔离。脚本不改这一主机级策略。

## 编码处理

[Win32-OpenSSH 的 UTF-16 问题记录](https://github.com/PowerShell/Win32-OpenSSH/issues/2070)确认 PowerShell 5.1 默认重定向生成的 UTF-16 LE 授权文件可能导致认证失败。
[公钥安装说明](https://github.com/PowerShell/Win32-OpenSSH/wiki/Setup-public-key-based-authentication-for-windows)要求 UTF-8。
[授权行解析源码](https://github.com/PowerShell/openssh-portable/blob/latestw_all/auth2-pubkeyfile.c)没有在授权行解析层剥离 UTF-8 BOM 的逻辑；不能把含 BOM 的首行当作跨版本可靠格式。

`ReadAllText` 自动识别 UTF-8 BOM、UTF-16 LE/BE BOM，再用 `UTF8Encoding(false)` 写回，保留文本并确保末尾换行。无 BOM 输入使用严格 UTF-8 解码；含 NUL 的未知编码停止，避免把无 BOM UTF-16 当普通文本损坏后写回。没有在真实授权文件上验证认证失败；隔离测试验证了三种 BOM 的转换与去重，不依赖 sshd 对 BOM 的兼容行为。

## 用户名与主机密钥

[Microsoft 配置文档](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh-server-configuration)说明联网微软账号解析成本地账号名，AD 域账号使用 `domain_short_name\user_name`，Entra 身份不支持。
[Win32-OpenSSH 账号解析实现](https://github.com/PowerShell/openssh-portable/blob/latestw_all/contrib/win32/win32compat/pwd.c)通过 SID 反查 SAM 名称，将本机域去掉，其他域保留，再转小写。脚本使用 WindowsIdentity.Name 做相同规范化，不从目录名或显示名猜用户名，也不要求用户填微软邮箱。测试机只读结果为 `DESKTOP-QHRKJUQ\Ekk`，实际连接使用 `ekk`。AD/微软账号没有可用的真机账号做登录验收；域名保留逻辑有离线测试。

**不能保证 App 验证页一定显示 ED25519。** `NativeSshWorkspaceTransport.discoverHostKey` 未设置 `server_host_key`，使用 JSch 2.28.7 的协商结果；`FingerprintHostKeyRepository` 的 `HostKey.type` 是 UI 的算法来源。读取本地依赖配置确认默认先列证书算法，普通密钥顺序为 ED25519、ECDSA P-256/P-384/P-521、RSA SHA-512/SHA-256。Android 运行时算法支持及服务端配置均可能使协商选择 ECDSA/RSA。

因此最终脚本输出各个可用默认主机公钥（ED25519/ECDSA/RSA）的指纹；若配置显式 HostKey，则输出指定文件对应的 `.pub`。标签使用公钥类型 `ssh-ed25519`、`ecdsa-sha2-nistp256`、`ssh-rsa`，与 App 的 `RemoteHostKey.algorithm` 对应。RSA SHA2 是签名算法，公钥类型仍为 ssh-rsa。SHA256 使用 `ssh-keygen -lf <public file> -E sha256`，无 padding 的 Base64，与 App 对 SSH 公钥 blob 做 SHA-256 的格式相同。只读取 `.pub`，不读取主机私钥。不要拿 ECDSA 的指纹去比较 ED25519 那一行。

测试机 ED25519 公开指纹只读核验：`SHA256:yy3faXP7edMJa4kPE+XEXeXjKMFBxWb66r+qT6Oh4MY`。这不是对手机 JSch 实际选择类型的真机验收。

## 测试

- `ANDROID_HOME=/Users/chenxiansheng/Library/Android/sdk ./gradlew :app:testDebugUnitTest`：通过，938 tests，0 failures/errors/skipped。最终另以 `:app:testDebugUnitTest --rerun` 强制重跑测试，避免外部模板读取被增量检查跳过。
- Windows 11 的 PowerShell `5.1.26100.9444`：Parser::ParseInput 通过；`tools/windows-helper-tests/Test-WindowsSetup.ps1` 隔离测试通过。
- 覆盖首次安装、重复运行不重复追加且更新原规则、非管理员零修改、普通账号三主体 ACL、管理员两主体 ACL、无管理员 Match、自定义端口、有无 Tailscale、断开网卡排除、真实 /23 网络计算、空/过宽来源失败、安装失败 MSI 回退、UTF-8 BOM/UTF-16 LE/BE 转换、已有密钥选项保留、注释掉的密钥不算授权、域用户名、多主机公钥与自定义 ECDSA HostKey、防火墙失败输出原因并保留授权步骤、Include 配置停止。
- Harness 用同名函数覆盖安装、服务、网络、Firewall、Set-Acl；身份函数经 AST 精确替换，原生 keygen 输出替换为测试值，其余模板流程真实执行。实际文件仅在 `%LOCALAPPDATA%\Miffan\p7d-tests\run-<guid>` 中，ProgramData/USERPROFILE 指向该目录；finally 恢复变量并删除目录。传输暂存目录同样已删除。
- 本次只读查询了 sshd 状态/服务路径、有关配置行、当前身份、PS 版本、公开主机指纹。未运行真实接入脚本；未改测试机防火墙、授权文件或 sshd 配置，未重启服务、装卸软件。

复测时把模板和测试脚本暂存到获准的 `p7d-tests` 子目录，再运行：

```powershell
& .\Test-WindowsSetup.ps1 -TemplatePath .\windows-setup.ps1
```

## 用户实跑与已知限制

最终真实粘贴及 App 登录由用户验收：确认账号、连接同一 LAN 或既有 Tailscale、管理员粘贴一次、看到 ready 后填写参数、按类型对照指纹，再重复运行确认无重复公钥。安装 capability 在某些系统仍可能很慢；没有自动下载安装包，也不擅自中断系统安装。

自定义 Include、复杂 Match、非标准 AuthorizedKeysFile、多个/非法端口、带参数的 sshd 服务路径、HostCertificate 配置会停止并提示原因，需人工接入；不修改已有高级 SSH 配置。没有开放 IPv6、公网、无默认网关的纯 LAN；换网段后需用户重跑以更新自己的规则。现有其它防火墙宽泛 Allow/Block 或企业 GPO 不在脚本控制范围。已运行的 sshd 若配置文件被其他人改动但未重启，磁盘配置可能不同于实际监听配置，本脚本不替用户重启来同步。现有密钥的 restrict/command/from 等限制保留，可能仍限制 App 功能。

一次管理员粘贴不能替普通用户跨账号授权；仅提供该账号/默认配置路径，不处理 Entra。PSReadLine 真实交互粘贴、真实 ACL 生效、不同 Windows 版本安装及手机最终登录仍待用户验收。

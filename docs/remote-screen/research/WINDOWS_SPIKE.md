# Windows 支持验证（第二轮暂停记录，未完成验收）

日期：2026-10-12。分支：`research/windows-spike`。
依据：[WINDOWS_SPIKE_SPEC.md](../WINDOWS_SPIKE_SPEC.md)。

## 结论与建议

**本次验证尚未完成。第一轮防火墙问题已由用户处理并授权恢复；第二轮 Mac probe 和指定模拟器均在 UDP control 阶段失败，已再次通知 Claude `wJ:p1` 安排 Tailscale，停止远端实验。** 尚未验收 Windows 实际视频画面、伙伴动作或中文粘贴。

建议架构仍采用 Sunshine 采集真实控制台桌面，TCP 管理/配对/RTSP 经 SSH，
媒体及控制 UDP 直连 LAN/VPN；伙伴操作通过 SSH stdio MCP 连接 Session 1 中的
cua-driver 命名管道。这是待实测的方向，协议依据来自 [P6a](../P6A_NOTES.md)、
[P6b](../P6B_NOTES.md)。管理、配对、RTSP 与严格加密协商已验证；UDP 未通过。
高权限 SSH 直接连接普通用户 cua daemon 的管道也未通过，不能只加平台枚举就宣布支持。

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
因此没有可引用的 MSI 成功退出码；第二轮已由文件 VersionInfo 验证 FileVersion 和
ProductVersion 均为 `2026.914.233613`，服务与文件均已实际确认。
防火墙实际程序路径为 `C:\Program Files\Sunshine\sunshine.exe`。

[稳定版 WiX 安装器](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/cmake/packaging/wix_resources/sunshine-installer.wxs)
在静默模式运行 `sunshine-setup.ps1 -Action install -Silent`；
[安装脚本](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/src_assets/windows/misc/sunshine-setup.ps1)
包含 PATH、配置迁移、防火墙、服务、自启动步骤。没有自己重复执行这些脚本。

第二轮实际只读输出：

```text
SunshineService: Running / Auto / LocalSystem
  C:\Program Files\Sunshine\tools\sunshinesvc.exe, PID 3324, Session 0
sunshine.exe: PID 12136, Session 1, command "Sunshine.exe"
config: C:\Program Files\Sunshine\config\sunshine.conf
public cert: config\credentials\cacert.pem
private key location（仅枚举文件名，未读取）: config\credentials\cakey.pem
DER SHA-256: 8edb025280591442b733c675de6dafb1b011b0f69abddd1f18ed178b14bf512d
TCP Listen 0.0.0.0: 47984 / 47989 / 47990 / 48010
```

[精确安装版 service 源码](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/tools/sunshinesvc.cpp#L98)
复制服务自身的 LocalSystem token，把 TokenSessionId 改为活动 console Session，
再 CreateProcessAsUserW 启动 Sunshine。因此 Session 1 不等于进程是普通用户权限。
没有 console session（0xFFFFFFFF）时，服务每 3 秒等待，不自行创建登录会话。

锁屏/UAC/未登录仅作源码调研：服务有 LocalSystem 权限，
[misc.cpp](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/src/platform/windows/misc.cpp#L223)
用 OpenInputDesktop/SetThreadDesktop 同步桌面。
[Microsoft DuplicateOutput](https://learn.microsoft.com/en-us/windows/win32/api/dxgi1_2/nf-dxgi1_2-idxgioutput1-duplicateoutput)
说明安全桌面采集需要 LOCAL_SYSTEM。**推断**：安装器的服务模型具备采集安全/登录桌面的权限路径，
但不能据此承诺每种锁屏/UAC 切换都无黑屏且能恢复；没有主动锁屏、弹 UAC、注销或重启。
SSH 管理下真实视频因 UDP 失败未验收。管理员/配对状态实际落地文件仍需恢复后核实，
没有导出该文件内容；默认状态/凭据文件名的源码调研不能冒充实际路径验收。

### A2 编码

基线命令 `Get-CimInstance Win32_VideoController` 输出：

```text
Name = NVIDIA GeForce RTX 3060
DriverVersion = 32.0.16.1088
VideoModeDescription = 2560 x 1440 x 4294967296 colors
```

第二轮实际 Sunshine 日志：

```text
Desktop resolution [2560x1440], Display refresh rate [144Hz]
Found H.264 encoder: h264_nvenc [nvenc]
Found HEVC encoder: hevc_nvenc [nvenc]
NvEnc: created encoder v1301 H.264 P1 async two-pass rfi
NvEnc: created encoder v1301 HEVC P1 async two-pass rfi
```

NVENC H.264/HEVC 初始化已经确认；AV1 探测报 GPU 不支持，之后成功找到上述编码器。
**没有有效媒体帧，不能把初始化或请求 HEVC 当作实际视频编码/帧率验收**。

### A3 现有栈连通

管理、配对、RTSP 与严格加密协商已通过；**Mac 与指定模拟器的 LAN UDP 均失败**。
现有严格策略仍为 `ENCFLG_ALL` + `requireEncryptedStreams`。

备份 `sunshine.conf.bak-windows-spike-20261012-144022` 后设置 LAN/WAN 2/2，确认无 UDP
活动才重启 SunshineService，未重启 Windows。随机测试管理员密码只放主 checkout 的
`.deps/windows-spike/admin.json`，目录 0700、文件 0600，已实际检查；PIN 只在内存，
身份在同目录 `probe-identity/`，不进 git/命令参数。新装主机原先没有管理员/配对状态文件，
初始目录枚举是该不存在状态的证据；未备份或复制主机私钥。

独立客户端 `miffan-win-spike` 经管理 API 定向批准，完成签名/challenge/证书验证。
精确版本 [confighttp.cpp](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/src/confighttp.cpp#L1827)
要求 pairing_id，不能沿用只有 pin/name 的旧接口。只从 pending 列表选择本测试请求，
没有批准其它客户端，也未调用 cancel/unpair-all。

SSH 只在 Mac loopback 转发：57984→47984、57989→47989、57990→47990、58010→48010。
另临时反向转发 Windows loopback:18889 到 Mac 现有下载代理，官方 cua installer
只在进程内使用；没有改 Windows 系统/用户代理，实验暂停后该 SSH 转发已关闭。

```sh
tools/p6a-probe/build/p6a-probe stream \
  --http-host 127.0.0.1 --http-port 57989 --https-port 57984 \
  --keydir /Users/chenxiansheng/miffan/.deps/windows-spike/probe-identity \
  --udp-host 192.168.31.61 --rtsp-tcp 127.0.0.1:58010 \
  --app Desktop --seconds 20 --width 1920 --height 1080 --fps 60 \
  --bitrate 15000 --codec hevc --strict --json /private/tmp/windows-spike-hevc.json
```

Mac：RTSP stage 4 成功；stage 8 control establishment 从约 4138 ms 到 12216 ms，
error=35，startup result=1；frames=0、audio_packets=0。加密协商
`supported=requested=enabled=0x7`，video/audio/control/control_v2 均 true。
这证明严格协商通过，**没有媒体帧，不是成功的加密视频收流**。

模拟器：确认 emulator-5560 / RikkaHub_API_35_16K，复用已安装的 stream APK
`audioAndTrafficAcceptance`（只收媒体，不发主机输入）。它固定使用 Linux IP 和默认
TCP 端口，因此实验仅在模拟器中对这几个 fixture 目的地址作 NAT 映射到本 Windows
与 SSH 转发，并暂时导入同一已配对 probe 身份，不增加第二个测试客户端。
结果 `Failed(reason=UDP_UNREACHABLE, stage=8, code=11)`，约 13.995 秒，收到帧数 0。
没有进入视频解码/音频验收；仅凭失败阶段不能证明唯一原因就是 macOS 隐私限制。

原模拟器 prefs 备份在授权目录 `emulator-p6b-prefs-before.xml`（0600）。结束后读回确认
逐字节一致，adbd 恢复普通 shell；iptables 只有计数/时间戳变化，规则结构与基线一致。
未操作 emulator-5554，未修改 App 或 stream 测试源码。AVD 原先停止，实验按指定名称
启动在 5560，使用 no-window/no-snapshot-save；暂停时没有关闭它以便续测。
两端失败后依规格停下并通知 Claude 请求 Tailscale，未自行安装或开放 VPN UDP。

### A4 防火墙（第一轮暂停、第二轮已核实收紧）

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

用户处理后的第二轮只读核实：上述 TCP/UDP 宽泛规则均 Enabled=False；
`Miffan-Sunshine-UDP-LAN` 为 Enabled=True / Allow / UDP / 47998-48000，
RemoteAddress=`192.168.31.0/255.255.255.0`，Program 仍限 sunshine.exe。
用户/Claude 另从 Mac 验证 LAN 到 47984/47989/47990/48010/3389 全部不可达；此项是
用户交付证据，不冒充 Codex 自己执行。Codex 没有修改任何 Windows 防火墙规则。

**正式方案必须检测 Sunshine 安装/升级可能新增或重新启用的宽泛规则并提示用户。**
厂商 add-firewall-rule.bat 添加宽泛 Allow，不能只在首次接入时检查，也不能只记住当前
Disabled 的 GUID。helper 应按 sunshine.exe 路径、有效入站 Allow、协议/端口/来源/
网络配置文件检查叠加规则。发现 TCP 对外或 UDP 来源/端口过宽时停止高性能模式并提示，
由用户处理；不默默改规则。将来 Tailscale 的 UDP 来源范围也由用户/Claude决定。

### A5 输入及中文

**未测试** VK 按键、绝对坐标、DPI、多屏、中文粘贴。没有启动记事本。
用户会话剪贴板桥的候选是 `cua-driver clipboard_write`，但必须先核实实际安装版 schema、
命名管道权限、Session 1 写入以及 Ctrl+V 在实验窗口内的结果。
也不能以 SSH `Set-Clipboard` 成功替代用户桌面剪贴板验收。

### A6 PowerShell helper 能力

以下为命令设计；服务/版本/端口/DER 指纹与加密备份已由第二轮脚本实测，
完整 helper、wake 和故障/竞态处理**尚未验收**：

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

### B1 安装与自启动（已核实）

使用未修改的官方 `https://cua.ai/driver/install.ps1` 和 `_install-common.psm1`，
显式 `-Release 0.34.0 -NoAutoStart` 按用户安装（官方 installer baked stable=0.34.0）。
官方流程校验 checksum 和 Windows Authenticode。先保存用户 PATH 到
`%TEMP%\miffan-windows-spike\user-path-before.txt` 并记录任务不存在。

```text
cua-driver 0.34.0, git b0968e1b1283
binary SHA-256 77f5cac754b42b6a8bae126414fc8f7487432ace93466967965188e24e9e53fb
visible C:\Users\Ekk\AppData\Local\Programs\Cua\cua-driver\bin\cua-driver.exe
real C:\Users\Ekk\.cua-driver\packages\releases\0.34.0-x86_64-pc-windows-msvc\cua-driver.exe
task cua-driver-serve: Running / User Ekk / LogonType Interactive / RunLevel Limited
daemon PID 18552: Session 1, arguments serve
```

新版目录从旧 `Programs\trycua\cua-driver-rs` 改为 `Programs\Cua\cua-driver`。
官方 installer 的 autostart 当前默认 **Highest**，与旧 guide 的普通用户描述有差异。
本实验不注册该默认任务，再以实际用户 SID 注册 Limited/Interactive，不把 daemon 提权。
SSH 的 USERDOMAIN=WORKGROUP，拼 WORKGROUP\ekk 注册失败；用 WindowsIdentity 的
SID 成功。安装器内部 schtasks 查询“任务不存在”留下 LASTEXITCODE=1，不能把它当作
整个 .ps1 安装失败；已按实际二进制与任务结果核实，未重复安装。
产品遥测默认 enabled，未更改用户选项。

### B2 SSH MCP 与 schema（部分通过）

高权限 SSH 中执行 `cua-driver mcp --socket \\.\pipe\cua-driver`，两次初始化前失败：
`daemon transport error ... 管道的另一端上无任何进程 (os error 233)`。
SSH 和任务同账号；本机 **high-IL 客户端→medium daemon 的直连未通过**。
SSH CLI doctor 的 Session 0 提示属于客户端本身，不是 daemon 的 Session。

[0.34.0 serve.rs](https://github.com/trycua/cua/blob/cua-driver-rs-v0.34.0/libs/cua-driver/rust/crates/cua-driver/src/serve.rs#L1541)
以 GetNamedPipeClientProcessId→OpenProcess→OpenProcessToken(TOKEN_QUERY) 查客户端 SID；
读取失败返回 None 并断开。**推断**：medium daemon 查询 high-IL 客户端 token 失败是
候选原因，尚未记录该 API 的具体失败码，不能仅凭同 SID 保证可用。
[上游 #3318](https://github.com/trycua/cua/pull/3318) 的成功同 SID 回放使用 elevated
interactive daemon，不能替代本次 medium daemon 验收。

对照创建仅本实验的临时 Limited/Interactive MCP 客户端任务，以文件桥运输 stdio。
Session 1 普通客户端→普通 daemon **initialize 和 tools/list 成功**，返回 cua-driver
0.34.0、protocolVersion=2025-06-18、**59 个工具**。临时任务
`Miffan-Windows-Spike-MCP` 已移除，保留 `cua-driver-serve`。

App 精选的 **23 个工具名全部存在**，没有名字缺失；完整 schema 在本地 result-3.json。
当前 ComputerTools.kt 的精选集合没有 clipboard_read/write，需由 Windows 剪贴板桥接入。

| 工具 | Windows 实际参数/必填 |
| --- | --- |
| list_apps / list_windows | session；后者可 pid/on_screen_only，无必填 |
| get_window_state | 必填 pid/window_id；支持 max_image_dimension、query、max_elements、session |
| launch_app | name/aumid/path/launch_path/bundle_id/urls、creates_new_application_instance；无必填 |
| click | element_token 或 x/y，pid/window_id、capture_id、delivery_mode、session |
| type_text | 必填 text；可 element_token、pid/window_id、delivery_mode、session |
| hotkey / press_key | 必填 keys / key；可窗口目标与 delivery_mode |
| clipboard_write / clipboard_read | text/file_path/image_path / include_text，均可 session，无必填 |

Windows 特有 debug_window_info、aumid/launch_path/start_minimized；Mac 标识不能照搬。
App 动态取远端 schema、隐藏 session/输出文件参数及限制截图尺寸的机制可复用，
但工具名存在不证明所有动作可成功。

### B3–B5 未完成的验收

普通客户端桥的 list_apps 响应经子进程 stdout 默认 CP936 解码后损坏 JSON，未能成功
枚举；list_windows 尚未执行到。ASCII 的初始化/tools/list 正常而中文输出坏，且复用
StreamReader 的默认编码，故不能把该结果归为 cua-driver 自身 JSON 序列化缺陷。
显式 UTF-8 子进程 stdout 和 stdin StreamWriter 的修正已保存，**尚未远端复验**。

没有启动记事本、截图、点击、键入、剪贴板或 Ctrl+V，因此 A5、B3、B4 仍待验收。
没有操作用户窗口或发送任何 Sunshine 输入。锁屏只查文档/源码、不实测；普通用户
不具有 Sunshine LocalSystem 的安全桌面权限，不能承诺操作锁屏/UAC。安装版具体
结构化失败与恢复还需续查，不能用 Session 0 的空窗口行为代替锁屏结论。

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

**第二轮已验证可行的中文 stdin 方案**：EncodedCommand 只放固定引导代码，
同时设置 InputEncoding/OutputEncoding，参数/数据走 UTF-8 stdin：

```powershell
$utf8=New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding=$utf8
[Console]::OutputEncoding=$utf8
$OutputEncoding=$utf8
$value=[Console]::In.ReadToEnd()
Write-Output ('stdin:'+$value)
exit 9
```

Mac 发送 UTF-8 `中文测试\n`，得到 `stdin:中文测试\n\r\n`，SSH exit=9，
hex=`737464696e3ae4b8ade69687e6b58be8af950a0d0a`。不用改变 cmd 的全局代码页。
直接 `-Command -` 仍可能在引导行生效前以 CP936 解码中文；推荐固定 EncodedCommand
+ stdin 数据。若确实传脚本，可先用固定引导显式解码，再执行可信脚本，但该执行形式
尚未单独验收退出码/多行语义。子进程协议还要单独设 StandardOutputEncoding/UTF-8
StreamWriter，不能假定 Console 编码覆盖 ProcessStartInfo 重定向流。
没有修改 OpenSSH 默认 shell；注册表查询没有给出 DefaultShell 值，实测命令行为与
默认 cmd 一致。Git Bash 检查 `C:\Program Files\Git\bin\bash.exe=True`。
[Microsoft OpenSSH 配置文档](https://learn.microsoft.com/en-us/windows-server/administration/openssh/openssh-server-configuration#configuring-the-default-shell-for-openssh-in-windows)
允许 HKLM\SOFTWARE\OpenSSH\DefaultShell 指定完整 shell 路径。修改影响整个 SSH
server 的 exec/PTY 行为，是主机级决策，不是 App 可擅自执行的兼容修复。
Git Bash 可作为显式工具链，但路径/引号、退出码、stdin、缺失命令仍要验证；
它不消除 Session 0/用户桌面隔离。WSL status 仅证明默认版本 2，未验证发行版。

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
| `ui/im/computer/ComputerAddressStep.kt:21` | mkdir/printf/authorized_keys/chmod | Windows 密钥引导，管理员账号路径规则须单独验证 |

文件/目录操作主要已经使用 SFTP，不宜统一改成 shell；应检查路径根、junction/reparse
point、大小写和 ACL 等 Windows 语义。RemoteComputerRegistry.kt:210–226 动态发现
resources 并只预加载 SKILL.md，其余按需读，Windows guide 不应写死成 Linux。
Shell/terminal 工具最终落到 workspace 传输/持久协议，不是仅改变展示的 bash 标签。
没有改 App 代码。

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

| 实际改动 | 备份/证据 | 撤销方法（未执行，临时桥除外） |
| --- | --- | --- |
| 创建 `%TEMP%\miffan-windows-spike`，导出安装前全量规则/port filters | firewall-before.clixml / firewall-ports-before.clixml | 实验结束后精确删除临时目录，导出不改变防火墙 |
| 安装 Sunshine MSI；厂商添加宽泛规则、PATH/服务/自启动 | 第一轮规则 JSON、第二轮版本/服务输出 | 用户决定保留或卸载；卸载也会删规则，不自行执行 |
| 用户禁用宽泛规则、添加 UDP LAN 规则 | 第二轮只读输出，用户/Claude 交付 TCP 不可达结果 | 由用户管理，Codex 不修改/撤销 |
| Sunshine LAN/WAN 改为 2/2、重启其服务 | sunshine.conf.bak-windows-spike-20261012-144022 | 无活动流后恢复该备份，再重启 SunshineService |
| 创建测试管理员并配对 miffan-win-spike | 初始状态不存在证据、授权目录 admin.json/probe-identity | 只撤销该测试客户端；新装主机状态无其他新增数据时才能按初始不存在状态恢复，不能删其他客户端/管理员配置 |
| 按用户安装 cua 0.34、添加 User PATH、注册 Limited/Interactive cua-driver-serve | user-path-before.txt、cua-task-before.txt=absent、版本/任务输出 | 用户决定保留；卸载/移除任务前检查无其他使用者，只移除本次 PATH 项，别覆盖后来的 PATH 改动 |
| 临时普通用户 MCP 桥任务与文件 | Miffan-Windows-Spike-MCP、mcp-bridge/ | 已用 cleanup 脚本正常结束并移除任务，未关闭 daemon；文件暂留作证据 |
| 临时下载代理与 TCP 转发 | 仅 loopback SSH forwarding | 已关闭唯一明确匹配本实验的 SSH 进程，未改系统代理/规则 |

未重启/注销/锁屏 Windows，未修改 OpenSSH/RDP 配置，未启动记事本或操作用户窗口。
没有安装 Tailscale。现存 cua-driver-serve 是本实验注册的普通用户任务；不是系统服务。

本地安装包 `/private/tmp/windows-spike-sunshine-proxy.msi`；测试密码/身份只放主 checkout
已忽略的 `.deps/windows-spike`，权限实际确认 0700/0600。模拟器原测试身份备份也在此
授权目录。官方 cua 安装包通过原脚本临时目录校验安装，脚本自动清理下载 staging。

本机 probe 用公开离线 embedded.tar.gz 解压到 `/private/tmp/windows-spike-probe-deps`
补全依赖，`make -j4 DEPS_DIR=...` 编译通过；没有修改共享源码原件。
不记录密码、PIN、客户端/主机私钥或 launch rikey。管理员/配对状态仍在主机使用，
完整撤销前必须核对是否有用户之后添加的配置。

## 风险清单

1. 安装/升级可能重新添加或启用宽泛 Allow；helper 必须重新检查、提示并拒绝不安全配置。
2. LAN UDP 在 Mac 和模拟器均失败；需要 Tailscale 对照，不能把 VPN 之外的失败唯一归因于隐私限制。
3. high-IL SSH→medium daemon 管道失败；同 SID 仍不够，正式方案要验证令牌边界与 transport。
4. 中文 stdin 的显式 UTF-8 方案已通过；子进程协议另需指定编码，CP936 会损坏包含中文的 JSON。
5. 枚举/动作、剪贴板、DPI、多屏、锁屏恢复未验收；不能从编码器存在或工具名存在推断可用。
6. Sunshine 音频仍是协议的 AES-CBC；三路协商加密不等于全部具有 AES-GCM 认证。
7. 新装状态/用户任务实验应有精确撤销，不能通过恢复整份 firewall/PATH 来覆盖用户后来的更改。

## 需要用户决定的问题与下一轮

- Claude 已收到两端 UDP 失败结果，请安排用户安装 Tailscale及其 UDP 来源规则，再通知恢复。
- 是否接受正式方案采用官方 Highest 用户 daemon，或坚持 medium daemon 并增加安全的用户会话
  客户端桥/改进上游 token 身份验证；本轮没有擅自提权。
- 中文粘贴/DPI/多屏与前台键码验证需限定本实验新窗口并协调用户正在使用桌面的情况。
- 实验结束时是否保留 Sunshine、测试管理员/配对身份和 cua-driver 用户任务。

第二轮下一步：先重新只读核验规则/证书/无流状态，备份当前测试状态；验证 Tailscale收流，
修正并复验普通用户 MCP 桥的 UTF-8，然后新建记事本逐步测试并无保存关闭。
仍未达到最终验收；遇到桌面许可点击、宽泛 TCP 或网络停机条件继续通过 herdr 告知 Claude。

第二轮证据：

- `/private/tmp/windows-spike-resume-status.txt`、`windows-spike-encryption.txt`
- `/private/tmp/windows-spike-command-probe-resume.jsonl`、`windows-spike-hevc.json`
- `/private/tmp/windows-spike-cua-diagnostics.txt`、`windows-spike-cua-task.txt`
- `/private/tmp/windows-spike-cua-bridge/result-1.json`（initialize）和 `result-3.json`（59 个 schema）
- `/private/tmp/windows-spike-cua-bridge/raw-out-4.json`（CP936 子进程解码损坏的 list_apps）
- `/private/tmp/windows-spike-emulator/instrumentation.txt`、`stats.txt`、iptables-before/after.txt

已成功 `herdr pane send-text wJ:p1` 发送 UDP 停机原因、Tailscale需求、进度和未执行项。
这是继续实验的检查点，不是最终完成通知。

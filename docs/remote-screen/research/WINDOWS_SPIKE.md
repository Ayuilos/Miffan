# Windows 支持验证（实测结论与收尾交付）

日期：2026-10-12。分支：`research/windows-spike`。
依据：[WINDOWS_SPIKE_SPEC.md](../WINDOWS_SPIKE_SPEC.md)。

## 结论与建议

**Windows 的核心路径已实测成立：Sunshine Session 1 真实桌面 → SSH TCP + Tailscale UDP 严格加密；cua-driver Session 1 daemon → SSH stdio MCP。H.264/HEVC 均实际使用 RTX 3060 的 NVENC，持续打字约 60 fps；中英文输入、截图、点击、用户会话剪贴板和中文粘贴已通过。**

第四轮经用户激活窗口后，以 Session 1 无窗口观察器只读确认前台 HWND=788388，再通过 Sunshine 原生控制流验证绝对坐标、Ctrl+V、Ctrl+End、Left/Right、F5，文本和截图均读回。实验窗口已选择“不保存”关闭，临时观察器/MCP/转发均清理。**本机授权范围的 spike 已完成，结果足以进入正式 Windows 支持方案；不是跨机型完整兼容认证。** 当前仅单屏/96 DPI，其它缩放/多屏未实测；Win 键按只操作记事本的限制不发给共享 shell，Alt/Escape 仅确认排队返回而未独立观察效果。锁屏/UAC/未登录只读调研；音频两次自然收流收到 244/246 个加密压缩包，但未播放、未验收解码/音频来源及持续性。

建议采用 Sunshine 采集真实控制台，TCP 管理/配对/RTSP 经 SSH，媒体及控制 UDP 直连 LAN/VPN；伙伴操作通过 SSH stdio MCP 连接用户交互会话中的 cua-driver 命名管道。当前 Mac 的 ad-hoc probe 被本地网络隐私拦截，Tailscale 已由用户安装并配置，正式手机 LAN 能力仍需 App 适配后验收。

高权限 SSH → 普通权限 daemon 的直连失败；两种可行路径均已有对照证据：普通权限客户端桥，或用户授权的 Highest/Interactive daemon（本机还需要隐藏的启动包装）。推荐正式设计优先评估普通权限桥，保留权限边界；采用 Highest 前应明确其作为同 SID 权限代理的风险，不能只因管道通了就默认提升整个桌面运行时。

命令层需要显式 Windows 适配，不能直接执行当前 POSIX helper。SFTP 路径与
PowerShell 本地路径应分别处理。SSH 中的进程实际在 Session 0，不能把在那里
启动的 GUI/剪贴板进程等同于用户 Session 1 的桌面。

初步工作量（单人工作日，核心实验通过，跨机型/缩放验收仍有较大误差）：

| 部分 | 估计 | 主要内容 |
| --- | --- | --- |
| Windows 命令与路径适配 | 3–5 天 | 平台探测、UTF-8 stdin/stdout、退出码、SFTP 路径、工作目录 |
| PowerShell helper / 接入引导 | 3–5 天 | 安装、服务/任务探测、备份、安全加密配置、权限/防火墙提示 |
| Sunshine 接入 | 2–4 天 | 复用 stream，Windows 路由、编码、DPI/多屏、恢复验证 |
| cua-driver / 剪贴板桥 | 2–4 天 | 交互任务、管道权限、schema、用户会话中文粘贴 |
| 端到端验收 | 3–5 天 | LAN/VPN、控制权、锁屏诊断、不同机器/缩放 |

合计约 13–23 天；这是粗估，供正式方案分解，不是开发承诺。

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
第三轮已收到有效 H.264/HEVC 编码帧，SSH 管理路径能够采集已登录的真实控制台。probe 只统计、不解码显示；独立记事本截图另由 cua 验收。只读文件元数据确认状态落在 `config\sunshine_state.json`（1526 字节），应用清单为 `config\apps.json`；没有导出状态文件内容或主机私钥。

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
第三轮的实际流日志进一步确认，不再仅依据启动时编码器探测：

```text
15:39:34.864 Creating encoder [hevc_nvenc]（Claude HEVC 会话）
15:48:03.054 Creating encoder [h264_nvenc]（Codex H.264 会话）
15:48:03.104 NvEnc: created encoder v1301 H.264 P1 async two-pass rfi
15:48:41.743 Creating encoder [hevc_nvenc]（Codex HEVC 会话）
15:48:41.789 NvEnc: created encoder v1301 HEVC P1 async two-pass rfi
```

客户端 setup 分别返回 format=1（H.264）/256（HEVC），并收到完整帧，见 A3。

### A3 现有栈连通

管理、配对、RTSP 与严格加密协商已通过。**LAN UDP 在 Mac/模拟器失败，Tailscale UDP 已通过**；以下保留失败过程及后续对照。
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

第三轮恢复前，用户转交 Claude 的 pktmon 结果：Windows 网卡能收到 Mac `/usr/bin/nc`
发往 192.168.31.61:47999 的 UDP；p6a-probe 串流期间没有任何来自 Mac 的 UDP 到达网卡，
抓包工具已清理。Claude 定位为 macOS 本地网络隐私权限阻挡 ad-hoc probe（模拟器同理），
Windows 防火墙/Sunshine 无异常。这是 Claude 提供的抓包证据，Codex 没有冒充自己抓包。
Tailscale 地址通知前没有继续 UDP 实验，先完成 B 的权限与桌面验证。


第三轮用户通知：Windows Tailscale=`100.64.0.8`；Headscale grant 仅允许手机/Mac → win-desktop `udp:47998-48000`，由用户授权并执行，备份在 aliyun `/etc/headscale/policy.hujson.bak-windows-sunshine-*`。Windows UDP 规则来源增加 `100.64.0.0/10`，Codex 未改规则。Claude probe 退出、其 56984/56989/57010 转发关闭后，用户明确授权 Codex 独占 Sunshine；后续测试逐个执行，没有并发串流。

| 测试 | 实际编码/尺寸 | 完整帧 / 观测秒数 | 帧率 | 首帧 / RTT | 音频包 |
| --- | --- | --- | --- | --- | --- |
| Claude Tailscale 基线，10 s | HEVC 1920×1080 | 200 / 10.517 s | 总均值 19.017；稳态约 20–23 | 1241.489 / 19 ms | 0 |
| Codex H.264-only，10 s | H.264 1920×1080 | 549 / 11.182 s | 总均值 49.099；稳态 59.2–60.6 | 1767.796 / 26 ms | 0 |
| Codex HEVC + 第一次打字，20 s | HEVC 1920×1080 | 875 / 20.551 s | 总均值 42.576；打字覆盖不完整，保留原始采样 | 1120.744 / 23 ms | 0 |
| Codex HEVC + 完整持续打字，25 s | HEVC 1920×1080 | 1177 / 25.542 s | 总均值 46.082；打字约 54.8–61.7，结束后 20.5–21.9 | 1271.811 / 31 ms | 0 |

每次请求 60 fps、15000 kbps；严格策略 `ENCFLG_ALL + requireEncryptedStreams`，`supported=requested=enabled=7`，video/audio/control/control_v2=true，result=0。总均值包含建流/首帧及静止区间，不能当作稳态 fps。H.264-only 测试证明回退编码兼容路径可用；未制造 HEVC 编码器故障，不能声称验证了故障触发的自动回退。probe 不播放声音、不显示桌面或保存视频。

Codex 命令复用上面的 loopback 57984/57989/58010，只将 `--udp-host` 改为 `100.64.0.8`，分别 `--codec h264 --seconds 10`、`--codec hevc --seconds 25`，均 `--strict`。持续打字由 cua 对独立记事本 HWND 788388/PID 24040 后台投递 301 字符、70 ms/字符，之后 UIA 与截图读回内容。画面变化时接近 60 fps，静止时较低；这是采样观察，不是已证明的自适应帧率算法结论。

**音频零包的解释（只查配置/日志，没有播放）**：当前配置仅加密两项，未指定 audio_sink/virtual_sink；会话日志成功选择 `virtual-Stereo`，F32 48000 Hz 双声道，Opus 48 kHz/2 channels/512 kbps LOWDELAY，无初始化失败，所有 Win32_SoundDevice 状态 OK。probe 的 launch/resume 没有传 `continuousAudio`，common-c 附加参数只有 `corever=1`；Sunshine [nvhttp.cpp:506](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/src/nvhttp.cpp#L506) 默认 continuousAudio=0。精确版本 [WASAPI capture](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/src/platform/windows/audio.cpp#L573) 在无采样事件时返回 timeout，仅 continuous_audio=true 才补静音；[audio.cpp:252](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/src/audio.cpp#L252) 对 timeout 直接 continue，不编码音频。**推断**：静音/虚拟输出没有 render 数据且未请求连续音频，足以解释零包；不能仅凭配置/日志排除 UDP 音频路径问题，也不宣布音频已验收。Headscale/Windows 规则范围均包含 48000。


第四轮收尾补测：HEVC strict 的原生输入会话 result=0，1920×1080、893 帧/40.571 s、均值 22.011 fps、首帧 1394.863 ms、RTT 19 ms、enc=0x7。输入是少量粘贴/方向键，不是持续打字；隔离变体的主采样循环被多次 SSH 目标校验阻塞，出现约 37 s 的采样间隔，因此不用它评估逐秒帧率。动态打字的帧率结论仍采用前述 25 s 独立实验。

音频补充：两次保护性拒绝输入的会话分别收到 audio_packets=244/246，均 video/audio/control 加密=true、enc=0x7，客户端只解密/计数压缩包，没有主动播放/生成声音，也未在用户电脑打开音频程序。第三次静止原生输入会话又为 0；日志仍成功初始化同一 virtual-Stereo/Opus，第三次有一次 audio capture reinit。**音频 UDP → 解密回调确实收到过数据，不能再声称路径始终不通。** 零包与“未启用 continuousAudio、可能无 render 数据”相容，但具体音频来源/触发条件未测；没有将推断当成唯一原因。音频解码/播放质量与默认音频设备恢复没有专门基线验收，留给正式方案。

本地证据：Claude `/private/tmp/claude-501/-Users-chenxiansheng-miffan/5bf132e0-9637-4ef3-a1ff-3e795b9ff342/scratchpad/win-ts3.json`；Codex `/private/tmp/windows-spike-ts-h264.json`、`windows-spike-ts-typing.json`、`windows-spike-ts-typing-overlap.json`、`windows-spike-ts-media.txt`。初次 status 因 PowerShell ETS 属性膨胀产生过大 JSON，已收敛为原始配置值，脚本改用 `[IO.File]::ReadAllText`，没有重跑安装/配置。

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
由用户处理；不默默改规则。第三轮只读核验已含 LAN 与 100.64.0.0/10，宽泛规则仍 Disabled。

### A5 输入及中文

用户会话中文剪贴板与粘贴已实测：cua `clipboard_write(text="中文测试")`，`clipboard_read(include_text=true)` 返回同样文本，类型含 CF_UNICODETEXT。后台 `press_key(ctrl+v)` 实际插入字母 v；新快照确认失败后，显式 foreground SendInput 重试，独立记事本出现“中文测试”，截图/Document.value 一致。不能把 Session 0 的 `Set-Clipboard` 或后台组合键投递成功当作粘贴成功。

cua 后台 End/Left 确实改变实验光标（End 后列 34；Left 后续输入插在末字前），F5 插入 `15:53 2026/10/12`。Alt+F 后出现访问键/弹出 UI，但没有确认打开“文件”菜单，故不记为 Alt 组合键通过。Ctrl+V 的 SendInput 路径通过。Win 键作用于共享 shell，不在本次只操作记事本的范围内，未发送；锁屏快捷键绝不测试。

坐标尝试只覆盖 cua 窗口局部截图坐标：原窗口 bounds=(343,338,1910,1018)，预览 1568×836；在新 capture_id 下点击 (250,100)，返回 Posted click/effect=unverifiable，没有独立命中证明。End/Left 后文字位置的读回不能独立证明此前坐标点击，故该次 cua 坐标不记为通过，不能由此推断 Sunshine 绝对坐标；后者由第四轮独立原生实验验证。Session 1 的只读 `GetDpiForWindow` 返回 96；仅一块 DISPLAY1，bounds=(0,0,2560,1440)。其它缩放和多显示器没有硬件/当前配置证据，未修改用户显示设置。

Sunshine VK 协议源码 [input.cpp](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/src/input.cpp#L1118) 接收 Windows key code 与 Ctrl/Alt/Shift modifiers；[virtualhid_input.cpp](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/src/platform/virtualhid_input.cpp#L307) 传递 key_code/pressed/extended。Win=0x5B、Alt=0x12、F5=0x74、Left/Right=0x25/0x27 可以作为协议设计依据，不能以 cua 成功代替 Sunshine 原生输入成功。

第三轮隔离变体在首次焦点租约 refused 时停止且未发输入。之后只读查询发现 marker 是 `type_text` **追加到正文**的文本，并不会变成标题；当时实际标题/标签仍为 `*v中文测试Windows spike English  中文输 - Notepad`，窗口存在、未最小化、主屏内。先前按 marker 寻找的指引有误，已向 Claude 更正；不将该失误归为 Windows 窗口消失。

第四轮用户确认该窗口在最前面。由于 SSH 是 Session 0，不再用其 GetForegroundWindow=0 作用户桌面证据，而在交互式 Session 1 创建**无窗口只读观察器**（WindowsApplication，无任何 SetForegroundWindow/输入 API）。创建前确认临时任务不存在并记录；增强光标检查前导出其 XML。实际输出：

```text
ForegroundHwnd=788388, TargetPid=24040, Session=1
Exists=true, Visible=true, Minimized=false, DwmCloaked=0
Dpi=96, MonitorCount=1, ScreenWidth=2560, ScreenHeight=1440
physical WindowRect=(338,338)-(2258,1361)
```

隔离 probe 将正常源码复制到 /private/tmp，不改变 p6a 源码/正常二进制；只有 --input-test 明确 opt-in 才发输入。每批事件前写入随机 nonce，由 Session 1 观察器返回该 nonce 对应的最新前台状态；校验 HWND/PID/Session/可见/cloak、计划坐标命中窗口，且 Windows 收到请求至响应 <500 ms，SSH 总预算 <5 s。这样不依赖两机壁钟一致，也不把 PowerShell 启动耗时当作快照年龄。实测请求后响应 123 ms、整个 SSH 2746.8 ms。

第一次实际绝对移动到 `(600,499)`，比请求 `(600,500)` 差 1 像素，仍命中该 HWND；因最初严格等值检查拒绝点击。第二次允许 1 像素后仍被把 SSH 启动计入的 2 s 阈值拒绝。**这两次均已发送一次鼠标绝对移动，但没有点击/按键**；input_test.sent=false 并不等于完全没有输入事件。保留失败证据，修正校验后第三次完整成功；不把保护性拒绝归为 Sunshine 原生输入失败。

原生测试的实际结果：

| 操作 | 读回证据 / 结论 |
| --- | --- |
| 绝对鼠标位置 `(600,500)` / reference 2560×1440 | GetCursorPos=(600,499)，CursorRootHwnd=788388；仅 1 像素量化差，96 DPI/单屏这个点通过 |
| 原生点击 + Ctrl+V（VK 0x56、MODIFIER_CTRL=2） | 在原正文 offset=122 插入“中文测试”，非尾部；Document 与截图一致，坐标点击命中通过 |
| Ctrl+End / Ctrl+V | 确实在正文末尾插入“中文测试”，证明 Ctrl 修饰及 End=0x23 |
| Left=0x25 / Ctrl+V / Right=0x27 / F5=0x74 | 最终尾部为 `中文测中文测试试16:40 2026/10/12`；插在末字前、再移到末字后插时间戳，分别证明 Left/Right/F5 的实际效果 |
| Alt=0x12 / Escape=0x1B | down/up=0，末态无弹出菜单；只确认队列接收，不独立宣称 Alt/Escape UI 效果验收 |
| Win=0x5B | 未发送，避免作用到共享 shell；只保留上述协议源码依据 |

所有 key down/up 返回 0，input_test.sent=true，严格加密 result=0；实际效果依据 UIA/PNG，不只看排队返回。证据 `windows-spike-a5-native-input3.json/.txt`、`windows-spike-a5-final/result-5.json`、`image-5-2.png`、`windows-spike-a5-verification.json`。其它 DPI/多屏没有实测，也没有修改显示设置。

完成后只在 HWND 788388 的唯一实验标签执行“文件 → 关闭选项卡”，在该窗口的保存提示点**不保存**；随后 list_windows(pid=24040)=[]、精确 get_window_state 返回“No window with window_id 788388 exists”。没有 kill_app/泛杀 Notepad 或关闭其他窗口。

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
| wake | 候选是在用户交互会话以 `SendMessageTimeout(HWND_BROADCAST, WM_SYSCOMMAND=0x112, SC_MONITORPOWER=0xF170, -1)` 请求显示器上电，设超时并回报 request_sent/未知状态；未执行，不等于解锁或硬件唤醒成功 |

JSON 尽量兼容现有 `installed/version/running/lan_encryption_mode/wan_encryption_mode/`
`certificate_sha256/active_stream/candidates`，增加 console_session_id/daemon_session_id、firewall_model_ok、active_stream、wake_requested；未知值输出 null。

[Microsoft WM_SYSCOMMAND](https://learn.microsoft.com/en-us/windows/win32/menurc/wm-syscommand) 定义 SC_MONITORPOWER 的 -1 为上电。Sunshine [display_base.cpp:247](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/src/platform/windows/display_base.cpp#L247) 在捕获线程使用 `SetThreadExecutionState(ES_CONTINUOUS | ES_DISPLAY_REQUIRED)` 保持显示器亮屏，结束清理；这是保活依据，不是所有睡眠情形的 wake 验收。正式 helper 必须先找到已登录用户会话，通过受限任务/桥发送请求，不能在 Session 0 中调用后就报告屏幕已亮，也不能以鼠标移动作默认隐式唤醒。

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
前两轮不注册该默认任务，以实际用户 SID 注册 Limited/Interactive；第三轮才按用户补充授权做 Highest 对照。
SSH 的 USERDOMAIN=WORKGROUP，拼 WORKGROUP\ekk 注册失败；用 WindowsIdentity 的
SID 成功。安装器内部 schtasks 查询“任务不存在”留下 LASTEXITCODE=1，不能把它当作
整个 .ps1 安装失败；已按实际二进制与任务结果核实，未重复安装。
产品遥测默认 enabled，未更改用户选项。

### B2 SSH MCP 与 schema（权限对照已通过）

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

第三轮显式 UTF-8 普通权限桥复验成功：Session 1 daemon PID 18552 和 MCP PID 4640，TokenIntegrityLevel RID=8192；SSH RID=12288。list_apps 返回 234 项，list_windows 返回 11 窗口，中文 JSON 正常。`.NET ProcessStartInfo.StandardOutputEncoding/StandardErrorEncoding=UTF8`，stdin 使用 UTF-8 StreamWriter；逐项文件传输不继承控制客户端 stdin，修复 SSH 子进程吞掉后续请求的问题。临时桥已正常结束并移除任务，未遗留并发 MCP 控制者。

用户授权的 Highest 对照先导出完整 XML（UTF-16）：

```text
C:\Users\Ekk\AppData\Local\Temp\miffan-windows-spike\cua-driver-serve.limited-20261012-152540.xml
SHA256 f99826836a06d6087e5a77bba01baecd0e23a09cc523a08472babf526635854b
本地副本 /private/tmp/windows-spike-b-cua-task-limited.xml
```

仅修改 `cua-driver-serve` 的 Principal 为同用户 SID、Interactive、Highest。裸 exe `serve` 任务退出 LastTaskResult=1，没有 daemon，原因未定。再次导出 `cua-driver-serve.highest-before-wrapper-20261012-152916.xml` 后将 action 换成隐藏 PowerShell 包装，以 CreateNoWindow 和 UTF-8 stdout/stderr 运行同一二进制 `serve`。这次 daemon PID 21628、Session 1、RID=12288；任务仍 Interactive/Highest，同用户。没有放宽 pipe ACL、改 permission profile、绕过 SmartScreen/UAC 或新开 TCP listener。SSH 直连 `\\.\pipe\cua-driver` 完成 initialize，并成功执行 B3。**结论是 Highest + 此启动包装已验证，单改 RunLevel 的裸任务不够。**

安全差异：

| 方案 | 已实测 | 权限与代价 |
| --- | --- | --- |
| Highest Session 1 daemon，SSH 直连 | 通过初始化和桌面工具 | daemon 本身具有 High token，可接触提升权限程序；普通 exe 启动可能继承提升 token，包应用通过系统 broker 的权限则不能仅从 daemon 推断；SendInput 的 UIPI 边界由完整性级别决定。同 SID pipe ACL + SID 校验不要求调用者也为 High，低 mandatory label 允许普通用户客户端接入，因此它可能成为同用户的高权限代理；需要严格工具授权/宿主绑定，不能称为普通桌面权限 |
| Limited daemon + Limited Session 1 MCP 客户端桥 | 通过初始化/schema/list_apps/list_windows | 桌面运行时保持 Medium，不能注入更高 IL 程序/安全桌面；多一层 IPC、生命周期与认证。本次文件桥只是诊断样例，默认用户 ACL、请求/响应文件、缺少完善 nonce/重放防护，不直接用于产品；正式桥应限定 caller/session/任务与目录 ACL，明确 stdout 编码和超时 |

SSH 自身已经是 High 管理员，桥不会撤销 SSH 权限；安全收益是避免每个桌面工具及同 SID 本地客户端都借 daemon 获得 High 执行。源代码同 SID 校验详见上述 serve.rs。Microsoft [SendInput](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-sendinput) 说明只能向等于或低于自身 IL 的程序注入。

### B3 记事本验收与限制

工具默认 background：独立空白记事本 PID 24040/HWND 788388，英文+中文 UIA ValuePattern 写入后 effect=confirmed，Document.value 与 PNG 一致。后台 UIA 菜单点击（成功新建空白窗口）、End/Left/F5、持续 WM_CHAR 中英文有后续快照/截图证据；坐标点击仅投递、命中未独立验证。clipboard_write/read 为 Session 1 用户剪贴板，中文完全正确；Ctrl+V 默认后台失败（实际 v），仅显式 foreground 重试通过，未将 transport success 当作验收。foreground 路由按文档会暂时切换并恢复前台；未采集足够前后台 HWND 序列证明恢复，不能额外承诺完全不抢焦点。

初次 AUMID launch_app 的 `creates_new_application_instance=true` 在 Windows 是 no-op，现代记事本恢复了历史文档。随后在恢复窗口只调用“文件 → 新建窗口”，取得独立空白窗口后才输入；未编辑/保存/关闭历史文档。一次限制深度的树查询意外返回历史 Document.value，属于实验观察范围控制失误，已删除该两份本地原始响应，之后对恢复窗口强制禁截图并剥离 Document/Edit/Text.value；窗口标题亦在本地证据脱敏。无法撤回此前工具会话记录，笔记不包含历史文件名或内容。正式验收启动器必须避免恢复用户历史窗口，不能把 query/max_depth 当成数据隔离。

第三轮依停机规则等待用户激活；第四轮完成 A5 后通过目标窗口“关闭选项卡”与“不保存”关闭 HWND 788388，明确查询已不存在。只关闭该实验标签/窗口，没有 kill_app。用户此前自行操作后的同 PID 枚举只剩一个窗口，不把其它窗口消失归为本实验清理。所有实验 MCP session/client 已结束。

### B4 共存 / B5 锁屏

B4：HEVC strict 串流期间 cua 在同一真实桌面的实验记事本后台输入 301 字符（70 ms/字符），读回与截图正确，收帧 1177、打字稳态约 60 fps，未观察到连接/编码中断。控制台/进程模型没有冲突；这不证明两个全局输入控制者可同时安全操作，仍应保持单控制者。

B5：仅源码/文档调研，未锁屏。Interactive 用户任务须已有用户登录才能运行；Session 0 不提供代表性的 UIA/截图/焦点行为。锁屏/UAC 切换到安全桌面，用户 High daemon 仍不是 Sunshine LocalSystem；[Microsoft Desktop Security](https://learn.microsoft.com/en-us/windows/win32/winstation/desktop-security-and-access-rights) 的桌面 ACL/访问权限仍适用。不能承诺能截图/操作安全桌面，也未确认安装版在这些状态下的具体结构化错误、解锁后的恢复时序。正式方案应只报告 locked/unavailable/unknown，停止动作、在用户解锁后重新枚举，不能绕过登录或冒充恢复成功。

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

### C2 当前 POSIX 依赖与适配位置

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
| 创建 `%TEMP%\miffan-windows-spike`，保存导出/备份/启动包装 | firewall-before.clixml / firewall-ports-before.clixml、任务 XML、user-path-before.txt | **当前保留：cua 任务依赖 cua-logged-daemon.ps1，回滚任务或迁移包装前不能删整个目录**；导出不改变防火墙 |
| 安装 Sunshine MSI；厂商添加宽泛规则、PATH/服务/自启动 | 第一轮规则 JSON、第二轮版本/服务输出 | 用户决定保留或卸载；卸载也会删规则，不自行执行 |
| 用户禁用宽泛规则、添加 UDP LAN 规则 | 第二轮只读输出，用户/Claude 交付 TCP 不可达结果 | 由用户管理，Codex 不修改/撤销 |
| Sunshine LAN/WAN 改为 2/2、重启其服务 | sunshine.conf.bak-windows-spike-20261012-144022 | 无活动流后恢复该备份，再重启 SunshineService |
| 创建测试管理员并配对 miffan-win-spike | 初始状态不存在证据、授权目录 admin.json/probe-identity | 只撤销该测试客户端；新装主机状态无其他新增数据时才能按初始不存在状态恢复，不能删其他客户端/管理员配置 |
| 按用户安装 cua 0.34、添加 User PATH、初始注册 Limited/Interactive cua-driver-serve | user-path-before.txt、cua-task-before.txt=absent、版本/任务输出 | 用户决定保留；卸载/移除任务前检查无其他使用者，只移除本次 PATH 项，别覆盖后来的 PATH 改动 |
| cua-driver-serve 从 Limited 改 Highest，随后 action 改隐藏日志包装 | B2 完整 XML 两级备份、哈希、RID/Session 输出 | 先结束实验 MCP/确认无使用者，Stop-ScheduledTask；以 Limited XML Register-ScheduledTask -Xml ... -Force，Start-ScheduledTask，再核验进程/Session/IL；不要只改 RunLevel 忘记 action |
| 新建独立实验记事本、第三轮短时焦点租约、第四轮只读观察器任务 | HWND 788388、输入/不保存/窗口不存在证据、观察器任务 absent/导出 XML | **已清理**：只关该实验标签/窗口、不保存；观察器任务已移除且无观察器进程。临时二进制/源码/XML 保留审阅，可精确删除，不应重新注册旧任务 |
| 用户会话剪贴板写入实验文本 | clipboard_read 返回“中文测试”；最后一次 agent write 同样文本 | 未读取/记录原内容，不能恢复；不覆盖用户后续剪贴板变化 |
| 临时普通用户 MCP 桥任务与文件 | Miffan-Windows-Spike-MCP、mcp-bridge/ | 已用 cleanup 脚本正常结束并移除任务，未关闭 daemon；文件暂留作证据 |
| 临时下载代理与 TCP 转发 | 仅 loopback SSH forwarding | 已关闭唯一明确匹配本实验的 SSH 进程，未改系统代理/规则 |

未重启/注销/锁屏 Windows，未修改 OpenSSH/RDP 配置或防火墙；Tailscale/Headscale 由用户及 Claude 处理。当前 `cua-driver-serve` 保留用户授权的 **Highest/Interactive 隐藏包装**，daemon 最后核验 Session 1/High；没有擅自宣称已恢复 Limited。

完整回滚示例（备份已存在，未执行；必须先结束本实验客户端、核对无其它使用者）：

```powershell
$backup=Join-Path $env:TEMP 'miffan-windows-spike\cua-driver-serve.limited-20261012-152540.xml'
Stop-ScheduledTask -TaskName 'cua-driver-serve'
Register-ScheduledTask -TaskName 'cua-driver-serve' -Xml ([IO.File]::ReadAllText($backup)) -Force
Start-ScheduledTask -TaskName 'cua-driver-serve'
Get-ScheduledTask -TaskName 'cua-driver-serve'  # RunLevel Limited / LogonType Interactive
Get-Process cua-driver | Select-Object Id,SessionId
```

提供 `restore-cua-task.ps1`，默认只检查 XML，显式 -Apply 才执行并先备份当前 XML；该脚本尚未执行远端验收。Stop task 不保证所有子进程均被结束，恢复前应核对精确实验 PID，不能泛用 Stop-Process cua-driver 杀其它用户客户端。回滚后 SSH→Medium 直连预计恢复原失败状态，需使用普通客户端桥。此撤销方法不改 OpenSSH/RDP/防火墙。

本地安装包 `/private/tmp/windows-spike-sunshine-proxy.msi`；测试密码/身份只放主 checkout
已忽略的 `.deps/windows-spike`，权限实际确认 0700/0600。模拟器原测试身份备份也在此
授权目录。官方 cua 安装包通过原脚本临时目录校验安装，脚本自动清理下载 staging。

本机 probe 用公开离线 embedded.tar.gz 解压到 `/private/tmp/windows-spike-probe-deps`
补全依赖，`make -j4 DEPS_DIR=...` 编译通过；没有修改共享源码原件。
不记录密码、PIN、客户端/主机私钥或 launch rikey。管理员/配对状态仍在主机使用，
完整撤销前必须核对是否有用户之后添加的配置。

## 风险清单

1. Sunshine 安装/升级可能重新添加或启用宽泛 Allow，helper 必须按有效规则检测并提示用户。
2. ad-hoc Mac LAN 隐私限制已有 Claude pktmon 对照；Tailscale 成功不代表手机 LAN、漫游/恢复/掉线均通过。
3. Highest 同 SID desktop daemon 是高权限代理；Medium 桥能保留桌面权限边界，但本次文件桥不具备产品所需认证和重放防护。
4. stdin/stdout 及 .NET 子进程均需显式 UTF-8；PowerShell ETS 自动扩展也会导致异常大 JSON，应输出朴素值。
5. 后台 Ctrl+V 失败，F 键/箭头与文本并不等价；只能以读回结果决定降级，foreground 需明确授权和恢复验证。
6. 现代记事本可能恢复历史用户数据；query 保留祖先，max_depth 不阻止 Document.value 出现，必须限定空白窗口/避免自动保存历史数据。
7. Sunshine 原生坐标与指定 VK 组合在 96 DPI/单屏通过；其它 DPI/多屏、Win 及 Alt 的 UI 效果仍未覆盖。
8. 已收到两次加密音频包，但 AES-CBC 不提供视频/控制那样的 AEAD；未验证音频来源/解码/播放及默认设备恢复，本次未主动播放声音。
9. 配置/任务回滚必须精确，不能全量恢复 firewall/PATH 覆盖用户后来的更改；当前 Highest 启动包装在 TEMP，清理目录会破坏自启动，正式方案必须迁移到稳定的用户目录或回滚任务。

## 收尾状态与需要决定的问题

2026-10-12 16:45:48 的只读 `windows-spike-final-status.json`：SunshineService Running，service PID 14076/Session 0、sunshine PID 13352/Session 1；cua-driver PID 21628/Session 1，cua-driver-serve Running/Highest/Interactive/Ekk，action 为 TEMP 中的隐藏包装；User PATH 含 Cua bin。`Miffan-Windows-Spike-*` 临时任务列表为空，无观察器进程，正常 daemon 保留。加密配置 2/2；两条宽泛规则 Disabled，窄规则 UDP 47998–48000 仅 LAN+100.64.0.0/10、限 sunshine.exe。没有改防火墙、OpenSSH 或 RDP。

**已完成清理**：实验记事本选择“不保存”且 HWND 788388 不存在；临时普通客户端桥和只读观察器任务/进程已移除，MCP session/client、本轮 SSH forwarding、probe 均退出。本地 emulator-5560 的 adb 连接 TCP 5560 已 refused，未发 kill，没有触及 emulator-5554；第二轮 prefs/NAT 恢复证据仍保留。

**当前保留 / 可撤销项**：

| 保留项 | 状态与后续动作 |
| --- | --- |
| Sunshine、SunshineService、厂商 PATH/自启动 | 保留供正式方案；若用户决定卸载，仅用官方卸载流程，先保存需要保留的配置；Codex 不自行删服务/规则 |
| Sunshine 2/2 与初始空配置备份 | 保留安全设置；无需撤销以满足设计，若退回实验前必须无流后恢复指定备份并仅重启该服务 |
| miffan-win-spike 管理员、测试配对身份/状态、公私钥 | 本轮未撤销；待 Claude 审阅后定向删除测试客户端/管理员，先确认没有用户后加的数据；不 unpair-all，不删/导出主机私钥 |
| cua 0.34、User PATH、Highest/Interactive 用户任务 | 保留用户授权的对照状态；正式选择 Medium 桥或 Highest 前明确安全边界。回滚按上面的完整 Limited XML、核对精确实验 daemon PID，别只改 RunLevel |
| Windows TEMP 的启动包装/备份/安装日志/桥文件 | 当前包装 **不能删除**；先迁移任务到稳定 AppData 路径或回滚任务，再由用户/Claude审阅后精确清理其余本实验文件。目录 27 个直接文件及桥子目录，未全量删除 |
| 用户/Claude 配置的 Tailscale、Headscale grant、防火墙 | 非 Codex 改动，保留；任何调整/撤销仍由用户/Claude管理，备份位置见 A3 |
| `.deps/windows-spike` 测试凭据与模拟器 prefs 备份 | 0700/0600 已复核；审阅/定向撤销前保留，不复制到 git 或公开证据包 |
| 剪贴板 | agent 最后写入“中文测试”；原内容未保留，不能恢复，也不覆盖用户之后的新值 |

正式验收仍需多 DPI/多屏、真实手机 LAN、音频解码/来源/设备恢复、锁屏/UAC/登录切换恢复；本轮遵守禁主动锁屏等限制，以调研与明确范围结论交付。Win 键不得为了测试而作用到用户其它窗口。工作量/架构建议见开头。

## 临时证据清点与审阅顺序

完整清单见 [WINDOWS_SPIKE_ARTIFACTS.md](WINDOWS_SPIKE_ARTIFACTS.md)：87 个顶层证据项、833 个普通文件、约 85.74 MiB（不跟随 symlink，不含清单 JSON 自身）。没有为“收尾”删除审阅证据。各顶层目录已限制 0700，文件限制 0600；凭据在主 checkout `.deps/windows-spike/`，不在这份清单中。

优先保留并审阅：

1. `windows-spike-final-status.json`、`windows-spike-local-cleanup.json`：保留/清理状态。
2. `windows-spike-a5-foreground-before.txt`、`a5-cursor-calibration.txt`、`a5-native-input3.json/.txt`、`a5-verification.json`：精确前台、坐标、原生输入、结果差异。
3. `windows-spike-a5-final/result-5.json` / `image-5-2.png`：输入后读回；`result-12–15.json`：保存提示、不保存、窗口关闭。
4. B 的 Medium/High token、两个 XML、medium-bridge、b3-isolated 和完整 tool schema：权限对照与桌面动作。
5. `ts-h264.*`、`ts-typing-overlap.*`、`ts-media.txt`、`a5-media-final.txt`：NVENC、帧率、音频。
6. `command-probe*.jsonl`、LAN 失败、模拟器 prefs/NAT 恢复证据。

MSI（32.15 MiB）、APK（14.49 MiB）、Sunshine 源码（31.48 MiB）、离线依赖/隔离 probe 可保留复现，也可在审阅后重建；其余重复状态/启动失败辅助日志按清单处理。旧历史文档原始响应已删除，其它相关本地元数据已脱敏；不把它们重新打包。清点程序 `tools/windows-spike/inventory-temp.py` 只写清单，不删除任何文件。

## 第三轮证据索引

- `/private/tmp/windows-spike-b-medium-bridge/result-3.json`、`result-4.json`：UTF-8 list_apps/list_windows；`windows-spike-b-medium-token.txt`：Medium 客户端/daemon，High SSH。
- `/private/tmp/windows-spike-b-cua-task-limited.xml`；`windows-spike-b-highest-token.txt`：裸 Highest 无 daemon；`windows-spike-b-highest-token-logged.txt`：隐藏包装 High/Session 1。
- `/private/tmp/windows-spike-b3-isolated/`：新记事本 B3、clip、截图、持续打字、F5；历史窗口响应已在本地脱敏，不用于截图或输入。
- `/private/tmp/windows-spike-b3-window-metrics.json`：96 DPI / 一块 2560×1440 屏。
- `/private/tmp/windows-spike-ts-h264.json`、`windows-spike-ts-typing-overlap.json`、`windows-spike-ts-media.txt`：媒体与编码器/音频日志。
- `/private/tmp/windows-spike-ts-input.json`、`windows-spike-ts-input.txt`：焦点租约 refused，input_test.sent=false；隔离变体在 `/private/tmp/windows-spike-notepad-probe/`，正常 p6a-probe 未修改。
- 第二轮基线、LAN 失败、模拟器恢复及命令编码证据仍在 `windows-spike-*.json/jsonl/txt`，不进 git。

第四轮证据还包括 `windows-spike-a5-*`、`windows-spike-final-status.json` 与完整临时清单。本次按授权范围完成实验和收尾；未测项目按上述范围明确留给正式兼容验收，不宣称全场景通过。

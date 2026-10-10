# P6f：Mac 高性能模式的数据层与远端脚本

## 实现与契约

- `miffan.sh` 升到版本 11。`sunshine probe` 保留 Linux 字段，追加 `display_asleep`、`permissions: {screen_recording, accessibility}`、`permissions_from_log`。三个追加 JSON 字段在 Linux 为 null；数据层的 `permissionsFromLog` 对 null/缺字段归一化为 false。
- macOS 安装探测依次检查用户目录、系统目录 Sunshine.app，版本只读 `Info.plist/CFBundleShortVersionString`；无 bundle 时检查 Homebrew cask/formula 版本及 prefix 中的 bundle。不调用 Sunshine 二进制（包括 `--version`）。纯 formula 无 bundle 时可报告安装，但 `start` 明确失败，不改用后台二进制启动。
- 进程来自 `ps -ww -axo uid=,pid=,comm=`，只接受当前 UID、可执行文件名为 Sunshine 的进程；不计其他用户。读取该 PID 的命令行及必要的 cwd 以确定显式配置/覆盖值。多个实例或无法确定配置时拒绝改加密；进程无法探测时也拒绝启动。
- macOS 配置默认 `~/.config/sunshine/sunshine.conf`，LAN/WAN 默认 0/1。证书只经 `openssl x509` 读取公共证书并计算 DER SHA-256，不读取私钥。
- `lsof -nP -iUDP` 中匹配当前用户 Sunshine PID 和配置媒体端口（基础端口 +9/+10/+11），判断活动/待建流。检查失败时对正在运行的实例保守视为忙；修改和重启前再次检查，未知则拒绝。`ifconfig` 只保留 RFC1918/CGNAT IPv4，过滤 lo、bridge、awdl、llw、vmenet、vmnet、vboxnet 等接口；保留 utun 的 Tailscale 地址。
- `start`：Linux 使用已有单一用户 unit 的 `systemctl --user start`；Mac 使用 `open -a <bundle>`。已运行直接成功。Mac 加密操作保留备份、原子写入、配置变化/命令行覆盖/活动串流保护；用固定 bundle ID 的 `osascript quit` 优雅退出，等待最多 5 秒。退出失败/超时不强杀、不重新打开；已保存的配置和备份保留并在结果中说明。重开保留已验证的配置参数。
- `wake`：Mac 使用 `caffeinate -u -t 5`，重新探测并返回 `display_asleep`；Linux 明确不支持。
- `open-settings screen|accessibility`：固定参数映射到 `Privacy_ScreenCapture`/`Privacy_Accessibility`，只 `open` 页面；命令失败时退到 `com.apple.settings.PrivacySecurity.extension` 总页。Linux/未知权限返回失败。
- 数据层增加 `RemoteStreamStatus` 四个字段、`MAC_PERMISSIONS`/`DISPLAY_ASLEEP`、`startSunshine` 和 `openSunshinePermissionSettings`/`RemoteSunshinePermission`。所有手动命令沿用主机 revision 校验及 SSH lease。Mac 也进入高性能模式；明确睡眠且运行时先 wake，仍睡眠/唤醒状态未知则 `DISPLAY_ASLEEP`。建流失败再只读 probe，把明确权限/显示器证据转换成 fallback；诊断失败保留原失败，证书变化仍独立抛出。
- `StreamDesktopSession.typeText` 保留 SSH clipboard 写成功后才粘贴；Mac 使用 Cmd+V，Linux 保持 Ctrl+V。临时释放并恢复物理修饰键。
- UI 唯一改动是 Claude 明确授权的编译兼容分支：两个新增 fallback 暂映 `workspace_screen_stream_reason_other`，注释 `Claude replaces with dedicated strings on merge`。未改 VM 或字符串；正式文案由 Claude 合并时补齐。

## 显示器与权限判断依据

`display_asleep` 读取 `ioreg -a -r -n IODisplayWrangler` 的 `IOPowerManagement.CurrentPowerState/MaxPowerState`；若缺失，尝试 `pmset -g powerstate IODisplayWrangler` 的 current/max 列。仅把 current=0 判为睡眠、current=max（max>0）判为清醒；中间态和无证据均为 null，避免将变暗/过渡态误判。本机 macOS 26.5.2 的 ioreg 没有该电源字典，pmset 返回 `Internal failure: Failed to get power state information`，因此实测为 null。没有查询权限、加载驱动或唤醒来补全只读 probe。

权限始终不读 TCC.db，不绕过 TCC，不以辅助进程的 CoreGraphics/AX 权限冒充 Sunshine 权限。只扫描配置日志尾部最多 256 KiB，并限制在最后一个 `Sunshine version:` 启动段。唯一明确的屏幕权限缺失文本是 `No screen capture permission!`，来自：

- 参考 v2026.1008：`src/platform/macos/misc.mm:340`，后续说明指向 `System Settings -> Privacy & Security -> Screen & System Audio Recording`。
- 安装版 [v2026.914.233613 misc.mm](https://github.com/LizardByte/Sunshine/blob/v2026.914.233613/src/platform/macos/misc.mm#L94)：同一句拒绝文本，但后续说明仍写旧的 System Preferences 路径，且会调用 `CGRequestScreenCaptureAccess`。

只有该明确拒绝把 `screen_recording` 置 false，并置 `permissions_from_log=true`。没有拒绝/日志不可读/旧段落的拒绝均保留 null；不会根据成功初始化推断 true。查阅这两个版本的 macOS 平台及 libvirtualhid 后端，未找到能可靠断言“辅助功能未授权”的明确 Sunshine 日志；`accessibility` 目前保留 null。`No display devices are active at the moment! Cannot probe the encoders.`、一般事件创建失败、音频权限错误均不当作屏幕录制或辅助功能拒绝。

macOS 26 URL 的只读核实：本机 `/System/Library/ExtensionKit/Extensions/SecurityPrivacyExtension.appex/Contents/Info.plist` 声明 bundle ID 为 `com.apple.settings.PrivacySecurity.extension`、`allowsXAppleSystemPreferencesURLScheme=true`、`legacyBundleIdentifier=com.apple.preference.security`；其可执行文件字符串包含两个 `Privacy_*` anchor。因此旧 URL 仍有系统注册和 anchor 的依据；未实际弹出窗口验证导航。参考 Sunshine v2026.1008 的 `open_privacy_settings` 也使用该 legacy URL；[Chromium 144 的实现](https://chromium.googlesource.com/chromium/src.git/+/refs/tags/144.0.7559.228/base/mac/mac_util.mm) 使用现代 extension URL。helper 保留失败时的总页 fallback；`open` 成功只表示 URL 被接收，不保证每个未来 OS 都导航到子页。

## 键位、Retina、多显示器源码核实

参考源码并非旧版 `KeyCodeMap` 在 Sunshine `macos/input.cpp` 的布局：v2026.914 已使用 libvirtualhid，v2026.1008 同样如此。安装版本的 submodule 精确提交为 [53e1a949fc0784af716b782ddfa6c647cafd1f05](https://github.com/LizardByte/libvirtualhid/blob/53e1a949fc0784af716b782ddfa6c647cafd1f05/src/platform/macos/macos_backend.cpp)，核实该提交而非只看 master：

| X11 keysym / Windows VK | macOS 后端 |
| --- | --- |
| SUPER_L/R → 0x5B/0x5C | kVK_Command / kVK_RightCommand |
| ALT_L/R → 0xA4/0xA5 | kVK_Option / kVK_RightOption |
| CTRL_L/R → 0xA2/0xA3 | kVK_Control / kVK_RightControl |
| F1–F20 → 0x70–0x83 | kVK_F1–kVK_F20 |
| Left/Up/Right/Down → 0x25–0x28 | 对应 kVK_*Arrow |
| Home/End/PgUp/PgDown、Backspace/Delete | 对应原生导航/删除键 |

Command/Option/Control 不需要互换；VM 的平台快捷键应发 SUPER_L。F21–F24 无原生映射（-1）；VK_APPS（Menu）居然映为 RightCommand，适配层在 Mac 过滤 Menu，防止意外按住 Command，同时过滤 F21–F24、Pause、ScrollLock、Print/SysReq、NumLock 这些后端无支持的键。Linux 的原映射完整保留。

绝对坐标保持视频尺寸发送：Sunshine `input.cpp::client_to_touchport` 先处理视频/屏幕比例与 letterbox；`virtualhid_input.cpp::abs_mouse` 向后端传坐标和触控端口宽高。安装版后端 `absolute_mouse_location` 用 `x * CGDisplayBounds.width / event.width`（y 同理）加屏幕原点，目标 `CGDisplayBounds` 是点坐标，因此主屏 Retina 无需客户端额外除以 2。不能以模拟器相对移动验收声称绝对坐标或多屏已实测。

捕获默认 `CGMainDisplayID()`，配置 `output_name` 为合法 display ID 时捕获指定屏（两个 Sunshine 版本的 `macos/display.mm::display`）。**安装版 libvirtualhid 的输入状态始终选主屏 `CGMainDisplayID()`；指定非主屏捕获时，指针可能仍落主屏。** 参考新后端已引入 viewport API，但不能据此认定用户安装版已修复；本阶段没有修改上游或强制用户主屏设置。当前建议/验收范围为默认主屏。

## 验证记录

- `sh -n app/src/main/assets/remote/miffan.sh`、两个 Python 验证脚本的语法检查通过。
- helper 离线测试目前 20 个通过：Linux package/default/地址/备份/两次竞态/拒绝检查/start/不支持 Mac 命令；Mac plist、Homebrew、UID、配置参数与自定义端口、接口过滤、ioreg/pmset/unknown、明确日志与旧日志排除、加密备份与优雅重启、退出失败/超时、活跃及竞态/未知、start 幂等/检查失败、wake、两权限 URL/总页 fallback。
- fixture 的 uname、ps、lsof、ioreg、pmset、open、osascript、caffeinate 均伪造，Mac fixture 的 HOME、PATH 及系统 bundle 查询也隔离。第一轮原 Linux fixture 未固定 uname，落入本机 Mac 探测；helper 的配置校验拒绝写入。随后核对本机配置仍仅 `address_family = both` / `locale = zh`，没有 miffan 备份，并修正 fixture 隔离。
- `:stream:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` 已通过；新增 JVM 用例覆盖 Mac JSON/null、两 fallback、诊断证据/保留原失败、wake 触发条件、Cmd+V/修饰键恢复、键位过滤和 Linux 映射不回归。最终版本复核 `BUILD SUCCESSFUL in 18s`；`:stream` 8 项、`:app` 688 项 JVM 测试均无失败/错误/跳过。
- `:stream:assembleDebugAndroidTest` 已通过。新增 `macReceiveOnlyAcceptance`：TCP 与 UDP 都使用 10.0.2.2，独立 Mac 配对身份，只收音画和鼠标 5px 往返，不发文字/快捷键；验证视频帧、三通道加密、AudioTrack 播放/静音/焦点/关闭，并保存截图与统计到 `stream/build/test-output/`。

### 本机只读 probe（2026-10-10，Sunshine 尚未启动）

已在真实本机运行，包含沙箱外只读复核；未自己执行本机 start、enforce-encryption、wake、open-settings 或退出 Sunshine：

```json
{"installed": true, "version": "2026.914.233613", "running": false, "lan_encryption_mode": 0, "wan_encryption_mode": 1, "certificate_sha256": "2694e7d0dddb25095c2e7b63c7624c01fa8693b696f2c5f77ad898ede54ed391", "active_stream": false, "candidates": ["192.168.31.26", "100.64.0.2"], "display_asleep": null, "permissions": {"screen_recording": null, "accessibility": null}, "permissions_from_log": false}
```

### 端到端安排

Sunshine 的启动、强制加密、图形权限和 PIN 由用户在 Claude pane 安排。测试 APK 已交付；配对脚本 `test-emulator.py pair --mac --certificate-sha256 <公共指纹>` 在 pane 用隐藏输入接收 PIN，PIN 不放进 argv/日志。测试身份私钥只由 Android 自行生成/保存，不复制 Sunshine credentials。只使用 emulator-5560，Mac 验收命令为 `test-emulator.py mac --software --width 1280 --height 720 --fps 30 --bitrate 4000 --timeout 10000`。

等待 pane 通知 Sunshine/加密/配对就绪；未把准备好的测试当作端到端通过。

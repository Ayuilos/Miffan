# P6f：Mac 上的高性能模式（数据层与远端脚本）

背景：Linux 的高性能模式已完成并合并（`P6C_*`、`P6E_*`）。本阶段让 macOS 主机也能用 Sunshine。**本任务（Codex）负责远端脚本、数据层、`:stream` 适配与测试；UI、`RemoteScreenVM.kt`、`app/src/main/java/me/ayuilos/miffan/ui/` 与字符串由 Claude 负责。** 契约有疑问先在 pane 里问 Claude。

## 已知现场（2026-10-10，Claude 只读查看）

用户的 Mac 就是本机（macOS 26.5.2，Apple Silicon）：

- `/Applications/Sunshine.app`，`CFBundleIdentifier = dev.lizardbyte.app.Sunshine`，版本 `2026.914.233613`，Developer ID 签名（Team `F8XQ7XCN2R`）；目前**没有运行**，没有 LaunchAgent（用户手动打开）。
- 配置在 `~/.config/sunshine/`（`sunshine.conf` 只有 `address_family = both`、`locale = zh`，加密为默认值：LAN 0、WAN 1），证书在 `credentials/`。
- 日志显示 10-07 曾成功串流：VideoToolbox H.264 / HEVC，系统音频 tap；系统托盘正常。
- 日志多次出现 `No display devices are active at the moment! Cannot probe the encoders.`——显示器睡眠时无法串流。
- 本机应用防火墙开启，“自动允许已签名软件”开启。

源码在 `/Users/chenxiansheng/miffan/.deps/p6a/sunshine`（`v2026.1008` 预发布，与用户的 `2026.914` 接近；差异处请注明）。

## 要做的事

### 1. 远端脚本 `miffan.sh`（版本 11）的 macOS 分支

- `sunshine probe` 在 macOS 上输出与 Linux 相同的字段，并新增 macOS 字段（Linux 输出 null）：
  - 安装：`/Applications/Sunshine.app`、`~/Applications/Sunshine.app` 或 Homebrew 安装；版本取 `Info.plist` 的 `CFBundleShortVersionString`（Homebrew 用 `brew list --versions`）。**不要运行 Sunshine 二进制**（会轮转运行实例的日志，Linux 上已实测）。
  - 运行：Sunshine 进程是否存在（只认当前用户的进程）。
  - 加密：读 `~/.config/sunshine/sunshine.conf`（或进程参数指定的配置），默认值与 Linux 相同。
  - 证书指纹、活动串流（用 `lsof -nP -iUDP` 判断 Sunshine 是否占用媒体端口）、候选地址（`ifconfig`：私有 IPv4 与 `100.64.0.0/10`，排除 loopback、`bridge*`、`awdl*`、`llw*`、虚拟机接口）。
  - `display_asleep`：显示器是否在睡眠（例如 `ioreg -n IODisplayWrangler` / `pmset -g powerstate` 等可靠且无需权限的依据，写明依据）；判断不了就输出 null。
  - `permissions`：Sunshine 的“屏幕录制”和“辅助功能”授权状态。SSH 下读不到 TCC 数据库时输出 null，**不要尝试读取受保护的 TCC.db 或绕过隐私保护**。可以从 Sunshine 日志中的明确报错推断“缺少权限”（请从 Sunshine 源码 `src/platform/macos/` 找出缺权限时的确切日志文字，写进 NOTES），推断出的值标注为来自日志。
- `sunshine start`（Linux 与 macOS 都要）：Linux `systemctl --user start <unit>`；macOS `open -a <Sunshine.app 路径>`（在用户已登录的图形会话中启动）。已在运行时直接返回成功。
- `sunshine enforce-encryption` 的 macOS 分支：同 Linux 的备份、原子写入、有活动串流时拒绝；重启方式为优雅退出（`osascript -e 'quit app id "dev.lizardbyte.app.Sunshine"'`，等待进程退出，超时则返回失败、不强杀）后 `open -a` 重新打开。
- `sunshine wake`（macOS）：唤醒显示器（例如 `caffeinate -u -t 5`），返回唤醒后的 `display_asleep`。Linux 返回不支持。
- 不修改防火墙、不安装软件、不改其他设置；不读取或输出密码、PIN、私钥。

### 2. 数据层

- `RemoteStreamStatus` 增加：`displayAsleep: Boolean?`、`screenRecording: Boolean?`、`accessibility: Boolean?`（null = 不知道）、`permissionsFromLog: Boolean`。
- `RemoteStreamFallbackReason` 增加：`MAC_PERMISSIONS`（日志表明缺权限，或建流后无画面且日志指向权限）、`DISPLAY_ASLEEP`。
- `RemoteScreenRepository.startSunshine(hostId: String, expectedRevision: String): RemoteCommandOutcome`（调用 `sunshine start`）。
- `open()`：平台为 macOS 时也尝试高性能模式（去掉 Linux 限制）。建流前若 `display_asleep == true`，先 `sunshine wake`；仍睡眠则 fallback `DISPLAY_ASLEEP`。
- 输入适配（`StreamDesktopSession` / `StreamDesktopInput`）：
  - macOS 上 `typeText` / 粘贴用 **Cmd+V**（Linux 仍是 Ctrl+V）；`miffan clip` 在 macOS 已用 pbcopy。
  - 从 Sunshine macOS 源码核实 Windows VK → macOS 键码的映射：Command（`SUPER_L` → `VK_LWIN`？）、Option、Control、F 键、方向键等是否正确；VM 在 macOS 上把“平台快捷键”映射为 `SUPER_L`。不一致的地方在适配层修正，并写进 NOTES。
  - 从源码核实 Retina：绝对坐标按视频尺寸发送后，Sunshine 是否正确换算到点坐标；多显示器时串流的是哪一块屏。
- 现有 Linux 行为不得回归。

### 3. 验证

- JVM 单元测试：macOS probe JSON 解析、新 fallback 判定、macOS 粘贴快捷键、键位映射。
- 离线 helper 测试：用伪造的 `ifconfig` / `lsof` / `pmset` / `Info.plist` 覆盖 macOS 分支（参照现有 `tools/remote-screen/test_sunshine_helper.py`）。
- 本机只读运行：`sh app/src/main/assets/remote/miffan.sh sunshine probe` 可以直接在本机运行（就是用户的 Mac），把输出写进 NOTES。**不要自己运行 `start`、`enforce-encryption`、`wake`，也不要退出或启动 Sunshine**——这些会改动用户正在用的电脑，需要时在 pane 里请 Claude 安排（用户会配合启动 Sunshine、授予权限、输入 PIN）。
- 模拟器端到端（只用 `emulator-5560`，在 Claude 安排好 Sunshine 运行、加密与配对之后）：模拟器的 TCP 与 UDP 都发往 `10.0.2.2`（即本机）；只做声音 / 画面接收与小幅鼠标移动，**不发送文字或快捷键**。
- `./gradlew :stream:testDebugUnitTest :app:testDebugUnitTest :app:assembleDebug` 通过。

## 交付

- 代码、脚本、测试；`docs/remote-screen/P6F_NOTES.md`：实现说明、权限与显示器判断依据、键位 / Retina 核实结论、实测记录、遗留问题。
- worktree 分支提交，不提交到 `feature/im-4.0`。

## 约束

- 不修改 UI、`RemoteScreenVM.kt`、字符串资源。
- 不 ssh 到任何机器；不读取受保护的 TCC 数据库，不绕过 macOS 隐私保护。
- 不读取或复制签名配置、`local.properties`（worktree 里只有 `sdk.dir`）、任何私钥（包括 `~/.config/sunshine/credentials/` 下的私钥）。

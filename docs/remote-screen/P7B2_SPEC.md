# P7b 第二部分：Windows 屏幕接入 App（Codex 开发任务）

先读：
- `docs/remote-screen/P7_WINDOWS_PLAN.md`：已确定的决策，第 4 节是屏幕。
- `docs/remote-screen/P7A_NOTES.md`：Windows 执行层、`RemotePlatform`、能力标志。
- `docs/remote-screen/P7B_NOTES.md`：`miffan.ps1` 的命令契约，以及“第二部分接入说明”。
- `docs/remote-screen/P6C_NOTES.md`、`P6F_NOTES.md`：现有 Sunshine 接入，Mac 的做法可作参照。

## 目标

Windows 主机可以在屏幕页通过 Sunshine 高性能模式查看和操作真实桌面，中文输入走剪贴板。伙伴操作电脑（cua）**不在本阶段**，那是 P7c 的范围，相关入口保持“暂不支持”。

## 范围（数据层）

1. **平台**：`RemoteScreenPlatform` 增加 `WINDOWS`。识别以 P7a 的 `RemotePlatform`（SFTP）为准，再用 `miffan.ps1 probe` 的结果补充。
2. **辅助脚本的安装与调用**：`ensureHelper` 等函数按平台分支。Windows 上：
   - 安装路径：`%LOCALAPPDATA%\Miffan\bin\miffan.ps1`。保留 UTF-8 BOM，原子替换；版本号不一致时重新安装。
   - 调用：通过 P7a 的 PowerShell 执行层执行 `& "$env:LOCALAPPDATA\Miffan\bin\miffan.ps1" <命令>`，数据走 stdin。
   - 输出解析复用现有的 JSON 模型；字段有差异的地方，在 `miffan.ps1` 或解析层对齐，二选一，写进笔记。
3. **屏幕连接**：
   - `RemoteScreenRepository.open` 在 Windows 上**只走 Sunshine**，不回退到 VNC / RDP。
   - Windows 主机不管 `streamEnabled` 开关是什么值，都视为请求高性能模式。
   - 连不上时，抛出带具体原因的 `RemoteScreenUnavailableException`。例如 UDP 不可达时，说明需要同一局域网，或者 Tailscale 加上放行 UDP 47998–48000。可以新增 `RemoteScreenProblem`，界面由 Claude 来接。
   - 去掉 P7a 在屏幕路径上加的 Windows 拒绝（`supportsRemoteScreen`、`requireScreenSupported`），**但 cua 路径保留拒绝**：`openCuaProcess`、`installCuaDriver`，以及计算机能力的入口。
4. **配对、启动、加密、证书**：`startStreamPairing`、`startSunshine`、`enforceStreamEncryption`、证书核对与固定，在 Windows 上都改用 `miffan.ps1`。Mac 专用的 `wake`、`open-settings` 在 Windows 上跳过，或返回明确的“不适用”。
5. **防火墙**（决策 4：App 经 SSH 修改，每次弹窗确认）：
   - 新增仓库接口：`checkSunshineFirewall(hostId, revision)` 返回风险和建议计划；`applySunshineFirewall(hostId, revision, plan)` 原样下发用户批准的计划，返回执行结果和备份路径。
   - 允许的来源网段由 App 提出：这台 Windows 当前所在的局域网网段，以及在检测到 Tailscale 网卡时加上 `100.64.0.0/10`。如果 `miffan.ps1` 目前提供不了这些网段，就补上，并把脚本版本号加 1。
   - 每次在 Windows 上打开屏幕之前先做一次 check。若 `firewall_model_ok` 为 false，就抛出新的 `RemoteScreenProblem`（例如 `FIREWALL_REVIEW_NEEDED`），并附上建议计划，**不要自动执行 apply**。确认弹窗由 Claude 来做。
6. **中文与剪贴板**：`writeStreamClipboard` 在 Windows 上调用 `miffan.ps1 clip`。
7. **地址候选**：Sunshine 的 UDP 候选地址（局域网、Tailscale）与 Linux 的逻辑一致。数据来源换成 Windows probe 的结果，或者 SSH 连接用的地址。

## 不做

- 不改 `ui/` 下的 Compose 文件和 ViewModel。需要的状态、字符串和问题类型写进笔记，由 Claude 来接。
- 不碰 cua，不实现 P7c。
- 不改 OpenSSH、RDP 的配置。

## 测试机与测试方式

- Windows 测试机：`/Users/chenxiansheng/miffan/.deps/windows-spike/p7-test.properties`，其中含测试专用私钥的路径。**不要读取或输出私钥内容。** 测试机的状态见 `P7_WINDOWS_PLAN.md` 末尾一节：Sunshine 加密为 2 / 2，防火墙已经是目标状态，Tailscale 地址 100.64.0.8，Headscale 允许 Mac 和手机访问 UDP 47998–48000。
- **端到端测试在模拟器上做**：`emulator-5560`，AVD `RikkaHub_API_35_16K`，不要碰 `emulator-5554`。
  - 把测试私钥导入调试版 App 的主机配置时，用 androidTest 或调试专用的测试入口。私钥临时推到模拟器后，测完立即删除，笔记里写明。
  - UDP 走 Tailscale 地址 100.64.0.8。Mac 的“本地网络”权限会挡住发往局域网的 UDP，详见 `research/WINDOWS_SPIKE.md` A3。
- **Sunshine 同一时间只支持一个串流**。开始串流前通知 Claude；测试结束后确认串流已经结束。
- 允许在测试机上做的：安装或升级 `%LOCALAPPDATA%\Miffan\bin\miffan.ps1`、配对一个新的测试客户端（名字带 `miffan-p7b2`）、串流、剪贴板写入（每次写入前先通知 Claude）、只读的防火墙检查。
- **不允许**在测试机上运行 `firewall apply`。apply 的接入用单元测试和模拟数据验证。
- 只能操作你自己打开的窗口。需要往桌面输入时，先通知 Claude，由用户把目标窗口切到前台。不要动用户自己的窗口。

## 验收

- 单元测试：Windows 分支的命令生成、解析、防火墙检查结果到问题类型的映射、只在用户批准后才 apply。
- 模拟器端到端，需截图并在笔记里写清步骤：
  1. Windows 主机经 SSH 自动安装 `miffan.ps1`；
  2. 配对；
  3. Sunshine 画面显示；
  4. 键盘和鼠标操作你自己打开的记事本；
  5. 经剪贴板粘贴中文。
- `./gradlew :app:testDebugUnitTest assembleDebug` 通过，Linux / macOS 的相关测试保持通过。
- 写 `docs/remote-screen/P7B2_NOTES.md`：设计、测试结果、需要 Claude 接的 UI（防火墙确认、Windows 设置卡片、错误文案）、已知限制。

## 交付与沟通

- 在本 worktree 的 `feature/p7b2-windows-screen` 分支提交，不要合并到 `feature/im-4.0`。
- 通知 Claude：先 `~/.local/bin/herdr pane send-text wJ:p1 "消息"`，**再** `~/.local/bin/herdr pane send-keys wJ:p1 enter`。只发 send-text 的话，消息会停在输入框里。

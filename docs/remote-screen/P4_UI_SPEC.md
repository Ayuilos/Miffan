# P4 UI：轻松模式的电脑入口、屏幕页、聊天展示与接入引导

背景见 `docs/REMOTE_SCREEN_PLAN.md`（“入口”“引导流程”“控制权与可见性”）与 `docs/remote-screen/P3_UI_NOTES.md`。专业模式的屏幕页、助手开关与工具展示已完成。本任务把同样的能力放进轻松模式（IM 外壳），并做“为伙伴连接一台电脑”的引导流程。数据层与引导流程的状态机已完成，接口见下文。

轻松模式的原则：像聊天软件，不暴露工作空间、主机、端点等专业概念；用“电脑”“伙伴”“屏幕”来说话。

## 边界

可以修改：

- `ui/im/thread/` 下的文件（聊天页、时间线、工具卡片、输入条）。
- `ui/im/ImPartnerProfilePage.kt`。
- `ui/im/computer/` 下新建页面与组件（`ComputerSetupVM.kt` 除外）。
- `ui/pages/extensions/workspace/screen/` 下新建可复用组件；可以把 `RemoteScreenPage.kt` 中的画布、键盘、横幅、失败面板拆成可复用的 composable，但专业模式的行为与外观保持不变。
- `RouteActivity.kt`：新增路由。
- `di/ViewModelModule.kt`：只允许为新页面登记已有 VM 的其他 key/参数形式（如需要）。
- `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`，新字符串前缀 `im_computer_`。

不要修改：`data/` 下的文件、`service/`、`RemoteScreenVM.kt`、`ComputerSetupVM.kt`、`AgentThreadVM.kt` 的现有行为（可以新增只读的派生状态，但不改现有函数）、`workspace` 模块、数据库。接口不够用时写进 `docs/remote-screen/P4_UI_NOTES.md`。

## 已有接口

- `PartnerComputers.observe(assistantId): Flow<PartnerComputer?>`（Koin 单例）。`PartnerComputer` 字段：`workspaceId`、`hostId`、`name`（电脑名）、`platform`、`screenEnabled`、`computerUseEnabled`、`showsEntry`（= `screenEnabled`）。伙伴未绑定或绑定的是本地工作空间时为 null。
- `ComputerSetupVM(assistantId)`（`koinViewModel { parametersOf(assistantId) }`），见 `ui/im/computer/ComputerSetupVM.kt` 的 KDoc。要点：
  - `state: StateFlow<ComputerSetupState>`：`step`、`task`（进行中的工作，非 null 时显示进度并禁用操作）、`error`、`hostKey`、`hostKeyChanged`、`probe`、`install`、`permissions`、`partnerView`（伙伴看到的截图）、派生的 `isMac`、`sessionReady`、`screenServiceReady`、`cuaReady`、`prepared`、`tested`。
  - `hosts`、`sshKeys`、`replacedWorkspaceName`（绑定会替换掉的现有工作空间名）。
  - 动作：`back()`、`addNewComputer()`、`chooseHost(id)`、`createAppKey(onCreated)`、`submitAddress(name, address, port, username, auth)`、`rereadFingerprint()`、`trustAndConnect()`、`reprobe()`、`cuaInstallCommand()`、`installCuaDriver()`、`continueAfterPrepare()`、`savedMacAccount()`、`saveMacAccount(username, password?)`、`checkPartner()`、`requestMacPermissions()`、`continueAfterTest()`、`bind()`、`dismissError()`。
- `RemoteScreenVM`（参数 `RemoteScreenArgs(workspaceId)`，同一页面内用 workspaceId 作为 Koin key）：`state`、`bitmap`、`frameVersion`、`cursor`、`controller`、`handBackToPartner()`、`takeControl()` 及输入函数。专业模式的 `RemoteScreenPage` 是用法示例。
- `AgentThreadVM(assistantId)`：`timelineState`、`generatingSegmentIds`、`errors`、`send(...)`、`stop()`、`answerToolApproval(item, id, approved)`。
- `ui/components/message/ComputerToolUI.kt`：`computerActionTitle`、`computerToolStatus`、`computerToolNeedsForegroundWarning`、`COMPUTER_TOOL_PREFIX`。可复用；如需改为 `internal` 以外的可见性或移动位置，可以调整，但不改变专业模式的显示。

## 1. 聊天页顶部的电脑入口

`AgentThreadPage` 右上角：

- `PartnerComputer?.showsEntry == true` 时，把“…”换成电脑图标（`HugeIcons.Computer`），无障碍描述“查看 <电脑名> 的屏幕”，点击进入伙伴屏幕页（第 2 节）。
- 否则保持现有“…”（进入伙伴资料页）。

## 2. 伙伴屏幕页（新路由 `Screen.PartnerScreen(assistantId)`）

一页同时是“看屏幕”和“和伙伴说话”。

- 顶部：返回、电脑名、连接状态与流量（同专业模式的副标题信息，可精简），以及键盘、触控模式、更多（重连、发送按键）按钮，复用专业模式的组件。顶部下方是控制权横幅（复用 `RemoteScreenControllerBanner`）。
- 中间：屏幕画布，交互与专业模式一致（触摸即接管）。
- 底部：对话条，包含：
  - 伙伴最近一条回复的前两行，或生成中的实时状态；电脑操作进行中时显示当前动作标题（例如“点击”“输入文字：Hello”）。点击这一区域返回聊天页并定位到该消息。
  - 待审批的电脑操作卡片（第 3 节的样式，紧凑版）。在此页点“允许”时，先调用 `handBackToPartner()` 再批准，因为批准就是把电脑交给伙伴。
  - 输入条：复用 `ThreadComposer`，发送到同一个伙伴的聊天。语音、附件可隐藏以保持简洁。
  - 对话条不应遮挡画布；画布区域随之收缩（软键盘弹出时同样）。
- 连接失败：复用 P2 的失败面板，但措辞面向轻松模式；“屏幕设置”类操作改为进入接入引导（第 5 节）。
- 离开此页时控制权自动交还（VM 已处理）。

## 3. 聊天中的电脑操作

只处理名称以 `computer_` 开头的工具。其他工具保持现状。

- **进行中卡片**：回复生成中且最近的工具是电脑操作时，用一张卡片代替通用的“正在处理”状态：
  - 标题“正在操作 <电脑名>”，带进行中指示。
  - 副标题为最近一个动作的标题（`computerActionTitle`）。
  - 本条回复中最后一张截图的缩略图（最大高度约 160dp，圆角，保持比例）。
  - 点击卡片进入伙伴屏幕页。
- **完成后的摘要**：回复结束后，如果这条回复用过电脑，在回复正文下方保留一行摘要“在 <电脑名> 上操作了 N 步”，带最后一张截图的小缩略图（点击用现有的图片预览打开）和“查看屏幕”入口。只读步骤（查看、列出、读取指南）不计入 N；若只有只读步骤，摘要为“看了看 <电脑名> 的屏幕”。理由：这是伙伴在用户电脑上做过什么的证据，不属于可以丢弃的过程细节。
- **审批卡片**：电脑操作待审批时，不用通用的“Enable a partner capability”：
  - 标题“伙伴想操作 <电脑名>”。
  - 正文为动作标题（例如“输入文字：Hello from Miffan”“点击”“打开应用：Firefox”）。
  - `computerToolNeedsForegroundWarning` 为真时，醒目提示“会切换到前台，打断你正在使用的窗口”。
  - 按钮“允许”“这次不要”，以及文字按钮“查看屏幕”。
  - 可展开查看原始参数（复用现有工具详情）。
- **拒绝与错误**：工具输出为 `refused` 时，在进行中卡片或摘要中显示“你接管了屏幕，伙伴已停下”；`error` 时显示错误样式，不显示原始堆栈。

## 4. 伙伴资料页

在“能帮你做”区域增加一行“操作电脑”：

- 未绑定电脑：说明“让伙伴看到并操作你的电脑”，按钮“连接电脑”进入接入引导。
- 已绑定（`PartnerComputer` 非 null）：
  - 显示电脑名。
  - 开关对应 `computerUseEnabled`。从关到开时，弹出与专业模式相同的确认说明（截图会发送给模型提供商等）。
  - 入口“查看屏幕”（`showsEntry` 时）与“重新检测或更换电脑”（进入接入引导）。
- 写入通过现有的 `ImPartnerVM.update { ... }`。

## 5. 接入引导（新路由 `Screen.ComputerSetup(assistantId)`）

全屏页面，顶部是返回（调用 `vm.back()`，在 `CHOOSE` 或 `DONE` 时退出页面）和步骤进度（不必显示步骤名，显示“第 n 步，共 m 步”或进度条即可；macOS 多一步）。每一步一个清晰的主按钮。`task != null` 时主按钮显示进度并禁用，系统返回也禁用。`error` 显示在当前步骤内，可关闭；错误信息复用现有的 `workspaceErrorMessage` 映射，`RemoteScreenUnavailableException` 按 P2 的失败原因给出说明。

1. **CHOOSE**：已知电脑列表（`hosts`，显示名称与 `用户名@地址`），点选后 `chooseHost`；以及“添加一台新电脑”（`addNewComputer`）。没有已知电脑时直接显示添加入口和一段简短说明：需要这台电脑开启 SSH、已登录桌面；Linux 需要 wlroots 系 Wayland（如 niri、Sway、Hyprland）或 X11，GNOME/KDE 暂不支持；macOS 需要开启“远程登录”和“屏幕共享”。
2. **ADDRESS**：显示名称、地址（提示可填 Tailscale 名称或 IP）、端口（默认 22）、用户名、登录方式：
   - “密码”：密码框。
   - “用 Miffan 生成的密钥”（推荐）：没有密钥时按钮“生成密钥”（`createAppKey`），已有时可选择。显示公钥与一键复制的命令：
     `mkdir -p ~/.ssh && printf '\n%s\n' '<公钥>' >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys`
     说明这条命令需要在那台电脑上执行一次。
   - 提交调用 `submitAddress`。
3. **VERIFY**：显示 `用户名@地址:端口`、`hostKey.algorithm` 与 `hostKey.sha256Fingerprint`（等宽字体），说明“请确认这和电脑上的指纹一致”，并给出在电脑上查看指纹的命令：
   `for f in /etc/ssh/ssh_host_*_key.pub; do ssh-keygen -lf "$f"; done`
   `hostKeyChanged` 时用错误色醒目警告“这台电脑的身份与上次不同”。主按钮“确认并连接”（`trustAndConnect`）；次按钮“重新读取”（`rereadFingerprint`）。
4. **PREPARE**：按 `probe` 展示检查清单，每行带状态图标（可复用 P2 环境面板的行组件与文案）：
   - 桌面会话：`sessionReady`；否则提示在电脑上登录桌面。
   - 屏幕服务：`screenServiceReady`。
     - macOS：提示在“系统设置 → 通用 → 共享”中打开“屏幕共享”。
     - Linux：按会话类型给出可复制的安装命令（同 P2）。
   - 剪贴板：可选项，不阻塞继续。
   - cua-driver：`cuaReady`。否则显示“安装”或“升级”按钮，确认对话框中写出 `cuaInstallCommand()` 的完整命令，并说明：
     - 会从 cua.ai / GitHub 下载并安装软件；
     - cua-driver 默认开启匿名遥测，可在其设置中关闭；
     - macOS 安装后需要授予权限。

     确认后调用 `installCuaDriver`，进行中显示“正在安装，可能需要几分钟”；结束后若 `install.success == false` 显示输出末尾（可折叠）。
   - 主按钮“重新检测”（`reprobe`）；`prepared` 时主按钮变为“继续”（`continueAfterPrepare`）。
5. **MAC_ACCOUNT**（仅 macOS）：说明屏幕共享需要用这台 Mac 的登录账户登录。用户名（默认 SSH 用户名）、密码。若 `savedMacAccount()` 非 null，可选择“使用已保存的账户”（`saveMacAccount(username, null)`）。提交调用 `saveMacAccount`。
6. **TEST**：
   - 上半部分：内嵌实时屏幕预览（`RemoteScreenVM`，workspaceId 取 `state.workspaceId`），可交互，以便用户在 Mac 上远程点掉权限弹窗。
   - 下半部分：伙伴检查。
     - 按钮“让伙伴看一眼”（`checkPartner`）。
     - macOS 且 `permissions?.complete == false`：列出缺少的“辅助功能”“屏幕录制”，给出按钮“在 Mac 上请求权限”（`requestMacPermissions`），并说明会在 Mac 上弹出系统对话框，需要在预览里点“允许”或到“系统设置 → 隐私与安全性”中为 cua-driver 打开，然后再点“让伙伴看一眼”。
     - `partnerView` 非 null 时显示“伙伴看到的画面”缩略图。
   - `tested` 时主按钮“继续”（`continueAfterTest`）。
7. **BIND**：
   - 说明将把这台电脑交给 <伙伴名>，并显示与专业模式相同的授权说明：截图会发送给当前模型的提供商；伙伴的操作以该电脑账户的权限执行；每次操作前都会询问你，可在伙伴资料页修改。
   - `replacedWorkspaceName` 非 null 时提示“这会替换 <伙伴名> 当前使用的 <名称>”。
   - 主按钮“交给 <伙伴名>”（`bind`）。
8. **DONE**：
   - 显示“已连接”与伙伴看到的画面缩略图。
   - 主按钮“去聊天”：回到聊天页，并在输入框预填“看看我的电脑屏幕上有什么”，不自动发送（`Screen.Thread(assistantId, text = ...)`）。
   - 次按钮“查看屏幕”。

## 验收

1. `./gradlew :app:assembleDebug --offline` 与 `:app:lintDebug --offline` 通过，无新增错误或新增编译警告。
2. 只在 `feature/remote-screen-ui` 上提交（先基于 `feature/remote-screen` 最新提交 `8a8155b2` 或更新的提交重建该分支），不要合并或推送。
3. 写 `docs/remote-screen/P4_UI_NOTES.md`：做了什么、没做什么、需要真机确认的行为、对接口的建议。
4. 不需要连接远程机器或模型；联调由主代理完成。

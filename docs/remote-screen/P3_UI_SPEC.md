# P3 UI：伙伴操作电脑的开关、控制权提示与工具展示

背景见 `docs/REMOTE_SCREEN_PLAN.md`。数据层、cua-driver 连接、`computer_*` 工具与控制权仲裁已完成，本任务只做 UI。

## 边界

可以修改：

- 助手设置中工作空间相关的页面（`ui/pages/assistant/detail/AssistantBasicPage.kt` 或实际承载 `workspaceShellEnabled` 开关的文件）。
- `ui/pages/extensions/workspace/screen/RemoteScreenPage.kt` 及同目录新文件。
- 聊天消息中工具调用的展示（`ui/components/message/ChatMessageTools.kt` 及同目录新文件）。
- `app/src/main/res/values/strings.xml`、`values-zh/strings.xml`，前缀 `computer_use_` 或 `workspace_screen_`。

不要修改：`data/` 下的文件、`service/ChatService.kt`、`RemoteScreenVM.kt`、`workspace` 模块、数据库与依赖注入。接口不够用时写进 `docs/remote-screen/P3_UI_NOTES.md`。

## 已有接口

- `Assistant.computerUseEnabled: Boolean`（默认 false）、`Assistant.computerUseApprovalRequired: Boolean`（默认 true）。修改方式与 `workspaceShellEnabled` 相同（通过现有的助手更新路径）。
- 工具名以 `COMPUTER_TOOL_PREFIX`（`computer_`）开头，例如 `computer_click`、`computer_get_desktop_state`、`computer_read_guide`。观察类工具不需要审批；动作类按 `computerUseApprovalRequired`；`delivery_mode: "foreground"` 与 `computer_bring_to_front` 总是需要审批（现有审批卡片流程会处理）。
- 工具输出：`UIMessagePart.Text`（含 cua-driver 文本、`{"status":"refused",...}` 或 `structured: {...}` 行）与 `UIMessagePart.Image`（JPEG 截图）。
- `RemoteScreenVM.controller: StateFlow<RemoteController>`（`IDLE` / `PARTNER` / `USER`）与 `handBackToPartner()`。用户在屏幕页的任何输入都会自动接管，离开屏幕页自动交还。

## 1. 助手设置

在工作空间权限区域（Shell 开关附近），仅当助手绑定的是**远程**工作空间时显示：

- 开关“允许伙伴操作电脑”（`computerUseEnabled`）。说明：伙伴可以查看并操作这台电脑的桌面（点击、输入、打开应用），你可以随时在屏幕页接管；需要远端安装 cua-driver 0.34 或更新版本（可在主机的“屏幕设置 → 检测环境”中安装或升级）。
- 首次打开该开关时弹出确认：截图会发送给当前模型的提供商；伙伴的操作以该电脑账户的权限执行；建议保留“操作前询问”。用户确认后才写入。
- 开关“操作电脑前询问”（`computerUseApprovalRequired`），仅在上一个开关打开时显示。说明：观察屏幕不需要询问；切到前台的操作总是会询问。

## 2. 屏幕页的控制权提示

在屏幕页顶部（工具栏下方、画布上方）按 `controller` 显示一条细横幅：

- `PARTNER`：“伙伴正在操作这台电脑”，带进行中指示。用户此时触摸画布会自动接管（VM 已处理），无需额外按钮。
- `USER`：“你正在操作，伙伴已暂停”，右侧按钮“交还给伙伴”，调用 `handBackToPartner()`。
- `IDLE`：不显示。

横幅不遮挡画布内容（画布区域相应收缩），出现与消失有简短过渡。

## 3. 聊天中的电脑操作展示

对名称以 `computer_` 开头的工具调用，替换通用的工具展示为紧凑的“电脑操作”样式：

- 标题用人话描述动作，按工具名映射，例如：查看屏幕（`get_desktop_state`）、查看窗口（`get_window_state`）、列出窗口/应用、点击、双击、右键、拖动、输入文字（显示被输入文字的前 40 个字符；参数名 `text`）、按键/快捷键（显示 `key` 或 `keys`）、滚动、打开应用（显示 `bundle_id`/`name`/`app` 中可用的一个）、读取操作指南。未知名称显示原工具名。
- 输出中有图片时，在标题下显示截图缩略图（最大高度约 160dp，保持比例，圆角），点击用现有的图片预览打开大图。只显示该次调用的最后一张图。
- 输出包含 `"status":"refused"` 时显示“用户已接管屏幕，伙伴已停止”；包含 `"status":"error"` 时显示错误样式。
- 展开详情时仍可查看原始参数与文本输出（复用现有的工具详情界面）。
- 审批待定的调用继续使用现有审批卡片；如果卡片能显示参数，确保 `delivery_mode: "foreground"` 时有一行醒目提示“会切换到前台，打断你正在使用的窗口”。

## 验收

1. `./gradlew :app:assembleDebug --offline` 与 `:app:lintDebug --offline` 通过，无新增错误或新增编译警告。
2. 只在 `feature/remote-screen-ui` 上提交（先基于 `feature/remote-screen` 最新提交变基或重建该分支），不要合并或推送。
3. 写 `docs/remote-screen/P3_UI_NOTES.md`：做了什么、没做什么、需要真机确认的行为、对接口的建议。
4. 不需要连接远程机器或模型；联调由主代理完成。

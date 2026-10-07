# P1 UI：专业模式屏幕页与主机屏幕设置

背景见 `docs/REMOTE_SCREEN_PLAN.md`。本任务只做 UI 与接线；数据层、会话引擎和 ViewModel 已完成，接口见下文。

## 边界

可以新建：

- `app/src/main/java/me/ayuilos/miffan/ui/pages/extensions/workspace/screen/RemoteScreenPage.kt`：屏幕页（可按需要再拆出同目录下的文件，例如画布、手势、按键栏）。
- `app/src/main/java/me/ayuilos/miffan/ui/pages/extensions/workspace/screen/RemoteHostScreenSettingsDialog.kt`：主机屏幕设置对话框。

可以修改：

- `RouteActivity.kt`：新增 `Screen.WorkspaceScreen(val id: String)` 路由与 `entry`，用 `koinViewModel<RemoteScreenVM>(parameters = { parametersOf(RemoteScreenArgs(id)) })` 取得 ViewModel（写法参照 `WorkspaceDetailVM`）。
- `WorkspaceDetailPage.kt`：远程工作空间的顶部栏增加“屏幕”按钮，位于终端按钮旁，导航到 `Screen.WorkspaceScreen(id)`。本地工作空间不显示。
- `RemoteWorkspaceDialogs.kt` 的 `RemoteHostCard`：溢出菜单增加“屏幕设置”，打开设置对话框。
- `WorkspaceVM.kt`：增加读取与保存屏幕设置的方法，委托给已注入的 `screenRepository`（`getConfig` / `updateConfig`）。
- `app/src/main/res/values/strings.xml` 与 `values-zh/strings.xml`：新增字符串，前缀 `workspace_screen_`。其他语言不用加。

不要修改：`workspace` 模块、`data/` 下的文件、`RemoteScreenVM.kt`、数据库与依赖注入。如果发现接口不够用，不要自己改，写进本目录的 `P1_UI_NOTES.md` 说明需要什么、为什么。

## 已有接口

`RemoteScreenVM`（`ui/pages/extensions/workspace/screen/RemoteScreenVM.kt`）：

- `state: StateFlow<RemoteScreenUiState>`：`Connecting`、`Connected(name, width, height)`、`Failed(error)`、`Closed`。`Failed` 的 `error` 为 `RemoteScreenUnavailableException` 时表示屏幕未启用或缺少密码，应引导用户打开设置。
- `bitmap: StateFlow<Bitmap?>` 与 `frameVersion: StateFlow<Long>`：画面写在同一个 Bitmap 里，每帧结束 `frameVersion` 递增。绘制时必须在绘制作用域内读取 `frameVersion`，让画布在每帧重绘；不要每帧复制 Bitmap。Bitmap 在分辨率变化时会被替换。
- `bytesReceived: StateFlow<Long>`、`metered: Boolean`。
- `notices: SharedFlow<RemoteScreenNotice>`：`TextNotTypable`、`ClipboardFailed(error)`、`RemoteClipboard(text)`。
- 输入，坐标均为 Bitmap 像素：`movePointer(x, y)`、`press(button, down)`、`click(x, y, button, count)`、`scroll(steps, horizontal)`、`key(keysym, modifiers)`、`typeText(text)`。按键常量在 `me.rerere.workspace.screen.RfbKeys`，修饰键为 `RemoteModifier`（`COMMAND` 在 macOS 上是 Command，其他平台是 Ctrl）。
- 控制：`setVisible(Boolean)`、`setMaxFps(Int)`、`reconnect()`。

`RemoteScreenRepository`：`getConfig(hostId): RemoteHostScreenConfig?`、`updateConfig(hostId, enabled, endpoint, auth, username, password)`。`password` 传 null 表示保留已存密码；认证选“无”时会删除已存密码；需要密码但从未保存过时会抛异常。

## 屏幕页

### 布局

- 顶部栏：返回、标题（主机名或 `Connected.name`）、副标题显示本次已用流量（如 `12.3 MB`）。操作按钮：输入模式切换（直接点按 / 触控板）、键盘开关、溢出菜单（帧率：省流 5、标准 10、流畅 20；重新连接）。
- 画布：黑色背景，画面按比例完整显示（Fit），支持双指缩放 1×–5× 与平移；平移时画面边缘不能被拖离视口。
- 键盘打开时，在软键盘上方显示按键栏（可横向滚动）：Esc、Tab、Ctrl、Alt、⌘（macOS）/ Super、Shift、方向键、Home、End、PgUp、PgDn、F1–F12、Delete。Ctrl/Alt/⌘/Shift 是一次性粘滞修饰键：点亮后作用于下一次按键或输入的单个字符，然后自动熄灭。
- 页面可见期间保持屏幕常亮。

### 手势

直接点按模式（默认）：

| 手势 | 动作 |
| --- | --- |
| 单击 | 在手指位置左键单击 |
| 双击 | 左键双击 |
| 长按 | 右键单击 |
| 长按后不松手拖动 | 左键拖拽（按下、移动、松开） |
| 单指拖动 | 平移画面（未放大时无动作） |
| 双指捏合 | 缩放 |
| 双指拖动 | 滚轮滚动（按距离换算步数，方向与手机滚动习惯一致） |

触控板模式：

| 手势 | 动作 |
| --- | --- |
| 单指拖动 | 按相对位移移动远端指针，带加速 |
| 单击 | 在当前指针位置左键单击 |
| 双指单击 | 右键单击 |
| 双击后不松手拖动 | 左键拖拽 |
| 双指拖动 | 滚轮滚动 |
| 双指捏合 | 缩放，放大时视口跟随指针 |

触控板模式下在画布上绘制本地指针标记，位置由页面自己维护（Bitmap 坐标）。

### 键盘输入

用一个不可见的文本输入框接收软键盘输入：提交的新文字调用 `typeText`，删除调用 `key(RfbKeys.BACKSPACE)`，回车调用 `key(RfbKeys.RETURN)`。有粘滞修饰键时，输入的单个字符改为 `key(char.code, modifiers)`。输入法组合中的文字（拼音未上屏）不发送。

### 状态与提示

- `Connecting`：居中进度指示。
- `Failed`：错误信息与“重试”按钮；若是 `RemoteScreenUnavailableException`，按钮改为“屏幕设置”，直接在本页打开设置对话框，保存后调用 `reconnect()`。
- `Closed`：“连接已断开”与“重新连接”。
- `metered` 为 true 时，首次进入显示一次提示：正在使用移动网络，查看画面会消耗流量。
- `TextNotTypable`：提示该平台暂不支持输入这类文字；`ClipboardFailed`：提示粘贴失败；`RemoteClipboard`：把文字复制到手机剪贴板并提示。
- 生命周期：`ON_START` 调用 `setVisible(true)`，`ON_STOP` 调用 `setVisible(false)`。

## 主机屏幕设置对话框

字段：

- 启用屏幕（开关）。
- 连接方式：TCP 端口（默认 5900，1–65535）或 Unix 套接字路径（必须以 `/` 开头）。对应 `RemoteScreenEndpoint.Tcp` / `Unix`。
- 认证：无 / VNC 密码 / macOS 账户（`RemoteScreenAuth`）。选 macOS 账户时显示用户名（留空使用 SSH 用户名，占位符显示 SSH 用户名）。
- 密码：认证不是“无”时显示，隐藏输入。已保存过密码时占位符为“已保存，留空则不修改”。
- 说明文字：macOS 需在“系统设置 → 通用 → 共享”中打开“屏幕共享”，选择 macOS 账户认证；Linux 需运行只监听本机的 VNC 服务（如 `wayvnc 127.0.0.1 5900` 或 `x11vnc -localhost`），连接经过 SSH，不需要对外开放端口。

保存失败时在对话框内显示错误，不关闭对话框。

## 风格

参照同目录现有页面（`RemoteWorkspaceTerminalPage.kt`、`WorkspaceDetailPage.kt`、`RemoteWorkspaceDialogs.kt`）的组件、图标库（HugeIcons）与字符串写法。不需要新的设计语言。

## 验收

1. `./gradlew :app:assembleDebug --offline` 通过，无新增编译警告。
2. 只在你自己的分支 `feature/remote-screen-ui` 上提交，提交信息用英文、首行祈使句，结尾带协作者行（参考 `git log`）。不要合并、不要推送。
3. 完成后在 `docs/remote-screen/P1_UI_NOTES.md` 写下：做了什么、没做什么、哪些行为需要真机确认、对 ViewModel 接口的建议。
4. 真机/模拟器联调由主代理完成，你不需要连接远程机器。

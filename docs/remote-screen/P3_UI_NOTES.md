# P3 UI 实现说明

## 已完成

- 在已重建的 `feature/remote-screen-ui` 上实现，起点 `67f492dd`，包含 P2 后续修复及 P3 数据层。只改动 spec 允许的 UI、同目录新文件和英文/简体中文资源。
- 助手绑定远程工作空间时，在 Shell 权限附近显示“允许伙伴操作电脑”；说明点击、输入、打开应用、屏幕页接管及 cua-driver 0.34 的安装/升级入口。本地工作空间或未绑定时隐藏。
- 开启前显示截图发送给当前模型提供商、电脑账户权限及保留逐步询问的说明；确认后才通过现有 `onUpdate → AssistantDetailVM.update` 保存。取消/返回不写入；未确认状态支持旋转，助手或工作空间绑定变化时重置。启用后才显示“操作电脑前询问”，说明观察免审批、前台操作始终审批。
- 顶部工具栏下增加控制权横幅，位于布局中、不覆盖画布：PARTNER 显示进行中指示；USER 显示暂停说明及交还按钮；IDLE 隐藏。出现/消失用 150ms 过渡，退出动画保留上一条消息，支持无障碍状态播报。状态与交还调用均来自现有 VM。
- 所有 `computer_` 工具采用电脑图标和动作标题：查看屏幕/窗口、列出窗口/应用、点击/双击/右键/拖动、输入文字、按键/快捷键、滚动、打开应用、读取指南及切到前台。未知工具显示原名。输入文字保留前 40 个 Unicode 码点并标记省略，不切断 UTF-16 代理对；按键兼容 `key`、字符串或数组 `keys`，应用兼容 `bundle_id` / `name` / `app`。
- 内联只显示该次输出的最后一张截图，最大高度 160dp，保持比例并裁圆角；点击复用 `ZoomableAsyncImage` 的大图预览。工具详情复用默认 BottomSheet，保留参数及全部原始文本/图片。
- 从独立 JSON 文本、混合输出中的 JSON 行及 `structured:` 行读取 `status`。`refused` 显示用户接管提示；`error` 使用错误图标、标题色和错误容器提示。不把正文中的内嵌 JSON 示例当成状态。
- 保留原有待审批的批准、拒绝和拒绝原因流程；`delivery_mode: foreground` 和 `computer_bring_to_front` 待审批时均显示醒目的前台打断提示，不改变数据层审批规则。
- 新增 29 组 `computer_use_` / `workspace_screen_` 英文和简体中文资源。其他语言回退到英文；沿用 P1/P2 对新增英文键逐键标注 MissingTranslation 的方式，不修改其他语言或全局 lint 配置。

## 没做与当前限制

- 未修改数据层、ChatService、RemoteScreenVM、workspace 模块、数据库或依赖注入；不新增远端调用、控制权状态机、审批策略或 P4 的聊天屏幕入口。
- 未连接远程电脑、模型、模拟器或真机，未执行真实桌面操作。
- Assistant 没有独立的首次授权记录，因此每次从关闭变为开启都会确认；已开启的助手重新进入设置不会再次弹窗。关闭能力不会改动保存的审批偏好。
- 手势接管和离页交还沿用既有实现。源码中画布 ACTION_DOWN 仅更新手势状态，单击确认或远端输入发生时才调用 VM；仅按住、点击黑边或放大后的本地拖动画布不保证立即接管。横幅准确反映现有 controller，不通过伪造指针输入提前抢占控制权。
- controller 是逐次动作的控制状态，不代表完整模型任务生命周期。交还后恢复后续动作的时机、进行中动作是否可中断，由现有数据层决定。

## 验证

- `./gradlew :app:assembleDebug --offline`：通过（1m 7s）。逐项比对编译警告位置与新增/修改行，没有新代码警告；现有 Navigation3 marker、Coil opt-in、空值检查与弃用警告保持原样，包括 AssistantBasicPage 原第 455 行。
- `./gradlew :app:lintDebug --offline`：通过（5m 18s），0 errors / 359 warnings / 6 hints。核对报告的实际文件路径与修改行，没有新增 UI/资源行的 lint 报告。
- 五项针对性 JVM 检查通过（14s）：混合/多行/structured 状态识别及错误优先级、指南内嵌示例/残缺/非字符串状态容错、40 字符 emoji 与换行预览、按键/应用 schema 变体和未知工具回退、两种前台警告路径。测试来源与 Gradle init script 位于 `/tmp/remote-screen-p3-tests`、`/tmp/remote-screen-p3-test.init.gradle`，未将范围之外的测试或配置文件加入提交。
- 测试命令：`./gradlew :app:testDebugUnitTest --offline --no-configuration-cache -I /tmp/remote-screen-p3-test.init.gradle --tests me.ayuilos.miffan.ui.components.message.ComputerToolUITest`。没有调用 SSH 或模型。
- Universal 与 arm64-v8a Debug APK 均通过 `apksigner verify`。
- XML 解析、重复资源名、占位符及 `git diff --check` 通过。使用 locale-tui 的校验/Android 转义函数与临时写入器处理限定的两种语言，未下载依赖。

## 需要主代理真机确认

1. 远程/本地/未绑定时开关可见性；开启确认、取消、返回、旋转及保存后重进；审批偏好按助手隔离且正确持久化。
2. PARTNER → USER → IDLE 的横幅、交还按钮、短动作之间的过渡；小屏/横屏/软键盘显示时画布尺寸与指针坐标。确认触摸和键盘接管的实际时间，尤其纯按住或本地平移手势。
3. 点击、输入、按键与应用参数的真实 schema、长文本与 emoji 标题；多个截图只显示最后一张，横/竖屏截图比例、圆角、160dp 限制与大图预览。
4. 观察类免审批、动作逐步审批及关闭逐步审批后的行为；foreground 和 bring_to_front 始终询问且打断提示可见，批准/拒绝/拒绝原因能正常继续既有聊天流程。
5. 接管后的真实 refused 结果、cua-driver 错误及混合文本/structured 输出；详情保留完整参数、文字和全部截图。
6. 返回离页、进入其他页面、旋转、应用前后台切换时 controller 是否符合期望；实际用户接管能否阻止下一步动作，当前执行中的动作如何收尾。

## 对接口的建议

- 为 VM 提供不发送远端事件的显式 `takeControl()`，由画布首次按下调用，覆盖触控板的延迟单击、本地平移及黑边触摸；目前私有 `userInput()` 只能通过会发送输入的 API 间接触发，不宜在 UI 中伪造操作。
- 若希望只在首次启用时确认，增加授权说明版本/确认记录，并明确更换模型提供商或绑定电脑后的重确认策略；本次每次启用均确认以保证第一次授权不会遗漏。
- 当前离页交还由 VM 的 `onCleared()` 承担。如果屏幕页进入后台栈但 VM 保留，应明确是否立即交还，并提供可区分旋转、覆盖页面、真正离页的生命周期接口。
- 可在输出中单独暴露状态与当前步骤标题，避免以后依赖文本协议判断错误/接管；若要显示任务级持续进度，需增加与 controller 分离的任务活动状态。当前按既有 JSON 状态字段与逐动作 controller 展示。

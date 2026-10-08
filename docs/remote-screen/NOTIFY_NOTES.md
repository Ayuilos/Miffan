# 轻松模式通知修复说明

## 根因与排查

- 基线为 `0eccd82d`。轻松模式和专业模式共用 `ChatService` 的生成通知事件；轻松模式的队列经 `ConversationSession.takeNextQueuedMessage → sendMessageNow → launchGenerationJob` 获取同一前台服务，没有发现绕过通知事件或前台服务获取的独立发送路径。
- `DisplaySetting.enableNotificationOnMessageGeneration` 和 `enableLiveUpdateNotification` 原本均默认 false，因此未保存设置的新用户不会收到实时动态或回复完成通知。
- `ChatGenerationEnded` 原本没有审批信息，等待工具授权也走普通完成通知，且受回复通知开关限制。
- 原通知名称只按 `useAssistantAvatar` 选择，轻松模式可能显示模型名而非伙伴名。现改为轻松模式始终使用伙伴名，专业模式保留原选择规则。
- 原实时动态只在后台收到新的流片段时发布；前台已收到内容、退到后台后流暂时停顿时，不会立即发布。现保留各活跃会话的最新片段，在进程 ON_STOP 时按通知开关发布最新状态，并初始化当前生命周期状态。
- 当前 live update 与前台服务共用 ID **2002**，完成通知 ID 为 **1**；不是规格中沿用旧实现描述的 live ID 1。保留当前基线的共享前台通知设计。
- `POST_NOTIFICATIONS` 在首次轻松模式流程中没有主动请求。已存在的通知设置页在用户开启回复通知开关时请求权限；Web 服务设置也有权限入口。默认改为 true 并不能代替系统权限授权。按范围约束未新增界面或弹窗，请 UI 侧补充首次权限流程。

## 改动

- `data/datastore/PreferencesStore.kt`：两个通知默认值改为 true。`JsonInstant` 使用 `encodeDefaults = true`，已有 JSON 中明确保存的 true/false 保持原值；缺失字段使用新默认，不迁移或覆盖用户选择。
- `MiffanApp.kt`：新增 `chat_approval` 高重要性通知渠道。
- `data/event/AppEvent.kt` / `service/ChatService.kt`：结束事件携带待审批工具；成功结束先完成原有保存，再发布结束事件，避免观察数据库时读到上一轮状态。取消和错误仍走仅清理的结束事件。不改变生成器、工具定义或审批语义。
- `service/ApprovalNotification.kt`：纯函数生成摘要。Shell 只取第一行、最多 80 个 Unicode 码点；电脑操作使用历史电脑名，回退到主机标签及通用电脑名称，共用动作标题；其他工具显示工具名，多个审批附加剩余数。每个会话使用稳定负整数 ID，避开完成/前台服务的正整数 ID。
- `service/ChatNotificationManager.kt`：后台等待审批时替代完成通知，不受回复通知开关限制，仍通过现有通知工具检查权限。无批准/拒绝按钮，保留原 conversationId 点击路由与自动消除。通知状态与生命周期均在主线程串行处理。
- `data/repository/ConversationRepository.kt` / `di/AppModule.kt`：仅对等待审批的会话观察 Room 持久化变化；批准、拒绝、作答、聊天回复结算、停止、分支切换或删除后不再有 pending 时清除通知并停止观察。观察不依赖会话缓存是否仍存在，也不加入逐 token 的生成热路径。剩余审批变化时更新摘要。
- `utils/ComputerActionTitle.kt`：从 UI 文件移出动作标题数据与纯函数，服务不再依赖 Compose。`ComputerToolUI.kt` 仅移除原实现并导入新函数；`ThreadComputerActions.kt` 仅更新该 import。其他 Compose 界面未修改。
- 六个 `values*/strings.xml`：按规格增加六组通知文案，英文逐键标记 MissingTranslation；其余语言保持既有语气及占位符。
- `service/ApprovalNotificationTest.kt`：新增八项 JVM 检查，覆盖 Shell/Unicode 截断、电脑动作含/不含详情与名称回退、通用工具、多项审批、异常参数、各种结算/分支状态、ID、默认值和已保存选择、六种语言格式化。

## 验证

- `./gradlew :app:testDebugUnitTest --offline`：通过，588 项，0 failures / 0 errors / 0 skipped（1m 15s）；新增 8 项全部通过。
- `./gradlew :app:assembleDebug --offline`：通过（50s）。
- 模拟器 `emulator-5560`：经面板批准 adb 权限后，将本次 Debug APK 安装到 `me.ayuilos.miffan.app.debug`（保留数据）。设备为 API 35、轻松模式，原本通知权限未授予，两个通知开关均明确保存为 false。这些已有 false 按要求不会自动变成 true。
- 设备验证**未完成**：临时授予通知权限、移除两个开关字段以测试新默认值；为避免电脑工具初始化访问远端，临时关闭测试伙伴的 computerUse，Shell 始终保持需要授权。进入聊天/输入消息时模拟器持续无响应，`uiautomator` 返回 null root，截屏和 adb shell 多次长时间阻塞；日志出现 `Application Not Responding`、`user request after error` 和 `crashed quickly`。重连及保留数据的模拟器重启后仍重现。尚无法确定 ANR 的具体原因，不能据此断言与本次改动有关或无关。
- 未成功发送测试消息，未触发授权卡片，未点击 Allow，未在用户电脑上执行任何命令。因此尚无 live update 渠道/promoted 请求/流式更新/结束消除，以及后台审批通知/点击路由/拒绝消除的端到端通过证据。这些需在设备恢复后补验；ColorOS 流体云实际呈现还需在对应系统上确认。
- 临时设置已还原并回读确认：两个通知开关 false、computerUse=ask、Shell 审批开关 true。`POST_NOTIFICATIONS` 已撤销，`dumpsys package` 回读确认 `granted=false`。
- 未执行 Git 写操作或提交。

## Claude 复核（2026-10-08，emulator-5560 冷启动后）

- 之前的“ANR”是模拟器虚拟机整体僵住（qemu CPU 0%，所有 adb shell 阻塞），冷启动（-no-snapshot-load）后恢复，与本次改动无关。
- 按用户路径在“我的 → 通知”打开两个开关，系统弹出通知权限请求并允许。
- 后台生成：实时动态通知（id 2002，channel chat_live_update）从“Generating response...”更新到“Thinking...”，结束后换成回复完成通知（id 1，内容为回复开头）。
- 后台等待授权：约 12 秒后出现 chat_approval 通知，标题 “Default Assistant needs your OK”，内容 “Run command: uname -a” / “Run command: whoami”。点通知进入伙伴聊天并定位到卡片。
- 从桌面重新打开 App（不点通知）后点 “Not this time”，授权通知 1 秒内被取消。全程未点 Allow。
- 结论：用户已保存为 false 的开关不会因新默认值改变（encodeDefaults），需要用户手动打开；轻松模式首次使用时没有申请通知权限，留给 UI 侧处理。

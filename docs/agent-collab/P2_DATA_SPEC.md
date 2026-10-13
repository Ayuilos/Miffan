# P2 数据规格：消息来源、主人权限、非主人发起的回合

背景见 `docs/AGENT_COLLAB_PLAN.md` 第 2 阶段。核心原则是**身份对等，权限不对等**：伙伴的话和系统事件可以作为 USER 消息进入会话，但只有主人（用户）的消息带有权限。

界面由 Claude 负责：时间线事件行的渲染 `ThreadEventLine` 已经完成，`ui/` 下的文件**不要修改**（下文点名允许的除外）。

## 契约（已提交，签名不要改）

- `data/model/MessageOrigin.kt`：`MessageOrigin.Partner` / `MessageOrigin.Event`。
- `MessageNode.origin: MessageOrigin? = null`：`null` 表示主人。
- `Conversation.ownerTrigger(): MessageRef?`：方法体目前是 `TODO()`。
- `QueuedMessage.origin`，以及 `ChatService.sendMessage(..., origin)`：参数已加，**还没接通**。
- `ThreadService.deliverFrom(origin, assistantId, content, segmentId)`：`TODO()`。
- `TimelineItem.Event(segmentId, node, at, origin)`：`ThreadTimeline.build` 还不会产出它。

## 任务

### 1. 持久化

- 在 `MessageNodeEntity` 中新增 `origin` 列，写法和 `replyTo` 一致：JSON 字符串，空字符串表示 `null`。两个方向的映射都要写。
- 数据库 33 → 34：写迁移，更新 schema，补迁移测试（参照现有的迁移测试）。
- 已有数据不需要回填：没有来源就是主人。
- 检查所有序列化 `MessageNode` / `Conversation` 的路径，确认 `origin` 不会丢失：备份恢复、导出、web 接口。web-ui 暂时可以把事件消息显示成普通用户消息，但字段不能丢。

### 2. `ownerTrigger()`

- 取当前路径（`currentMessageNodes`）上最后一个 role 为 USER 的节点。它的 `origin == null` 时返回其 `MessageRef`，否则返回 `null`。
- 当前路径上没有 USER 节点时返回 `null`。

### 3. 权限判断改造

审计所有“把最近一条 USER 消息当作触发者 / 授权者”的地方，改用 `ownerTrigger()`。至少包括：

- **`ChatService` 构造自我修改工具的地方**（约 1070 行）：`trigger` 改用 `ownerTrigger()`，`triggerText` 取对应节点的文本。触发者为 null 时，工具本来就会拒绝。
- **`ChatService.extractMemories`**：`ownerTrigger() == null` 时整轮跳过自动记忆整理；有触发者时，`RevisionOrigin.trigger` 用它。
- **`GenerationHandler`**：
  - 不要再在内部从 messages 推导记忆的 `RevisionOrigin.trigger`，改由调用方传入；
  - 新增参数 `ownerTurn: Boolean = true`；
  - `ownerTurn == false` 时不提供记忆工具；
  - 调用方（`ChatService` 生成入口）根据 `ownerTrigger()` 计算这两个值。
- **主人消息顺带回答待审批卡的逻辑**：`sendMessage` 中的 `answersApproval`、`finishInterruptedPendingTools(repliedInChat = true)`、`ThreadService.send` 中“有待审批就强制进入该分段”的逻辑，**只对主人消息生效**。非主人消息在有待审批时排队等待，绝不能替主人把审批卡结算掉。
- 其余命中的地方，比如 `ThreadContext`、`SmartSegmentRouter`、`PromptInjectionTransformer`、`TimeReminderTransformer`、`MemoryExtraction`：逐个判断是否涉及权限。只是取文本的地方保持不变；涉及权限的改掉。在交付说明里列出你判断过的每一处和结论。

### 4. 接通 `origin`

- `sendMessage(origin)` → `QueuedMessage.origin` → 追加节点时写入 `MessageNode.origin`。`Conversation.appendMessage` 可以增加一个 `origin` 参数。

### 5. `ThreadService.deliverFrom`

- 不经过话题路由。分段的选择顺序：传入的 `segmentId` → 该伙伴最新一个未关闭（`threadClosedAt == 0`）的分段 → 新建分段。
- 需要读取分段时，可以给 `ThreadService` 注入仓库，并更新 DI。
- 投递方式与 `deliver` 相同：`openThreadSegment`，然后 `sendMessage(immediately = false, origin = origin)`，然后 `resumeMessageQueue`。生成要能在后台保活，沿用 `answer = true` 时的前台服务。
- 返回所用分段的 id。

### 6. 时间线与消息列表

- **`ThreadTimeline.build`**：
  - `origin` 为 `Event` 的 USER 节点，产出 `TimelineItem.Event`，不产出 `Message`；
  - `origin` 为 `Partner` 的节点不出现在 IM 时间线中（第 3 阶段它们只存在于伙伴私聊的视角分段里）；
  - 事件节点之后的回复不显示“你问：”引用条，并行批次的引用规则也不把事件当作用户消息；
  - 事件条目会打断同一发送者的分组。
- **`ThreadListSummaries.summarize`**：需要知道消息来源，可以改成接收节点，或接收“消息 + 来源”，相应修改 `ConversationRepository.getRecentMessages` 或新增方法。
  - 最后一条是事件时：预览显示 `origin.summary`，`fromUser = false`；
  - 未读规则不变：只数助手回复。
  - 这里允许你修改 `ImHomeVM` 中调用 `summarize` 的那一处。
- **通知**：确认事件触发的回复在 app 处于后台时，会像普通回复一样走 `ChatGenerationEnded`，发出完成通知。如果因为“最后一条用户消息”之类的假设导致不发，修复它。

### 7. Debug 触发器（只在 debug 构建中存在）

- 在 `app/src/debug` 中新增一个 `BroadcastReceiver`，Action 为 `me.ayuilos.miffan.debug.DELIVER_EVENT`。
- extras：
  - `assistant`：UUID，缺省时取第一个伙伴；
  - `text`：模型读到的内容；
  - `summary`：时间线上显示的那一行；
  - `segment`：可选。
- 收到后调用 `ThreadService.deliverFrom(MessageOrigin.Event("debug", summary), ...)`。
- 只在 debug 的 manifest 中注册，release 构建里不能存在。把 adb 命令写进交付说明。

## 测试

- `ownerTrigger()`：主人消息、事件消息、伙伴消息、没有 USER 消息、分支切换后的当前路径。
- 自我修改工具：事件触发的回合拿到的 trigger 为 null。
- 记忆：事件回合跳过自动整理，`GenerationHandler` 不提供记忆工具。
- 审批：有待审批时，事件消息进入队列，不结算审批卡。
- `deliverFrom` 的分段选择。
- 时间线：Event 条目、Partner 节点被隐藏、引用条被抑制、分组被打断。
- 消息列表预览。
- `MessageNode` 序列化往返；数据库迁移 33 → 34。

## 文件归属

除下面两处外，你负责数据、服务、DI、debug 源集与测试：
- `ui/` 下只允许修改 `ImHomeVM` 中调用 `summarize` 的那一处；
- 契约中的签名不要改。

## 验收

- 定向测试全部通过；数据库迁移测试在模拟器上通过（`emulator-5560` 正在运行，可以使用）。那台模拟器上的 debug app 有手工准备的测试数据，**不能被卸载或清空**：只跑迁移相关的测试类，并加上 `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`，避免测试结束后 AGP 卸载 app。
- `./gradlew :app:assembleDebug`、`:app:lintDebug` 通过。
- 在分支 `feature/origin-data` 上提交，不要提交到 `feature/im-4.0`。
- 写 `docs/agent-collab/P2_DATA_NOTES.md`：审计清单与结论、迁移、adb 触发命令、测试列表。

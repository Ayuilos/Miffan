# P4 数据规格：群聊，以及私聊里的审批

背景见 `docs/AGENT_COLLAB_PLAN.md` 的“群聊（2026-10-12 定）”和第 4 阶段，第 3 阶段的实现见 `P3_DATA_NOTES.md`。界面由 Claude 实现，`ui/` 下的文件**不要修改**，下文明确允许的除外。

## 契约（已提交，签名不要改）

- `PartnerChat.hostId`
- `PartnerChatMessage.mentions`
- `PartnerChatSummary.unread`
- `PartnerChatApproval`
- `PartnerGroupLimits`
- `PartnerChatService` 新增的方法：
  - `observeApprovals` / `answerApproval`
  - `createGroup` / `renameGroup` / `setMembers` / `setHost` / `deleteGroup`
- `DefaultPartnerChatService` 里对应的方法目前是 `TODO()`，由你实现。

## 任务

### 1. 私聊和群聊里的审批（先做，同时修补第 3 阶段的缺口）

- **`observeApprovals(chatId)`**：该聊天所有成员的视角分段里，状态为 Pending 的工具调用，按出现顺序排列。
- **`answerApproval`**：先加载该成员的视角分段，再调用 `ChatService.handleToolApproval`。行为与 IM 时间线里的审批卡一致，包括审批记录。
- **审批通知**：
  - 会话带 `chatId` 时，点击通知要打开 `Screen.PartnerChat(chatId)`。
  - 允许你修改通知的 PendingIntent，以及 `RouteActivity` 里“intent → 打开哪个页面”的映射代码，只限这一处。
  - 通知标题用聊天的标题：群用群名，私聊用“小A · 小B”。
- 第 3 阶段里，审批挂起的那一轮会一直等待，这个行为保持；现在用户有地方点了。

### 2. 存储（数据库 35 → 36）

- `partner_chat` 新增列 `host_id`，可空。
- `partner_chat_message` 新增列 `mentions`：JSON 数组，默认 `[]`。
- 写迁移、更新 schema、补迁移测试。和前两阶段一样：**不能卸载或清空 emulator-5560 上的 app**，只跑迁移测试类，并加上 `leaveApksInstalledAfterRun`。

### 3. 建群与管理

- **`createGroup`**：
  - 2～6 个不重复、并且存在的伙伴，否则抛 `IllegalArgumentException`；
  - kind 为 GROUP；
  - `hostId` 取第一个成员；
  - title 先 trim，空白时取成员名用“、”连接，最长 24 个字符。
- **`setMembers`**：
  - 人数规则同上；
  - 移除成员时，停止并删除它的视角分段；
  - 主持被移除时，由新成员列表的第一个成员接任；
  - 新加入的成员从加入后的消息开始同步（不补发历史）。
- **`setHost`**：必须是现有成员。
- **`deleteGroup`**：删除群、成员、消息和视角分段，并从 `chatOrganization` 中去掉群 id。
- **删除伙伴时**：把它从所有群中移除；群里剩下不足 2 个伙伴时，删除整个群。

### 4. 发言调度（只适用于 GROUP；DIRECT 保持第 3 阶段的行为）

- **轮次**：你每发一条消息开启新的一轮。轮内计数：伙伴最多发 `MESSAGES_PER_ROUND` 条，同一成员最多被叫起 `TURNS_PER_MEMBER` 次。超过上限就静默停止，不再叫人。
- **`sendFromOwner(chatId, text, mentions)`**：
  - 存储时 `mentions` 写进消息；
  - 有 mentions：这些成员**同时**开始发言；
  - 没有 mentions：由主持发言。
- **伙伴回复里的 @**：
  - 解析回复文本里的 `@名字`，按成员名匹配，名字长的优先；也要识别对主人的 @，包括 `@主人` 和主人昵称；
  - 解析结果写入 `mentions`（主人对应 `OWNER_PARTICIPANT_ID`）；
  - 被 @ 的其他成员同时开始发言，计入本轮的上限；
  - 成员 @ 自己不算。
- **成员正在发言时又被叫到**：排在当前这一轮之后。开始时同步进来的新消息已经包含叫它的那条，所以不需要额外传参。
- **`replyTo`**：由谁的消息叫起来的，就填那条消息。主持或被你 @ 的成员，第一条回复的 `replyTo` 是你那条消息。
- **群视角的系统提示**（通过 `threadContext` 注入），要说清楚：
  - 这是群“<群名>”，成员有谁、各自一句话介绍；
  - 谁是主持；
  - 主人没有 @ 人时由主持接，主持可以自己回答，或在回复里写 `@名字` 请成员处理；
  - 需要别人帮忙时写 `@名字`；要提醒主人时写 `@主人`；
  - 不要 @ 自己，也不要为了客套而 @ 别人。
- 群视角里仍然提供 `list_contacts` / `send_message`，伙伴可以私下联系其他伙伴，规则同第 3 阶段。

### 5. 未读与通知

- **`PartnerChatSummary.unread`**：
  - GROUP：`ThreadListState` 中 `readAt[chatId]` 之后的伙伴消息条数；
  - DIRECT：始终为 0。
- **GROUP 的伙伴消息满足以下任一条件时发通知**，否则不发：
  - `mentions` 包含主人；
  - `replyTo` 指向主人的消息。
- 通知规则：
  - app 在前台时不发；
  - 遵守现有的“生成完成通知”设置；
  - 标题为群名，正文为“<伙伴名>：<去掉 Markdown 的文本>”；
  - 点击打开 `Screen.PartnerChat(chatId)`。
- 后台保活沿用第 3 阶段的 `GenerationKeepAlive`。

## 测试

- 审批：`observeApprovals` 能看到视角分段里的 Pending；`answerApproval` 后这一轮继续；通知的跳转目标正确。
- 迁移 35 → 36。
- 建群和管理的参数校验、主持接任、删除群、删除伙伴后的级联。
- 调度：
  - 你没有 @ 人时由主持接；你 @ 多人时同时开始；
  - 伙伴回复里的 @ 会叫起被 @ 的成员；
  - 每轮 8 条、每人 2 次的上限；
  - 成员 @ 自己会被忽略；
  - 对主人的 @ 能被识别；
  - `replyTo` 填写正确。
- 未读与通知判定，用可注入的通知接口 fake。

## 验收

- `./gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 通过；迁移测试在 emulator-5560 上通过。
- 用真实模型在模拟器上实测（网关已配好）：
  - 建一个 3 个伙伴的群，你发一句不 @ 人的话，确认主持接；
  - 让主持 @ 一个成员分工，确认被 @ 的成员发言。
  - 建群可以用临时的 debug 广播或测试代码完成，不要改 UI。
  - 把实测结果写进说明。
- 提交到 `feature/group-chat-data`，写 `docs/agent-collab/P4_DATA_NOTES.md`。
- 有事实性问题直接在你的输入框里问。

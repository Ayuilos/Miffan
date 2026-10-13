# P3 数据规格：伙伴私聊

背景见 `docs/AGENT_COLLAB_PLAN.md` 第 3 阶段与“架构”一节。原则：**身份对等，权限不对等**。界面全部由 Claude 实现，`ui/` 下的文件**不要修改**。

## 契约（已提交，签名不要改）

- `data/model/PartnerChat.kt`：
  - `OWNER_PARTICIPANT_ID`、`PartnerChat`、`PartnerChatMessage`、`PartnerChatSummary`、`PartnerPair`；
  - `PartnerMessageTool`：工具名、参数名和结果字段名。界面会按这些名字读取工具调用。
- `data/partner/PartnerChatService.kt`：接口，由你实现并在 DI 中注册。
- `Conversation.chatId`：标记某个伙伴在某段多伙伴聊天中的私有视角分段。
- `Settings.partnerContactGrants`：哪些伙伴两两之间已经获得你的授权，可以互相联系。
- 路由 `Screen.PartnerChat(chatId)`：页面由 Claude 实现。

## 任务

### 1. 存储（数据库 34 → 35）

- 新增三张表：
  - `partner_chat`：id、kind、title、created_at；
  - `partner_chat_member`：chat_id、assistant_id、joined_at、`perspective_segment_id`（该成员的私有视角分段）、`synced_message_id`（已同步进视角分段的最后一条聊天消息）；
  - `partner_chat_message`：id、chat_id、sender_id、text、created_at、exchange_id、reply_to。
- `conversation` 表新增 `chat_id` 列。
- 写迁移、更新 schema、补迁移测试，做法同第 2 阶段，**不能卸载或清空 emulator-5560 上的 app**。
- 同一对伙伴只有一段 DIRECT 聊天。
- 删除伙伴时：
  - 删除它参与的 DIRECT 聊天、成员行和视角分段；
  - 从授权列表和聊天组织（`chatOrganization`，置顶和分组）中去掉相关 id；
  - 聊天 id 同样适用上一阶段的 `retainChats` 清理。
- 备份恢复：新表随数据库走。确认 `partnerContactGrants` 随设置备份。

### 2. 视角分段不进 IM 时间线

- `chatId != null` 的分段不出现在以下地方：
  - 伙伴的 IM 时间线：`ThreadRepository`、`observeThreadSegmentStamps`；
  - 消息列表的“每个伙伴最新会话”；
  - 话题路由的候选；
  - `deliverFrom` 的分段选择；
  - 分段摘要的注入；
  - IM 搜索。
- 专业模式下可以照常显示，标题取“与 <对方> 的私聊”。

### 3. 视角同步与发言

- **轮到伙伴 X 在聊天 C 中发言时**：
  1. 把 C 中 `synced_message_id` 之后的消息，按 X 的视角追加进 X 的视角分段（没有就新建，`assistantId = X`，`chatId = C`）：
     - X 自己说过的话已经在分段里，跳过；
     - 其他伙伴的话：USER 节点，文本前缀 `[<伙伴名>] `，`origin = Partner(senderId, exchangeId, chain)`；
     - 主人的话：USER 节点，文本前缀 `[<主人昵称，没有就用“主人”>] `，`origin = null`，所以带主人权限。
  2. 用现有的投递 / 生成路径生成一轮。连续的 USER 节点不要合并（来源可能不同）；确认各 provider 适配器都能接受连续的 user 消息，不能接受的在请求层合并。
  3. 这一轮结束后，取它最后一条助手消息的文本，写成 C 中 X 的一条 `PartnerChatMessage`，并更新 `synced_message_id`。
- **视角分段的系统提示**通过现有的 `threadContext` 追加，包括：
  - 这是你和 <对方> 的私聊；
  - 带 `[名字]` 前缀的消息是谁说的；
  - 主人可能加入；
  - 回复要直接对对方说，第一版只能用文字。
- **`observeTyping`**：一轮正在进行的成员。
- **`sendFromOwner`**：
  - 存下主人的消息；
  - 按 mentions 的顺序依次让被 @ 的伙伴发言；没有 @ 时，由最近一个说过话的伙伴发言；
  - 方法本身在存储完成后就返回，不等伙伴回复。

### 4. 工具：`list_contacts` 与 `send_message`

- 只在轻松模式下提供给伙伴，挂到 `ChatService` 构建工具列表的地方。
- **`list_contacts`**：返回可联系的人。
  - 主人：id 为 `owner`，只在视角分段中提供，见下文；
  - 其他伙伴：id、名字、一句话介绍（取 system prompt 的开头，截断）、是否已获授权。
  - 不包含调用者自己。
- **`send_message(to, text)`**：结果为 JSON，字段名和状态值见 `PartnerMessageTool`。
- **发给另一个伙伴 B 时**：
  - **拒绝**（返回 `refused` 并说明原因）的情况：
    - B 不存在，或者 B 就是自己；
    - B 已经在调用链上（防循环）；
    - 调用链深度超过 2（A→B→C 允许，再往下不行）；
    - 本次交换的往来超过 6 条。
  - **授权**：这对伙伴不在 `partnerContactGrants` 里时，`needsApproval` 返回 true，由主人在审批卡上决定。主人批准后，`execute` 先写入授权，再继续。
  - **执行**：
    1. 找到或建立 {A, B} 聊天；
    2. 写入 A 的消息：新的 exchangeId，调用链 = 当前链 + A；
    3. 让 B 发言；
    4. 最多等 90 秒：
       - 等到了：写入 B 的消息，返回 `replied` + `reply`；
       - 超时：返回 `pending`。B 完成后照常写入 B 的消息，再用 `ThreadService.deliverFrom(Event("partner_replied", summary = "<B> 回复了", sourceAssistantId = B, chatId = C), A, …, segmentId = A 发起时所在的分段)` 唤醒 A。投递内容是给模型看的：说明 B 回复了什么。
  - 异步唤醒只针对从 A 的 IM 时间线发起的调用。从视角分段里发起的调用（链上的第二跳）只同步等待，超时就返回 `pending`；B 的回复只写进聊天，不唤醒发起方。
- **发给主人（`to = owner`）时**：
  - 只在视角分段中可用；
  - 在 A 的 IM 时间线最新的未关闭分段（没有就新建）中追加两个节点：
    1. 事件节点：`origin = Event("partner_note", summary = "来自和 <B> 的私聊", sourceAssistantId = B, chatId = C)`；
    2. A 的助手消息，内容为 text。
  - 不触发生成；计入未读，并像回复完成一样，在后台时发通知。
  - 返回 `delivered`。
- 调用链：沿用 `MessageOrigin.Partner.chain`。发起方的这一轮由什么触发，就从那里取当前链：
  - 主人消息或事件触发：链为空；
  - 伙伴消息触发：取该消息的 chain。

### 5. 前台保活（第 2 阶段遗留问题）

- 现状：`ChatGenerationForegroundService.acquire` 每次都调用 `startForegroundService`，app 在后台时会被系统拒绝，进程随后被冻结，非主人发起的回合停住，直到用户打开 app。
- 改为：
  - 服务已在运行时，在进程内直接给计数加一，不再调用 `startForegroundService`；
  - 交接时先获取、后释放：B 的一轮结束时，先为写回和唤醒 A 的那一轮获取，再释放 B 的；
  - 服务没在运行、又被系统拒绝时，退回 WorkManager 加急任务（`setExpedited`，配额用完时用 `RUN_AS_NON_EXPEDITED_WORK_REQUEST`），让进程在这一轮期间保持运行。
- 在 emulator-5560 上验证：app 退到后台后，用 debug 触发器投递事件，回复应在**不打开 app** 的情况下完成并发出通知。

### 6. 消息列表与已读

- `observeSummaries` 供消息列表使用。
- 只有伙伴之间往来的聊天不计未读、不发通知。
- 主人参与的聊天的未读，留到第 4 阶段。
- 已读时间可以复用 `ThreadListState`，key 用 chatId。

## 测试

- 迁移 34 → 35 与 `chat_id` 列。
- 视角同步：前缀、来源、跳过自己的话、`synced_message_id` 递增。
- 主人在私聊中的话带主人权限，伙伴的话不带。
- `send_message`：
  - 授权：未授权需要审批，批准后写入授权；
  - 各种拒绝情况；
  - 90 秒内回复；
  - 超时后通过事件唤醒（用可注入的超时时间测试）；
  - 第二跳不唤醒发起方。
- 发给主人：两个节点、未读、不触发生成。
- 视角分段不出现在 IM 时间线、消息列表、路由和搜索中。
- 删除伙伴时的级联清理。
- 前台服务计数与加急任务退路（用 fake 测试）。

## 文件归属

数据、服务、工具、DI、迁移、前台服务与测试归你。`ui/` 下不要修改，契约签名不要改。

## 验收

- 定向测试与 `./gradlew :app:testDebugUnitTest` 通过。
- 迁移测试在 emulator-5560 上通过：只跑该类，加 `-Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true`。
- `:app:assembleDebug`、`:app:lintDebug` 通过。
- 后台保活实测通过，见第 5 节。
- 提交到分支 `feature/partner-chat-data`，不要提交到 `feature/im-4.0`。
- 写 `docs/agent-collab/P3_DATA_NOTES.md`。
- 有事实性问题直接在你的输入框里问，我会回答。

# 规格：P3 让伙伴新建伙伴、建群拉人、整理消息列表

前置阅读：
- `AGENT_ENV_INVENTORY.md`
- `AGENT_ISOLATION_SPEC.md`（R1–R7）
- `AGENT_SELF_PROFILE_SPEC.md`（R8、P2）
- `AGENT_RELAY_AVATAR_SPEC.md`（R9、头像预设）
- 以及上面三份规格对应的 NOTES。

所有新工具都通过 `AgentAccess`，并满足 R1–R9。

## 先分清两个“组”

| 名称 | 指什么 | 数据 |
|---|---|---|
| **群聊** | 多个伙伴（以及主人）一起聊天的群 | `PartnerChat`，`kind = GROUP`，由 `PartnerChatService` 管理 |
| **分组** | 消息列表里用来归类聊天的文件夹，加上置顶 | `Settings.chatOrganization`（`ChatOrganization`/`ChatGroup`） |

工具名、描述、notes 里都要严格区分这两者，不要混用“group”。

## 通用约束

- 只在 **IM 模式** 并且是 **主人发起的回合**（`access.ownerInitiated`）时注册；执行时再检查一次。伙伴发起的回合里看不到这些工具。
- 所有写操作都要审批（`needsApproval = { true }`）。每个写工具都必须带 `reason` 参数，用主人的语言说明理由。
- 不开放任何删除操作。唯一的例外是删除**空的**分组，因为它不涉及任何数据。
- 一律通过 `withContext(RevisionOrigin(RevisionAuthor.AGENT, trigger = ownerTrigger, summary = reason))` 写入，凡是现有修订机制能记录的都记录下来。
- 输入不合法时返回 `{"success":false,"error":...}`，不抛异常。
- 输出里不能有其他伙伴的配置（R5）、聊天内容（R1/R2）、路径或凭据（R7）。

## 一、新建伙伴：`create_partner`

参数：
- **必填**：`name`（1–40 个字符）、`reason`。
- **可选**：
  - `system_prompt`：不超过 8000 个字符。
  - 头像：和 `update_my_profile` 规则完全一致，即 `avatar_emoji`，或者 `avatar_preset`（`miffan`/`whale_girl`）配可选的 `miffan_kind`/`miffan_palette`。这里**不支持** `previous`。没有给头像时，用 `Assistant()` 的默认头像。请复用 P2 已有的校验函数。

执行：
- 新伙伴写成 `Assistant(name, systemPrompt, avatar, useAssistantAvatar = true)`，其余字段全部保持默认。也就是说：
  - 模型使用 App 默认模型；
  - 能力只有默认的那几项。

  伙伴**不能**给新伙伴指定模型、工具、MCP 或工作空间。这些由主人在界面上设置。
- 创建成功后，自动授予“调用者 ↔ 新伙伴”的联系权限（写入 `partnerContactGrants`），审批卡上会说明这一点。
- 每次调用只建一个伙伴。
- 返回 `{"success":true,"partner_id":...,"name":...}`。
- 确认 `AssistantRevisionRecorder` 能记录新增（`before = null`）。

## 二、群聊

### `create_group_chat`

参数：`title`、`member_ids`（来自 `list_contacts` 的伙伴 id）、`reason`。

- **成员**：调用者自动加入成员，作为第一个成员，也就是群主（`hostId`）。最终成员列表要满足 `validateMembers` 的现有规则，比如人数下限。
- **校验**：所有 id 都必须是存在的伙伴，并且不能是调用者自己的重复项。
- **执行**：通过 `PartnerChatService.createGroup` 创建。
- **返回**：`chat_id` 和 `title`。

### `invite_to_group_chat`

参数：`chat_id`、`member_ids`、`reason`。

- **权限**：调用者必须是这个群聊的成员；不是成员时，返回和“群聊不存在”**完全相同**的错误（R2）。
- **范围**：只能**新增**成员，不能移除；已经在群里的 id 直接忽略。
- **执行**：通过 `setMembers` 写入，保持现有成员和群主不变。

**不做**：退群、移除成员、改群名、删除群聊。`setMembers` 移除成员时会删除该成员的视角数据，属于删除操作，本轮不开放。

## 三、消息列表：查看与整理分组

### 可见范围

先定义“调用者可见的聊天”，下面的读写都只针对这个范围：

- 所有伙伴与主人的一对一线程。`chatId` 就是该伙伴的 id，显示名用 `partnerDisplayName`。伙伴名字本来就是公开的。
- 调用者**自己是成员**的伙伴私聊和群聊。显示名使用现有标题规则。
- **不包括**调用者不在其中的伙伴私聊和群聊。它们不能出现在输出里，也不能被操作引用；如果引用了，按“聊天不存在”处理。操作时，这些聊天原有的分组和置顶也必须保持不变。

### `view_chat_list`（只读，不需要审批）

只在主人发起的回合注册。返回内容：
- 分组列表（按顺序），每个分组给出 id、名字和其中可见的聊天；
- 未分组的可见聊天；
- 置顶列表（按顺序，只含可见聊天）。

每个聊天只给 `chat_id`、`name`、`type`（`partner_thread`/`partner_chat`/`group_chat`）、`pinned`。**不给**消息预览、时间、未读数。

### `organize_chat_list`（写，**整批一次审批**）

参数：`operations`（数组）和 `reason`。按顺序执行，整批原子：任何一步不合法，整批都不写入，并返回出错的是第几步、原因是什么。

操作类型：

| 操作 | 字段 |
|---|---|
| `pin` | `chat_id` |
| `unpin` | `chat_id` |
| `create_folder` | `name`，可选 `ref`（同一批中后续操作可以用 `ref` 引用这个新分组） |
| `rename_folder` | `folder_id`、`name` |
| `move_to_folder` | `chat_id`，`folder_id` 或 `ref`；两者都不给表示移出分组 |
| `move_folder` | `folder_id`，`offset`（±整数） |
| `delete_empty_folder` | `folder_id`。只有分组里**没有任何聊天**时才允许删除，不可见的聊天也算在内 |

执行要求：
- 使用 `ChatOrganization` 已有的方法，在一次 `settingsStore.update` 里应用整批操作。
- 分组名 1–30 个字符。
- 一批最多 50 个操作。
- 返回 `success`，以及每个新建分组的 `ref → folder_id` 对应关系。

## 界面（我来做，你只需要保证参数名和上面完全一致）

我会为 `create_partner`、`create_group_chat`、`invite_to_group_chat`、`organize_chat_list` 做专门的审批卡，并添加步骤文案。卡片显示时，会用 `LocalSettings` 和 `PartnerChatService` 把 id 解析成名字。

## 测试

- 注册范围：仅 IM 模式且主人发起的回合；伙伴发起的回合和专业模式都看不到这些工具。
- 写操作都需要审批，`view_chat_list` 不需要。
- `create_partner`：
  - 默认字段正确；
  - 不能指定能力；
  - 联系授权已写入；
  - 头像校验复用 P2；
  - 修订记录正确。
- 群聊：
  - 调用者自动成为成员和群主；
  - 非成员邀请时的报错与“群聊不存在”完全相同；
  - 只能新增成员；
  - 现有群主不变。
- 消息列表：
  - 可见范围正确：调用者不在其中的伙伴私聊和群聊不会出现，也不能被操作；
  - 整批操作是原子的；
  - `ref` 引用可用；
  - 非空分组不能删除（不可见聊天也算）；
  - 操作上限和名字长度生效；
  - 不可见聊天原有的分组和置顶保持不变。

## 验收

- `./gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 全部通过。
- **不要使用模拟器**：emulator-5560 可能被其他会话占用，内存也很紧张。只用 JVM 测试，并在 notes 里写明。
- 交付：
  - 分成三个提交：新建伙伴、群聊、消息列表；
  - 提交到 `feature/agent-p3`；
  - 写 `docs/agent-collab/AGENT_P3_NOTES.md`；
  - 日志和临时文件放在 worktree 内的 `build/` 下。
- 有事实性问题直接在输入框里问。

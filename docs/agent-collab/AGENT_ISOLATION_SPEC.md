# 规格：伙伴之间的权限隔离 + 只读环境感知工具（P0 + P1）

背景见 `AGENT_ENV_INVENTORY.md`。主人最关心的一点：**伙伴 A 不能读到它不该读的东西**。下面的规则由主人在 2026-10-12 确认。

## 术语

- **调用者**：正在生成回复的伙伴（`assistant.id`）。
- **主人发起的回合**：`turnConversation.ownerTrigger() != null`。
- **伙伴发起的回合**：其余情况，例如别的伙伴发来消息，在私有视角分段里叫醒了调用者。
- **主人私聊**：`chat_id IS NULL` 的会话。
- **伙伴聊天**：`chatId` 不为空的私有视角分段，以及对应的 `PartnerChat`。

## 隔离规则

| # | 规则 |
|---|---|
| R1 | 主人私聊只属于它所在的那个伙伴。任何工具只能读到调用者自己（`assistant_id = 调用者`）的主人私聊。**IM 模式和专业模式都适用**。 |
| R2 | 伙伴聊天只有成员能读，这一点已经实现，保持不变。 |
| R3 | 伙伴发起的回合里，回忆类工具（`conversation_search`、`recent_chats`）不返回任何主人私聊，只返回调用者参与的伙伴聊天。`read_partner_chat` 不变。记忆照常注入，主人已确认这样做。 |
| R4 | 记忆只能在调用者自己的记忆范围内修改：开了全局记忆时，范围是全局记忆；否则是调用者自己的记忆。`memory_tool` 编辑或删除不属于这个范围的编号时，返回和“记录不存在”**完全相同**的错误，不能透露这个编号是否存在。 |
| R5 | 其他伙伴的配置对调用者不可见：系统提示词、学到的偏好、记忆、模型、绑定等。`list_contacts` 去掉系统提示词首行，改为该伙伴的**标签名**（`settings.assistantTags` 中对应 `assistant.tags` 的名字）。没有标签时，就不给这个字段。 |
| R6 | 扩展目录（`extensions_catalog`）对调用者自己给出完整信息；其他伙伴只给 `id` 和 `name`。针对其他伙伴的变更（任何 `assistantId != 调用者` 的变更），除了现有的审批，还要求是主人发起的回合，否则在 preview 阶段就拒绝。 |
| R7 | 凭据（API key、请求头、OAuth、代理密码、WebDAV/S3 配置等）永远不出现在任何工具的输出里。这一点现状已满足，新增工具也必须遵守。 |

实现要求：
- 把“调用者是谁、本回合是否主人发起”收敛成**一个**每回合构建的上下文对象，例如 `AgentAccess(callerId, ownerInitiated, memoryOwnerId)`。名字和形状由你决定。
- 各工具通过这个对象判断，不要在各处重复推导。
- 每条规则至少要有一个测试覆盖。

## P0：修复现有漏洞

1. **R1**：`MessageFtsManager.search` 第 79 行附近的条件 `chat_id IS NULL OR (...)` 让调用者能搜到所有伙伴的主人私聊。
   - 改为：只包含调用者自己的主人私聊，加上 IM 模式下它自己的视角分段。
   - 主人在界面上使用的全局搜索（不经过工具、没有调用者）**保持现状**，不要误伤。请找全搜索的调用方，区分开这两种用法。
2. **R3**：伙伴发起的回合里，`conversation_search` 和 `recent_chats` 不返回主人私聊。工具描述也要相应调整，不要让模型以为能搜到。
3. **R4**：在 `GenerationHandler` 的 `onUpdate`/`onDelete`，或 `MemoryRepository` 层加归属检查。
   - 在哪一层加由你选择，但要保证主人自己在记忆页面的编辑和删除不受影响。
   - 内部的自动记忆提取（`MemoryExtraction`）如果也会编辑或删除记忆，同样要受约束。
4. **R5**：`list_contacts`。
5. **R6**：`ExtensionManagementService.catalog()` 和 `preview()`。
   - 这两个函数需要知道调用者；从 `ChatService` 传入。
   - 主人在设置页面里不经过工具使用这个服务时，如果也走这两个函数，要保持完整视图。

## P1：只读工具 `my_environment`

仅在 IM 模式下提供，无需开关和审批，主人发起和伙伴发起的回合都可用。只返回调用者自己的信息和无敏感内容的共享信息：

- `name`：空名字用 `partnerDisplayName` 的规则显示；
- `avatar`：只给类型，例如 emoji、图片、默认。不给文件路径；
- `model`：当前模型的显示名和提供商的显示名。不给 base URL 和 key；
- `abilities`：本回合实际可用的能力，以人能看懂的方式列出，例如：
  - 联网搜索；
  - 记忆，以及是否用全局记忆；
  - 最近聊天参考；
  - 各本地工具；
  - 工作空间及 shell；
  - 电脑操作；
  - 已启用的 skill 名；
  - 已绑定的 MCP 服务器**名称**；
  - 扩展管理。
- `workspace`：绑定的工作空间名称。不给本地路径；
- `computer`：绑定的电脑名称，如果有的话；
- `groups`：调用者所在的群，包括群名和成员显示名。**只列调用者是成员的群**；
- `contacts_authorized`：已获主人授权可以直接联系的伙伴名字。

不包含：
- 调用者的系统提示词和学到的偏好（它已经在 prompt 里）；
- 任何其他伙伴的配置；
- 凭据。

工具描述要讲清楚：主人问“你是谁、你能做什么、你在哪些群”这类问题时使用。

## 不做

- 任何界面改动。工具步骤的文案映射（`ThreadStepRecord.kt`）由我来加。
- 写操作类的新工具（P2、P3）。
- 数据库版本变更。

## 测试与验收

- 每条规则 R1–R6 都有 JVM 测试。如果需要真实的 SQL，FTS 条件放到 androidTest（可参考现有的 `PartnerRecallQueryTest`），并在 emulator-5560 上运行。
- 测试 `my_environment` 输出不含其他伙伴的信息、路径、URL 和凭据。
- `./gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 全部通过。
- 在 emulator-5560 上用真实模型实测：
  - 伙伴 A 搜索只有伙伴 B 的主人私聊里才有的关键词，搜不到。
  - 伙伴发起的回合里搜不到主人私聊。
  - 问 A“你是谁、能做什么、在哪些群”，A 会调用 `my_environment`，答案正确。
- 模拟器操作：
  - 当前是关着的。用 `~/Library/Android/sdk/emulator/emulator -avd RikkaHub_API_35_16K -port 5560 -no-window -no-audio -no-boot-anim` 在后台启动。
  - 不能卸载 App，也不能清空 App 数据。
  - 测完执行 `adb -s emulator-5560 emu kill`，并在 worktree 里运行 `./gradlew --stop`。
- 交付：
  - 分成 P0、P1 两个提交，提交到 `feature/agent-isolation`；
  - 写 `docs/agent-collab/AGENT_ISOLATION_NOTES.md`，内容包括：每条规则落在哪里、哪些调用方有意保持原样、实测结果、你发现但没有处理的其他隔离风险。
- 有事实性问题直接在输入框里问。

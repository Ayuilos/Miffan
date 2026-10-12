# 规格：伙伴能搜到自己参与过的伙伴对话

## 问题（真机复现）

主人在 A 的线程里让 A 联系 B。过了一阵，主人在 B 的线程里问 B“你和 A 沟通过吗”，B 调用搜索后回答“没找到”。

原因是 B 回复 A 的那段对话存在 B 的私有视角分段里（`chat_id` 不为空），而下面这些查询在 IM 模式下都把这类分段排除了：

- `conversation_search`：`MessageFtsManager.search` 里的条件 `chat_id IS NULL`；
- `recent_chats`：`getRecentConversationsOfAssistant` 里的条件 `chat_id IS NULL`。

这些排除原本是为了不让视角分段出现在主人的列表和全局搜索里，副作用是伙伴自己也搜不到它经历过的对话。

决定（2026-10-12，主人认可）：只放开搜索，不往上下文里注入摘要。

## 任务

### 1. `conversation_search`（`data/ai/tools/ConversationTools.kt`）

- IM 模式下，搜索范围在现有结果之外，再加上**调用者自己**的视角分段，即 `assistant_id` 等于调用者、且 `chat_id` 不为空的会话。其他伙伴的视角分段仍然排除。
- 结果中来自伙伴对话的条目：
  - 标明来源，例如 `"source": "partner_chat"` 或 `"group_chat"`；
  - 标题用聊天的显示名：私聊写“和 <对方名> 的私聊”，群聊写群名，群名为空时用成员名拼接（同 `partnerChatTitle` 的规则）；
  - 给出 `chat_id`。
- 已有的条目（主人线程）不变，可以加 `"source": "owner_thread"`。
- 主人的全局搜索（`ImSearchPage` 等，走同一个 `searchMessages`）**行为不变**，仍然排除所有视角分段。需要的话给 `searchMessages` 或 FTS 查询加参数来区分。
- 专业模式行为不变。

### 2. `recent_chats`

- IM 模式下，在列表里加入调用者参与的最近伙伴对话（私聊和群聊），按最近活动时间和现有条目一起排序。
- 每条给出标题（规则同上）、`source`、`chat_id` 和日期。和现有条目一样，只返回标题和日期。
- 总数仍受 `limit` 限制。

### 3. 工具说明

两个工具的 description 都补一句，例如：“Also covers your private chats and group chats with other partners that you took part in.” 让模型知道这些对话能搜到。

### 不做

- 不往系统提示或线程上下文里注入伙伴对话摘要。
- 不改变 `conversation_search` 对主人线程的现有范围。
- 不改界面。

## 测试

- 调用者能搜到自己视角分段里的消息，结果的来源和标题正确；
- 搜不到其他伙伴的视角分段（例如 C 和 D 之间的私聊）；
- 主人的全局搜索仍然排除所有视角分段；
- `recent_chats` 混合排序正确，并受 `limit` 限制；
- 专业模式行为不变。

## 验收

- `./gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 全部通过。
- 在 emulator-5560 上用真实模型实测：在 A 的线程里让 A 联系 B，然后在 B 的线程里问“你和 A 沟通过吗”，确认 B 能搜到并说出内容。
  - 模拟器目前是关着的，用 `~/Library/Android/sdk/emulator/emulator -avd RikkaHub_API_35_16K -port 5560 -no-window -no-audio -no-boot-anim` 在后台启动。
  - 不能卸载或清空 app 数据。
  - **测完后**用 `adb -s emulator-5560 emu kill` 关掉模拟器，再在 worktree 里运行 `./gradlew --stop`，因为主人的电脑内存紧张。
- 提交到 `feature/partner-recall-data`，写 `docs/agent-collab/PARTNER_RECALL_NOTES.md`，把实测结果写进去。
- 有事实性问题直接在你的输入框里问。

# 规格：专业模式中隐藏伙伴的私有视角分段

## 问题

伙伴之间的私聊和群聊，会在每个参与者名下各存一个私有视角分段：`Conversation` 的 `chatId` 不为空。专业模式把这些分段当作普通会话，出现在会话列表、搜索等处。以 A 联系 B 为例，A 名下会出现一个“与 B 的私聊”：

- 第一条是 A 发出的问题，显示成助手气泡；
- B 的回答是带 `[B]` 前缀的 USER 消息，显示成主人的气泡。

看起来像是主人在用 B 的口吻说话，让人困惑。而且在专业模式里往这种分段里继续发消息，会写进私有视角，但不会同步到伙伴聊天，两边的记录会对不上。

决定（2026-10-12，主人选定）：专业模式完全不展示这些分段，数据保留不动。切回轻松模式后，照常在伙伴私聊和群聊里查看。

## 任务

专业模式下，凡是列出、搜索、挑选或打开会话的路径，都要排除 `chat_id IS NOT NULL` 的会话。请自行把这些路径找全，至少包括：

- **会话列表**：`ConversationDAO` 中按 assistant、文件夹、标题搜索的列表和分页查询（第 21、24、27、30、69、72 行附近），以及 `observeRecentConversations`、`searchConversations*` 等。
- **全文搜索**：`MessageFtsManager.search` 在专业模式下目前返回全部会话，改为排除视角分段。IM 模式和 `perspectiveAssistantId` 的行为不变，见 `PARTNER_RECALL_NOTES.md`。
- **`conversation_search` 工具**：专业模式下同样排除视角分段。
- **默认打开的会话**：专业模式进入某个助手、或启动时恢复“上次的会话”时，不能选中视角分段。
- **按 id 打开**（通知、收藏、深链接、`Screen.Chat(id)` 等）：如果目标是视角分段，改为打开 `Screen.PartnerChat(chatId)`，不进入专业模式的 `ChatPage`。如果该聊天已经删除，就按“会话不存在”的现有逻辑处理。
- 你发现的其他路径（导出、会话数量统计、文件夹计数等）：
  - 涉及“展示给主人看”的，同样排除；
  - 备份、恢复和导出全部数据保持原样，不能丢数据；
  - 拿不准的列在说明里。

### 不做

- 不删除、不迁移任何数据，数据库版本不变。
- IM 模式的所有行为不变。
- 不改专业模式的界面布局。只有按 id 打开时的跳转属于路由，允许修改。

## 测试

- 上面列出的每个查询：专业模式下都不返回视角分段，普通会话照常返回。
- 全文搜索覆盖三种情况：专业模式、IM 主人的全局搜索、IM 伙伴的回忆搜索。
- 默认会话的选择会跳过视角分段。
- 按 id 打开视角分段时，会跳转到 `PartnerChat`。
- 备份和导出包含视角分段（如果已有相关测试，确认它们仍然通过即可）。

## 验收

- `./gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 全部通过。
- 在 emulator-5560 上实测。模拟器里已有伙伴私聊和群聊数据，例如 Default Assistant 与 Translator 的私聊。
  - 切到专业模式，确认会话列表和搜索里都没有“与 X 的私聊”或“群聊：…”；
  - 再切回轻松模式，伙伴私聊和群聊仍然完整。
  - 测完把模式切回原来的轻松模式。
  - 模拟器当前是关着的，用 `~/Library/Android/sdk/emulator/emulator -avd RikkaHub_API_35_16K -port 5560 -no-window -no-audio -no-boot-anim` 在后台启动。
  - 不能卸载或清空 app 数据。
  - 测完用 `adb -s emulator-5560 emu kill` 关掉模拟器，并在 worktree 里运行 `./gradlew --stop`。
- 提交到 `feature/pro-hide-perspectives`，写 `docs/agent-collab/PRO_HIDE_PERSPECTIVES_NOTES.md`：
  - 列出改了哪些路径、哪些路径有意保留；
  - 写上实测结果。
- 有事实性问题直接在你的输入框里问。

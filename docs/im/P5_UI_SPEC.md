# P5 IM 页面实现说明（交给 Codex）

本说明是 P5 的界面层任务：把 IM 模式剩余的页面按已评审的设计稿还原。数据与规则已经就绪，界面只需使用下列 API；不修改数据层。

背景：[开发计划](../IM_EXPERIENCE_PLAN.md)、[设计说明](DESIGN_BRIEF.md)（IM 交互约定、第一轮评审修正、实现备注）、设计稿 `docs/im/mockups/*.webp`、P2 的实现记录 [P2_UI_NOTES.md](P2_UI_NOTES.md)（沿用其组件与风格）。

## 工作方式

- 在 herdr 创建的独立 worktree 中工作，分支 `feature/im-4.0-p5-ui`，基于 `feature/im-4.0`。
- 可在该分支提交（末尾加 `Co-Authored-By: Codex <noreply@openai.com>`），不推送，不合并。
- 模拟器只用 `RikkaHub_API_35_16K`（`emulator-5556`，所有 adb 命令带 `-s emulator-5556`）。P2 用过的本地 fixture 服务可以继续用，但不要提交进仓库。

## 可以修改的范围

- `app/src/main/java/me/ayuilos/miffan/ui/im/` 下的文件（含新子包），包括 `ImChatsTab.kt`、`ImPartnersTab.kt`、`ImMeTab.kt`、`ImHomePage.kt`。
- `RouteActivity.kt`：新增 `Screen` 类型与对应 `entry`；在 `entry<Screen.Onboarding>` 中按 `settings.isImMode` 选择新的 IM 引导页。
- `di/ViewModelModule.kt`：只为新增的 IM 页面 VM 注册。
- 字符串资源：新增 `im_` 前缀的键，`values`、`values-zh` 写入，其余四种语言用 `locale-tui set` 写入你自己的翻译（撇号转义 `\'`）。

## 禁止修改

`data/`、`service/`、数据库、`ThreadTimeline`、`AgentThreadVM` 及专业模式页面。缺少能力时用界面侧最小绕行，并写进 `docs/im/P5_UI_NOTES.md`。

## 可用 API

| 能力 | API |
| --- | --- |
| 消息列表行 | `ImHomeVM.chats`：`ImChatItem(assistant, preview, previewKind, previewFromUser, lastActivity, unread, pinned, typing)`；`setPinned(assistant, pinned)`、`hide(assistant)`（“不显示”） |
| 打开线程 | `Screen.Thread(assistantId, focusMessageId?)` |
| 伙伴配置 | `SettingsStore.update { … }` 修改 `settings.assistants`（名字、头像、`systemPrompt`、`enableWebSearch`、`enableMemory` 等）；新增伙伴即追加 `Assistant(...)`。所有修改都会自动进入版本历史 |
| 版本历史 | `RevisionRepository.history(subject, subjectId)`（新到旧）；`RevisionService.restore(target, expectedHeadId)` 返回 `RestoreResult`（`Restored` / `Conflict` / `NotFound`）；`RevisionService.undo(revisionId)`。快照解码：`AssistantRevisionRecorder.restore(snapshot)`、`MemoryRepository.restore(snapshot)` |
| 记忆 | `MemoryRepository.getMemoryRecordsFlow(ownerId)` 返回 `MemoryEntity`（含 `createdAt`、`sourceConversationId`、`sourceMessageId`）；`deleteMemory(id)`；`MemoryRepository.GLOBAL_MEMORY_ID`。伙伴的记忆归属：`assistant.useGlobalMemory` 为真时用全局，否则用伙伴 id |
| 来源跳转 | 记忆的来源会话属于某个伙伴：用 `ConversationRepository.getConversationById(sourceConversationId)?.assistantId` 得到伙伴，再打开 `Screen.Thread(assistantId, focusMessageId = sourceMessageId)` |
| 搜索 | `ConversationRepository.searchMessages(keyword)` 返回 `MessageSearchResult`（查看其字段）；伙伴按名字在 `settings.assistants` 中过滤 |
| 换个话题 / 删除聊天记录 | `ThreadService.closeAll(segments)`（需要当前段，可在新 VM 中通过 `ThreadRepository.observeSegments(assistantId, 12)` 取得）；`ConversationRepository.deleteConversationOfAssistant(assistantId)`（必须二次确认） |
| 工具授权 | `AgentThreadVM.answerToolApproval(item, toolCallId, approved)`、`answerToolQuestion(item, toolCallId, answer)` |

## 必须实现的画面（对照设计稿）

| 设计稿 | 要点 |
| --- | --- |
| 01 | 消息列表：平铺行加分隔线；未读数红色角标（大于 99 显示 99+）；置顶行有标记并排在前；“正在输入…”；预览按 `previewKind` 显示文本或“[图片]”“[文件]”，`previewFromUser` 时前缀“我：”；左滑露出“置顶 / 取消置顶”与“不显示”；首次进入可显示一行灰色提示，之后不再显示（本地记住） |
| 08 | 伙伴 Tab：“我的伙伴”网格；“推荐伙伴”4 张模板卡（翻译官、写作搭子、陪聊、学习教练），点“添加”创建一个新伙伴并进入它的资料卡；模板的名字、一句话简介和 system prompt 写成简体中文和英文两套（按系统语言选择），口吻温和、具体、简短；已添加过的模板显示“已添加”。点伙伴进入资料卡（不再直接进聊天），长按也进入资料卡 |
| 09 | 伙伴资料卡（新页面 `Screen.PartnerProfile(assistantId)`）：大头像（可换）、名字与性格（`systemPrompt`）编辑、主按钮“发消息”；“它记得关于你的 N 件事”进入记忆页（筛选为该伙伴的记忆归属）；能力开关只放真实存在的两项：联网搜索（`enableWebSearch`）、记住我们聊过的事（`enableMemory`）；入口：设定历史、聊天背景（进入现有 `Screen.AssistantBasic`）、换个话题（弱化）、删除聊天记录（红色，二次确认） |
| 06 | 设定历史（新页面 `Screen.RevisionHistory(subject, subjectId)`，伙伴和记忆共用）：时间线，每条显示时间、来源（你在聊天中说的 / 你手动修改 / 恢复 / 备份恢复 / 初始状态）、人话摘要（伙伴：列出改了哪些项，如“名字、性格、学到的偏好、联网搜索”；记忆：新增 / 修改 / 删除了哪几条）；触发消息可点击跳回线程；展开一条可切换“人话总结 / 原文差异”（原文差异按行比较 `systemPrompt` 与 `learnedPreferences`，记忆按条目比较）；“恢复到此版本”调用 `restore(target, 当前 head id)`，冲突时提示刷新 |
| 07 | AI 对我的了解（替换“我”Tab 目前进入的旧页面）：列表行“内容 / 由 X 记下 · 日期”，可跳转来源、可删除（删除前不确认，但提供撤销提示：调用 `RevisionService.undo` 刚产生的修订）；右上“历史”进入对应归属的记忆历史；顶部说明“这些是伙伴从聊天中记下的，你可以随时删除”；无记忆时的空态 |
| 11 | “我”Tab：按 11 稿调整分组与入口，“AI 对我的了解”进入 07 |
| 18 | 线程中的权限卡：`approvalState` 为 `Pending` 的工具在时间线里渲染成卡片（标题“打开伙伴能力”、说明、允许 / 这次不用），调用 `answerToolApproval`；只改 `ui/im/thread` 中渲染工具的位置 |
| 24 | 搜索页（新页面 `Screen.ImSearch`，消息 Tab 搜索框进入）：分组“伙伴”“聊天记录”，命中片段高亮，点聊天记录打开 `Screen.Thread(assistantId, focusMessageId)` |
| 12 | IM 引导（`isImMode` 时替换 `Screen.Onboarding` 的内容）：第 1 步连接 AI 服务——列出服务商，OpenRouter 标“一键登录”并复用现有 OpenRouter 授权，其余走现有服务商配置；选中后“继续”才可用。第 2 步选择第一个伙伴（默认伙伴或推荐模板）。完成后进入 IM 首页。专业模式的引导保持原样 |

## 验收

1. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过，新文件无 lint 告警。
2. 在 `emulator-5556` 上走通上表全部画面；切换中文与英文各看一遍主要页面。
3. 截图转 WebP 存入 `docs/im/p5-screens/`，`docs/im/P5_UI_NOTES.md` 逐条记录完成情况、偏差、未覆盖项与原因。
4. 完成后提交，回复“P5 UI 完成”并附提交哈希与 NOTES 摘要。

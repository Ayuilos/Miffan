# P2 时间线界面实现说明（交给 Codex）

本说明是 P2 时间线**界面层**的实现任务。数据层与交互规则已经完成并有单元测试，界面只需按设计稿还原，不需要也不允许改动数据层。

背景：[开发计划](../IM_EXPERIENCE_PLAN.md)、[设计说明](DESIGN_BRIEF.md)（含“IM 交互约定”与“实现备注”）、设计稿 `docs/im/mockups/*.webp`。

## 工作方式

- 在 herdr 创建的独立 git worktree 中工作，分支 `feature/im-4.0-p2-ui`，基于 `feature/im-4.0` 的契约提交。
- 可以在该分支上提交；不要推送，不要合并到其他分支。提交信息末尾加 `Co-Authored-By: Codex <noreply@openai.com>`。
- 完成后由 Claude 审查并合并。

## 可以修改的范围

- `app/src/main/java/me/ayuilos/miffan/ui/im/thread/` 下的所有文件：替换占位页 `AgentThreadPage.kt`，按需新增组件文件。
- `RouteActivity.kt` 中 `entry<Screen.Thread>` 的一处调用（用于传入 `focusMessageId`）。
- 字符串资源：新增 `im_thread_` 前缀的键，写入 `values` 与 `values-zh`，并用 `locale-tui set` 为 `values-zh-rTW`、`values-ja`、`values-ko-rKR`、`values-ru` 写入你自己的翻译（含撇号时转义为 `\'`）。

## 禁止修改

`data/`、`service/`、`di/`（除非只为注册新 VM，但本任务不需要）、数据库、`ChatService`、`ThreadTimeline`、`AgentThreadVM` 的公开 API。如果发现 VM 缺少必须的能力，在 `docs/im/P2_UI_NOTES.md` 写明需求并用最小的界面侧绕行，不要改数据层。

## 可用 API：`AgentThreadVM`（`ui/im/thread/AgentThreadVM.kt`）

| 成员 | 说明 |
| --- | --- |
| `assistant: StateFlow<Assistant?>` | 顶栏头像与名字 |
| `timeline: StateFlow<List<TimelineItem>>` | 按时间升序的时间线项；直接渲染，不要自行排序或推断引用 |
| `loaded: StateFlow<Boolean>` | 首屏是否加载完成（区分“空线程”和“加载中”） |
| `generatingSegmentIds: StateFlow<Set<Uuid>>` | 是否在生成；非空时显示停止按钮 |
| `topics: StateFlow<Map<Uuid, ThreadTopic>>` | 话题标题（`title` 可能为空，此时用引用条里的问题文本做标签） |
| `topicFilter: StateFlow<Uuid?>` / `setTopicFilter(id?)` | 只看这个话题 |
| `replyTarget: StateFlow<TimelineItem.Message?>` / `setReplyTarget(item?)` | 回复条；话题过滤时不显示回复条（发送自动归入该话题） |
| `errors: StateFlow<List<ChatError>>` / `dismissError(error)` | 本线程的错误 |
| `send(parts)` / `sendText(text)` | 发送（图片、文件用现有的 `UIMessagePart` 与 `FilesManager` 流程构造） |
| `regenerate(item)` | 仅当 `item.canRegenerate` |
| `stop()` | 停止生成 |
| `startNewTopic()` | “换个话题”，放在顶栏溢出菜单 |
| `loadMore()` | 滚动到顶部时加载更早的话题段 |

`TimelineItem`（`data/thread/ThreadTimeline.kt`）：

- `DateSeparator(at)`：居中时间标签，格式为“今天 21:03 / 昨天 21:03 / 10月3日 21:03 / 2025年10月3日”（按语言本地化）。
- `Message`：`quote`（非空时在气泡顶部画引用条，颜色由 `quote.topicIndex` 决定；`explicit` 为真表示用户主动回复）、`groupedWithPrevious`（为真时不显示头像，缩小上间距）、`streaming`、`canRegenerate`、`topicIndex`、`ref`。
- `Notice(notice)`：居中灰色系统提示，如“Miffan 调整了自己的设定：… · 查看 · 撤销”。`undoable` 与回调先只做界面（撤销按钮调用一个空的 `onUndoNotice` 参数），P4 会接上。
- `Typing(segmentIds)`：末尾的“正在输入”行，使用小号 Miffan（见下文）。

话题颜色：用 `topicIndex % N` 从一组与主题协调的颜色中取色（基于 `MaterialTheme.colorScheme` 推导，不要写死十六进制色值），引用条与“只看”标签使用同一颜色。

## 必须实现的画面（对照设计稿）

| 设计稿 | 要点 |
| --- | --- |
| 02 / 14 | 时间线、分组头像、引用条、时间标签；深色主题正常 |
| 03 | 顶部“只看：话题 ✕”标签；输入框占位文字“在“话题”里说点什么…” |
| 04 | 长按消息弹出菜单：回复、复制、只看这个话题、重新生成（仅 `canRegenerate`）、更多（收藏、朗读、翻译沿用现有能力，能复用就复用，不能就先不放） |
| 16 | `Typing` 行：小号 Miffan 吃饭表情加“正在输入…”。用 `AssistantAvatar(..., generationPhase = AssistantGenerationPhase.Waiting)`；流式回复中用 `Responding`，未完成推理用 `Reasoning` |
| 17 | 工具调用折叠成人话：已完成的步骤合并为一行摘要（如“查了 3 个网页 ›”，点击展开现有的工具详情组件），进行中的步骤显示“正在查资料…” |
| 19 | 错误：AI 位置一条柔和气泡“网络有点卡，没收到回复”（尽量按错误类型给出人话），“重试”调用 `regenerate` 最后一条可重生成的消息；头像中性表情加提示标记 |
| 20 | `Notice` 行样式 |
| 21 | 点击引用条：滚动到原消息并短暂高亮；离开底部时显示“回到最新 ↓” |
| 22 | 按住说话：复用现有语音识别（`LocalASRState`）；如果现有能力不支持按住说话，就做成点击开始、再点结束，并在 NOTES 中说明 |
| 23 | “+”面板：照片、拍照、文件三项，复用现有选择与上传流程 |
| 25 | 空线程：大号 Miffan、伙伴自我介绍（伙伴 system prompt 的第一句或名字）、最多 3 个建议话题（来自伙伴的 quick messages；没有就不显示） |

另外：

- 左滑消息进入回复（时间线手势）。
- 顶栏：返回、头像加名字（点击进入现有伙伴设置页 `Screen.AssistantDetail`，P5 会换成资料卡）、溢出菜单（换个话题）。
- 生成中时发送按钮变为停止。
- `Screen.Thread.focusMessageId` 非空时打开即滚动到该消息并高亮（P5 的搜索结果会用到）。

## 复用现有组件

- 正文渲染：`ui/components/richtext/Markdown.kt` 的 `MarkdownBlock`；推理、工具、附件等部分参考 `ui/components/message/` 下的现有组件，能复用就复用，不要复制粘贴大段代码。
- 头像：`AssistantAvatar`，注意它会消费点击，需要把行为通过 `onClick` 传入。
- 不显示模型名、token、分支切换器。

## 验收

1. `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` 通过，新文件无 lint 告警。
2. 使用独立的模拟器 `RikkaHub_API_35_16K`（`emulator -avd RikkaHub_API_35_16K -port 5556`，之后所有 adb 命令加 `-s emulator-5556`；`Pixel_8_API_35` 由 Claude 使用，不要碰）。安装调试包后需要先完成引导：选择“Use another provider”，给任一服务商添加一个模型并在默认模型里选中它（API Key 可以留空）。手动走通：打开线程 → 发送 → 生成中 → 错误与重试（没有可用 API Key 时会出错，这正好用来检查错误态）→ 长按菜单 → 回复 → 只看话题 → 引用跳转。
3. 把关键画面截图保存到 `docs/im/p2-screens/`（转成 WebP 后提交；本机没有 cwebp，可在 /tmp 建 Python venv 安装 Pillow 来转换），并在 `docs/im/P2_UI_NOTES.md` 中逐条对照上表说明完成情况、偏差与原因。

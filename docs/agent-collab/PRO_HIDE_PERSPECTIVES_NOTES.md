# 专业模式隐藏伙伴私有视角交付说明

分支：`feature/pro-hide-perspectives`。依据：`PRO_HIDE_PERSPECTIVES_SPEC.md`、`AGENT_COLLAB_PLAN.md`、P3/P4 与 `PARTNER_RECALL_NOTES.md`。

## 实现路径

- `ConversationDAO`：按助手、未归类、文件夹、标题检索的 Flow/分页查询，全局分页、置顶列表、近期快捷会话和会话按日计数均在 SQL 中排除 `chat_id IS NOT NULL`；分页和 limit 之前过滤。助手切换的默认会话沿用同一个已过滤的助手列表。原有 IM 最新线程、摘要、变更戳和近期工具查询保持隔离。
- `MessageFtsManager.search`：专业模式只搜索普通会话；IM 主人的全局搜索仍只搜索主人线程；IM 伙伴回忆保留全部主人线程及调用者自己的视角。过滤位于排序和 50 条上限之前。`conversation_search` 沿用调用模式的 SQL 过滤；`recent_chats` 原有专业模式范围已经排除视角，IM 混合聊天逻辑不变。
- 启动恢复：专业模式恢复的上次 ID 如果是视角，改选同一助手的普通会话；没有普通会话时使用新草稿。
- `Screen.Chat` 的专业模式入口先解析目标，再创建 `ChatPage`/`ChatVM`。通知、收藏、搜索结果及其他 ID 导航统一受保护，视角改进 `Screen.PartnerChat(chatId)`；聊天已删除而视角残留时，以新的草稿 ID 沿用现有“找不到会话则新建草稿”行为，避免编辑孤立视角。普通会话的草稿、附件和聚焦参数保留；跳转保留已有根页，避免连续通知增加空聊天页面。IM 的 `ImChatRedirect` 未修改。
- 收藏：专业模式 NODE 收藏列表排除视角引用，保存的收藏不删除；轻松模式列表和底层原始收藏读取不变。
- 统计：专业模式会话数量、消息数量、token 和活跃热力图排除视角；轻松模式统计保留原范围。调试页的展示会话数量在专业模式下排除视角，轻松模式保留原来的原始数据库数量。
- Web：列表、分页、标题和全文搜索复用上述过滤；专业模式的按 ID 读取、流、发送、修改、删除和分叉入口拒绝视角，返回已有 `Conversation not found`。Web 尚无伙伴聊天页，不能套用原生 `PartnerChat` 路由，因此保持现有 404 处理，避免从 Web 编辑私有视角。
- 没有修改专业模式布局、数据库版本或迁移；没有删除、迁移任何聊天数据。

## 有意保留

- `getAll`、`getAllIds`、`countAll`、按 ID 原始读取、伙伴视角查询和索引重建继续包含视角；FTS 索引仍完整，以支持伙伴回忆。
- 助手删除/内部清理改用显式全量助手读取，避免展示查询的过滤使内部清理遗漏视角。
- 本地导出、WebDAV/S3 备份与恢复沿用整个 SQLite 数据库文件；全量数据不会因展示过滤丢失。单段聊天导出只能从专业模式中可见的普通会话进入，导出器本身未改变。
- 文件夹本身没有计数字段；列表由已过滤的文件夹查询生成。IM 事件显式指定分段时改为按原始 ID 判断视角，保持既有拒绝行为，不依赖已过滤的列表；其他生成服务、审批、伙伴聊天存储和 IM 时间线没有改变。

需要后续产品决定：专业历史页的“删除全部”和轻松伙伴页的“清空聊天”共用按助手全量清理，本次保留原来的全量范围（包括视角）；是否应只清理主人会话没有在本规格中确定，因此未调整。验收未执行这些删除操作。

## 验证

命令（离线、两个 worker）：

```bash
./gradlew --offline --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

- JVM：首次独立验收 854 项通过；最终补充回归后与第二项一同运行，868 项全部通过（本任务基线及测试共 855 项），0 failure/error/skipped。新增 6 项路由/恢复/返回栈测试和 1 项 IM 显式事件隔离回归测试；现有搜索和工具测试随新的专业模式范围更新。备份设置、导入等已有测试仍通过。
- Debug app/test APK 均构建成功。Lint：0 error、376 warning、6 hint，没有屏蔽规则。
- emulator-5560：指定 `ProfessionalConversationQueryTest`、`PartnerRecallQueryTest`、`Migration_34_35_Test`，最终 APK 下共 8 项通过。测试仅使用独立内存/文件 fixture，未打开或清理用户数据库。
- 查询测试逐项覆盖所有新增过滤的 Flow/分页、limit 前过滤、会话统计、专业消息/token/热力图统计及原始统计、专业收藏和原始收藏；原始 getAll/getAllIds、按 ID、助手全量和伙伴视角读取仍包含数据。
- FTS 用 55 个其他伙伴视角验证 limit 前隔离；专业模式即便传入 perspectiveAssistantId 也不允许视角，IM 主人及调用伙伴范围保持原行为。

实际界面验收（2026-10-12）：

1. 原模式是轻松模式，从“我”页切到专业模式。Translator 抽屉只显示普通的 `Asking How They Are`；切换 Default Assistant 后，抽屉保留 `Writing Update`、`问候Translator` 等普通会话，没有“与…的私聊”或“群聊：…”视角。近期快捷项也没有视角。
2. 全文搜索 `doing`，只有普通会话 `Asking How They Are` 返回；包含同一文字的私有视角均未显示。
3. 用 `conversationId=c15d22aa-19b3-4167-aab2-77c19cee7129` 冷启动，进入 `Default Assistant · Translator` 伙伴私聊页；用通知的 SINGLE_TOP 行为投递群视角 `8036c1be-357e-4297-8dbf-12dc694c836d`，进入三伙伴群聊页。连续跳转保留已有专业模式根页，冷启动跳转才补一个根页；不实例化视角的专业 ChatVM。
4. 设置中切回轻松模式，在消息列表打开既有私聊，问候与翻译等消息完整；既有三伙伴群聊的秋天英文短诗和日文译文完整。没有发送测试聊天消息。
5. 前后只读复制数据库/WAL 并比较全字段：40 段会话、133 个节点、5 个聊天、12 个成员、23 条聊天消息全部逐行一致，数据库始终版本 36。全程覆盖安装，没有卸载、清空或迁移数据。

截图及验证日志保存在宿主机 `/tmp/pro-hide-*.png`、`/tmp/pro-hide-final-build.log`、`/tmp/pro-hide-device-tests-final.log`。最终同一 Gradle 进程成功完成要求的检查。第二项开发后的最终复查补充了 IM 显式事件隔离回归：展示列表已排除视角时，也仍然拒绝将事件投递到私有视角 ID。firstInstallTime 保持 `2026-10-06 10:03:47`。模拟器宿主图形进程中途崩溃，保留 userdata、禁用 Vulkan 冷启动后再核对，所有原行仍一致。

第一轮 APK 暴露未注册 DAO 注入问题，改为从已注册 AppDatabase 取得 DAO 后重新构建并完成上述验收。

## 收尾

主人在本任务进行期间追加了 `PARTNER_RECALL_2_SPEC.md`，要求同分支另一个提交。该规格不纳入本次提交；模拟器按新指示保留到两项任务全部验收后，再关闭并运行 `./gradlew --stop`。原生依赖不纳入提交。

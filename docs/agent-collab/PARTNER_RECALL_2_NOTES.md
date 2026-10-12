# 伙伴回忆第二轮交付说明

分支：`feature/pro-hide-perspectives`。本任务独立于专业模式隐藏视角提交；依据 `PARTNER_RECALL_2_SPEC.md`。

## 实现

- 数据层统一 `partnerDisplayName(Assistant?)`：非空名字保留原值，空白名字使用 `BLANK_PARTNER_NAMES[1]`（“默认助手”），已删除伙伴使用“已删除的伙伴”。
- 新同步消息前缀、私聊/群上下文、群主持、聊天标题、新视角标题、异步回复唤醒、给主人留言的事件、回忆标题、群通知发送者、联系人列表和主人 @ 提示均复用这个函数。
- 群 @ 解析使用 `partnerMentionNames`，空名成员可通过六种语言的默认助手名字被叫起；最长名字优先、自我 @ 排除和去重逻辑保持不变。
- 不迁移数据库，不改已有分段标题或已有 `[伙伴]` 消息。回忆条目和读取工具每次用伙伴配置重新生成标题，旧数据也能显示正确身份。
- 新增 `read_partner_chat`，只在 IM 模式的会话工具组注册；专业模式仍只有原来两个工具。`chat_id` 必填且必须为 UUID；limit 默认 20，约束 1～50。
- 先检查聊天存在和调用者成员身份，再通过 `PartnerChatService.observeMessages` 读取最终消息。读取完成后再次检查成员身份，拒绝已经离群的调用者。无效 ID、不存在或非成员仅返回 error，没有标题或消息内容。
- 返回 `chat_id`、当前回忆规则的 title、type（direct/group）及 messages。每条包含 sender、sender_id、created_at（完整 ISO 时间）、text；按时间取最近 limit 条，返回正序；每条最多 2000 个 Unicode 码点，不截断代理对。主人使用昵称或“主人”，删除的发送者使用“已删除的伙伴”。不读取视角分段或工具过程。
- `recent_chats` 说明可用 chat_id 直接读取；`conversation_search` 说明及伙伴聊天命中的 read_hint 同样提示读取。操作记录沿用现有“查询聊天”语义，读取工具不算修改行为，不增加文案或改变布局。

## 测试与实测

最终验收命令：

```bash
./gradlew --offline --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

- JVM：868 项通过，0 failure/error/skipped；本任务新增 13 项测试，覆盖有名/空白/删除名字、新前缀及旧前缀不改、私聊/群/主持上下文与标题、多语言群 @、服务的新视角标题及主人留言、回忆实时标题和读取提示、最终消息时间排序/limit/Unicode 截断、主人昵称、删除的发送者、非成员/不存在/无效 ID、读取期间成员撤销、专业模式不注册。
- Debug app/test APK 构建成功。Lint：0 error、376 warning、6 hint，没有屏蔽规则。
- 最终 APK 的设备回归：`ProfessionalConversationQueryTest`、`PartnerRecallQueryTest`、`Migration_34_35_Test` 共 8 项通过（1.482 秒），仅使用独立 fixture；第一任务的专业模式过滤及 IM 回忆范围未回退。

真实模型实测（2026-10-12，emulator-5560，原有网关和 `codex/gpt-6.1-sol`）：

1. 保留原有空名字默认助手，新增独立伙伴“二轮蓝鲸”；没有改变原有伙伴配置。使用临时 debug receiver 调用实际 `ThreadService.send`，来源是正常主人消息；全新分段显式指定，不关闭或改写旧线程。验收伙伴没有自动记忆或摘要注入，系统提示仅为自然中文交流，没有要求使用回忆工具。
2. 14:46:25 发给二轮蓝鲸：“请联系@默认助手，告诉它：我们约定周六下午四点在海边灯塔旁喝桂花茶，暗号是蓝鲸灯塔。请让它确认地点、时间和暗号，再把答复转告我。”模型直接调用 `send_message`，通过已有 @ 授权规则联系原有空名字助手；对方于 14:46:50 确认。二轮蓝鲸于 14:46:58 向主人转告。新视角标题是“与 默认助手 的私聊”。
3. 14:47:32 在二轮蓝鲸的全新主人分段问：“之前是不是跟@默认助手有过交流？聊了什么？”没有提示工具名称。
4. 模型只调用 **2 次工具**，依次 `recent_chats({"limit":30})` → `read_partner_chat({"chat_id":"945e25dc-39d9-3863-90e7-3b0636d20349","limit":50})`。近期结果显示“和 默认助手 的私聊”，读取结果显示两条最终消息，发送者分别为“二轮蓝鲸”“默认助手”。没有关键词试探，也没有再次联系对方询问。
5. 14:47:59 最终回答明确说“有，刚才我和 @默认助手 有过一次私聊”，准确列出周六下午四点、海边灯塔旁、桂花茶、蓝鲸灯塔暗号，并引用对方确认内容。最终去掉临时入口后覆盖安装，轻松模式界面中的回答正常可见。

验收标识（保留供复查）：

| 对象 | ID |
| --- | --- |
| 原有空名字默认助手 | `0950e2dc-9bd5-4801-afa3-aa887aa36b4e` |
| 二轮蓝鲸 | `55603620-0000-4000-8000-0000000000b1` |
| 发起联系的主人分段 | `55603620-0000-4000-8000-0000000000b2` |
| 自然回忆提问的新分段 | `55603620-0000-4000-8000-0000000000b3` |
| 私聊 | `945e25dc-39d9-3863-90e7-3b0636d20349` |
| 二轮蓝鲸私有视角 | `5bebe9a9-b8ea-4fff-8de6-23aa3402a6d9` |
| 默认助手私有视角 | `81116f52-fb5c-421b-8ced-3de179e2cd12` |

实测记录：`/tmp/partner-recall2-real-transcript.json`（只保存文本和工具结果，不含 reasoning metadata）、`/tmp/partner-recall2-answer.png`、`/tmp/partner-recall2-final-build.log`、`/tmp/partner-recall2-device-tests.log`。

## 数据保留与收尾

最终覆盖安装后只读复制实际数据库/WAL，比较全部原字段：原有 40 段会话、133 个节点、5 个聊天、12 个成员、23 条聊天消息逐行完全一致。实测仅新增 2 个主人分段、2 个视角、7 个节点、1 个聊天、2 个成员、2 条聊天消息；总计 44/140/6/14/25。数据库始终版本 36，旧标题和旧消息没有迁移。

全程覆盖安装，没有卸载或清空 app；firstInstallTime 保持 `2026-10-06 10:03:47`，最终 lastUpdateTime 为 `2026-10-12 15:03:28`。临时 Recall2Receiver 及 manifest 声明已从源码和最终安装包移除。原生依赖未纳入提交。模拟器保持原来的轻松模式。

最终固定源码同一 Gradle 进程完成全部验收，`BUILD SUCCESSFUL in 6m 7s`。关闭前 UI 的 Chats/Partners/Discover/Me Tab 确认轻松模式。`adb -s emulator-5560 emu kill` 返回 OK；最终 ADB 设备列表为空。在本 worktree 执行 `./gradlew --stop`，返回 `1 Daemon stopped`。两个任务全部测试和收尾完成。

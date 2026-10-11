# P3 数据交付说明：伙伴私聊

分支：`feature/partner-chat-data`。依据：`P3_DATA_SPEC.md`、`AGENT_COLLAB_PLAN.md` 第 3 阶段及 `P2_DATA_NOTES.md`。

## 实现与接口

- 实现 `DefaultPartnerChatService`，在 Koin 中注册 `PartnerChatService`；提供聊天、消息、摘要、typing、授权联系人观察及撤回授权。已提交的模型和服务接口签名未修改，`ui/` 文件未修改。
- `sendFromOwner` 先存储主人消息后返回；后台按有效 mentions 的顺序串行发言，无 mentions 时选择最近发言的伙伴。全新的空聊天没有发言者时使用第一位成员。
- 每位成员使用自己的 `Conversation(chatId = C, assistantId = X)`。同步保留独立 USER 节点、名字前缀和来源：伙伴为 `Partner(senderId, exchangeId, chain)`，主人为 null，昵称为空时用“主人”。生成回复已在视角里，同步时跳过。
- IM 中 `send_message` 的初始发送补成发送者视角中的 ASSISTANT 镜像。视角正忙时不等待它，避免它向 IM 汇报与 IM 联系工具互相等待；下一次同步补上镜像。
- 游标按数据库追加顺序推进，不比较 UUID 或毫秒时间。生成期间有新消息到达时，游标不会越过尚未同步的其他参与者消息或尚未镜像的发送。
- 视角的私聊说明通过 `threadContext` 注入；标题固定为“与 <对方> 的私聊”，不运行自动标题和建议写回。生成、工具、审批与主人权限检查沿用 `ChatService`；等待最终助手回复时可以跨越审批暂停。

## 存储、隔离与清理

数据库 **34 → 35**，显式迁移注册到 DI，并导出 `35.json`：

- 新建 `partner_chat`、`partner_chat_member`、`partner_chat_message`；成员和消息外键随聊天删除。
- 实际会话表名为 `conversationentity`，新增可空 `chat_id`；旧会话保持 null。
- 消息增加内部 `chain TEXT NOT NULL DEFAULT '[]'`，持久化调用来源，保证进程重启和数据库备份后来源仍在；不改变 `PartnerChatMessage` 契约。
- DIRECT id 由规范化伙伴对产生稳定 UUID，并由主键保证同一对伙伴只有一个聊天，包括并发创建和重新打开数据库。
- `partnerContactGrants` 新增 DataStore 读写键 `partner_contact_grants`，Settings JSON 备份往返保留授权，旧备份默认无授权。聊天表随现有数据库备份恢复。
- `chat_id != null` 从 IM 分段观察、每伙伴最新会话、近期路由/引用候选、`deliverFrom` 选择、分段摘要和 IM 全文搜索中排除；显式 IM 投递也不能指向私聊视角。专业模式的会话列表、分页和全文搜索保留视角及其标题；全文索引统一建立，查询按当前模式隔离。
- 启动时 `retainChats` 的有效 id 集合包含数据库中的伙伴聊天，不会清掉其置顶和分组。
- 删除伙伴时取消相关 DIRECT 聊天任务，停止并释放双方视角会话，删除视角、附件及聊天/成员/消息，清理相关授权、置顶和分组。删除前等待视角写回退出。

## 工具与发言

- 仅轻松模式构建 `list_contacts` 和 `send_message`。通讯录不包含自己；伙伴包含 id、名字、system prompt 开头简介和授权状态；仅私聊视角包含 `owner`。
- 未授权伙伴对使用现有 `needsApproval` 审批门；执行获批调用时先写入规范化授权，再投递。不存在、自己、链上联系人、超过两跳，以及超过六条交换预算的调用返回 `refused`。
- 每个根调用创建 exchangeId，第二跳继承根交换及调用链，整个交换共享六条预算；发送时为最终回复预留一个位置，避免嵌套或并行调用占满预算后丢失已经生成的外层回复。
- 最多同步等待 90 秒，返回契约规定的 `replied` / `pending` 和 `chat_id`、`partner_id`、`reply`；超时时间可注入测试。
- IM 发起的超时调用在回复写回后，通过 `partner_replied` 事件唤醒原 IM 分段，投递文本包含对方回复。视角中的第二跳超时只写聊天，不唤醒发起方。
- 视角中发给 `owner` 时，在该伙伴最新未关闭 IM 分段追加 `partner_note` Event USER 节点和伙伴 ASSISTANT 文本节点，不生成回复；沿用助手回复的未读计数和后台完成通知。
- 伙伴间私聊不发普通流式/完成通知，也不进入 IM 未读计数；审批通知保留。主人参与私聊的未读按规格留到 P4；可用 chatId 作为 `ThreadListState` key。

## 后台保活

- `GenerationKeepAlive` 管理每轮租约，Android 后端和加急任务后端可用 fake 替换测试。
- 前台服务已经运行时直接登记新租约，不调用 `startForegroundService`。服务启动结果确认失败才进入退路。
- 退路为共享 `ChatGenerationKeepAliveWorker`，使用 `setExpedited(RUN_AS_NON_EXPEDITED_WORK_REQUEST)`，实现低版本所需 `getForegroundInfo`，manifest 合并声明 WorkManager SystemForegroundService 的 `dataSync` 类型。等待 Worker 真正启动后再运行模型，最后一个租约释放后结束 Worker。
- 联系、生成、回复落库和异步唤醒各自持有租约；下一段工作先获取，上一段再释放。排队的迟到事件也保留交接租约。
- 这是当前进程中的生成保活；没有新增进程被杀后的生成任务重放或恢复机制。

## 验证

| 检查 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | 774 项通过；比 P2 增加 22 项，覆盖来源/权限、同步游标、并发镜像、审批/撤权、拒绝、两跳、同步/超时、第二跳不唤醒、主人发言、owner note、删除、组织保留及保活 fake。 |
| `:ai:testDebugUnitTest` | 222 项通过；新增 Gemini 连续 USER 请求合并测试，保留工具调用/结果顺序与原始消息。 |
| `Migration_34_35_Test` | emulator-5560 上 3 项通过：34→35 保留旧数据、Room 查询隔离、规范化 DIRECT 唯一性与嵌套回复预算。 |
| `:app:assembleDebug` / `:app:assembleDebugAndroidTest` | 通过；最终设备验收同时构建并保留 app/test APK。 |
| `:app:lintDebug` | 通过：0 error、373 warning、6 hint；未屏蔽规则。补齐了 WorkManager SystemForegroundService 的 dataSync 声明。 |
| emulator-5560 后台真实模型 | 通过，详情如下。 |

Provider 适配：OpenAI 两种请求适配器保留连续 USER；Anthropic Messages 支持并在服务端合并连续同角色消息；Gemini 在 HTTP 请求构造的最后一步合并相邻 user contents，数据库节点和权限来源不合并。参考 [Anthropic Messages](https://platform.claude.com/docs/en/api/messages/create)、[Gemini 对话历史](https://ai.google.dev/gemini-api/docs/generate-content/text-generation) 和 [WorkManager 加急工作](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)。

后台实测：模拟器停留在 Nexus Launcher，未启动 app Activity。2026-10-11 23:15:13 投递事件，日志先显示 `ForegroundServiceStartNotAllowedException`，随后 `ChatGenerationKeepAliveWorker` 于 23:15:13.279 启动，23:15:17.556 SUCCESS。最终安装版本又于 23:29:24.360 启动 Worker，23:29:31.902 SUCCESS，落库并通知 `P3_BACKGROUND_FINAL_OK`，模拟器仍停留在 Launcher。宿主机 3425 网关生成 `P3_BACKGROUND_OK`，数据库中 Event USER 与 ASSISTANT 回复均已落库，`chat_completed` 的 id=1 通知包含同一文本。

数据保留：测试前库版本 34，21 段会话、75 个节点；最终两次后台实测后版本 35，23 段会话、79 个节点（增加两个独立测试分段、四个测试消息节点）。原有 21 段会话全部保留，75 个节点的 id、message、origin、revision 逐行完全一致。firstInstallTime 始终为 `2026-10-06 10:03:47`。没有卸载、清空 app 或运行会清库的其他设备测试。

迁移测试使用独立 fixture 数据库及内存 Room 数据库；MigrationTestHelper 的原始连接需显式启用 `PRAGMA foreign_keys = ON` 才能检查级联，生产 Room 会启用外键。

验收命令：

```bash
./gradlew --offline --no-daemon :app:testDebugUnitTest :ai:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug

ANDROID_SERIAL=emulator-5560 ./gradlew --offline --no-daemon \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.ayuilos.miffan.data.db.migrations.Migration_34_35_Test \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

后台触发（新分段，避免影响现有话题；text 使用无空格文本，避免 adb 的远端 shell 参数拆分）：

```bash
"$ANDROID_HOME/platform-tools/adb" -s emulator-5560 shell input keyevent KEYCODE_HOME
"$ANDROID_HOME/platform-tools/adb" -s emulator-5560 shell am broadcast \
  -a me.ayuilos.miffan.debug.DELIVER_EVENT \
  -n me.ayuilos.miffan.app.debug/me.ayuilos.miffan.debug.DeliverEventReceiver \
  --es text '请只回复P3_BACKGROUND_OK，不调用工具。这是P3后台保活验收。' \
  --es summary P3_BACKGROUND_CHECK \
  --es segment 55603500-0000-4000-8000-000000000002
```

最终同一 Gradle 进程执行 JVM 测试、构建、lint 与指定迁移类，全部成功，耗时 4m 56s。首次 lint 遇到 Kotlin 分析器内部异常，后续固定源码、使用新 JVM 正常扫描；扫描发现的 WorkManager manifest 声明缺失已修复，没有调整 lint 规则。

原生依赖没有纳入提交。

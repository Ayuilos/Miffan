# 伙伴权限隔离与环境感知交付记录

分支：`feature/agent-isolation`。依据 `AGENT_ISOLATION_SPEC.md`，分为 P0 和 P1 两个提交，不改数据库版本或界面。

## P0 规则落点

- 统一上下文：`AgentAccess` 在 `ChatService.handleMessageComplete` 根据实际回合构建，携带 callerId、ownerInitiated、memoryOwnerId；生成器、回忆、联系人和扩展工具复用同一个对象。自动记忆提取按提取时的当前伙伴设置构建同种上下文。
- R1：`MessageFtsManager.search` 在 SQL LIMIT 前约束调用者 assistant_id。主人发起仅含该伙伴的主人私聊；IM 可额外包含自己的伙伴视角。专业模式工具也必须传入上下文。
- R2：`recent_chats` 和 `read_partner_chat` 的成员检查保留；FTS 同时检查当前 partner_chat_member，已退出的群不会因遗留视角而继续命中。
- R3：伙伴发起的回合不查询主人 recent 列表，FTS 禁止主人私聊。两个回忆工具的说明明确本回合范围。记忆 prompt 注入保持现有行为；伙伴回合仍不提供记忆写工具。
- R4：`MemoryRepository.updateContent/deleteMemory` 接受访问上下文，外部编号和不存在编号产生相同异常和消息。生成器和自动提取的修改都传入访问上下文；创建使用上下文选定的 owner。
- R5：`list_contacts` 只给公开标签名（tags），无标签则省略。另发现 `partnerMentionHints` 和群聊 perspectiveContext 也注入其他伙伴的提示词首行，已同步改为标签名。
- R6：`catalog(access)` 对其他伙伴只序列化 id、name；自己完整。跨伙伴变更在 preview 的任何资源加载/验证前拒绝伙伴回合，避免通过验证错误或摘要泄露配置；已有审批保留。preview capability 绑定调用者上下文，apply 不能跨调用者复用。
- R7：没有新增凭据输出；现有 MCP 目录凭据回归测试保留。

## P1 实现

- IM 回合默认注册只读的 `my_environment`，无开关、无审批，主人与伙伴发起的回合均可用；专业模式不注册。
- 返回自己的显示名（复用 partnerDisplayName）、头像类型、当前模型/提供商显示名、实际注册工具对应的人类可读能力、工作空间和电脑名称、成员群和成员显示名、已授权联系人显示名。当前主人 @ 授权也算当轮可联系联系人。
- 能力从本回合的工具快照和动态电脑工具读取；记忆区分全局/自己及伙伴回合只读。skill 名与 use_skill 复用同一筛选函数，排除不可用工作空间 skill、全局 skill 和被内置 skill 覆盖的同名项。
- 绑定 MCP 只列名称。集成中发现 McpManager 原来使用界面当前选中的伙伴绑定，已改为 ChatService 传入同一 AgentAccess，避免后台 A 意外获得界面 B 的 MCP 工具；无上下文的直接主人调用保留原选择行为。
- 显式构建白名单 JSON，不序列化 Assistant、提供商、WorkspaceEntity、RemoteHostEntity 或 MCP 配置。无提示词/偏好、其他伙伴的模型/绑定、头像路径、工作空间路径、连接 URL、API key、请求头或 OAuth。
- 不修改 UI、ThreadStepRecord 文案映射或数据库；扩展管理开关与原有审批流程保留，未采用背景清单中额外提出的“IM 默认扩展目录”。

## 有意保持原样的调用方

- SearchVM、ImSearchVM、Web ConversationRoutes 的主人全局搜索不传 access：仍搜索全部主人私聊，排除伙伴视角。
- 记忆页面（AssistantDetailVM、ImMemoryVM、工具结果 UI）的主人编辑/删除不传 access，保留原行为，缺失删除仍是 no-op。
- 扩展服务直接调用不传 access 时保持主人完整目录与权限；工具包装层必须传入 access。
- 最近聊天参考在专业模式仍遵从伙伴开关，IM 默认启用；read_partner_chat 的最终消息输出和成员复查不改。

## 验证

最终源码 JVM：882 项通过，0 failure/error/skipped。各规则的 JVM 覆盖：

| 规则 | 测试 |
| --- | --- |
| R1 | PartnerSearchTest；ConversationRecallTest.ownerSearchAlwaysPassesCallerInBothModes |
| R2 | ReadPartnerChatTest 的非成员、成员撤销复查；ConversationRecallTest 的成员列表过滤 |
| R3 | ConversationRecallTest.partnerTurnSkipsOwnerRecentQueryAndPassesRestrictedSearchInBothModes；GenerationHandlerOriginTest |
| R4 | AgentAccessTest：私有/全局记忆、同编号外部记录与不存在记录的异常类型和消息完全相同、主人 UI 调用保留 |
| R5 | PartnerChatTest 的标签输出和群 prompt；PartnerMentionsTest、PartnerDisplayNameTest；AgentMcpIsolationTest |
| R6 | ExtensionManagementServiceTest：其他伙伴只有 id/name、全部定向变更类型的预览前拒绝、拒绝时不读取设置、主人直接视图、preview 调用者绑定与一次性消费 |
| R7 / P1 | MyEnvironmentToolTest；原有 MCP 目录凭据脱敏测试 |

P0 的 emulator-5560 PartnerRecallQueryTest：2 项通过（0.954 秒），使用真实 SQL，覆盖双模式主人工具搜索、伙伴回合、SQL LIMIT 前过滤、成员撤销及主人全局搜索保持原样。最终不含临时入口的测试 APK 已覆盖安装，PartnerRecallQueryTest 再次 2 项通过（1.720 秒）。最终同一构建的 :app:testDebugUnitTest、:app:assembleDebug、:app:lintDebug、:app:assembleDebugAndroidTest 全部通过，BUILD SUCCESSFUL in 6m 18s。Lint SARIF：0 error、382 warning；没有增加屏蔽规则。

构建环境使用 Android Studio JBR 和本机 Android SDK，离线模式：
```bash
./gradlew --offline --no-daemon --max-workers=2 :app:testDebugUnitTest :app:assembleDebug :app:lintDebug :app:assembleDebugAndroidTest
```
首次构建补齐 worktree 缺失的非敏感原生依赖：从主仓库复制 rdp/build/native 和 .deps/p6a 的校验源码包（忽略文件，不提交），没有复制签名或凭据配置。

## 真实模型实测

2026-10-12 18:12:32–18:13:09，emulator-5560，沿用已有提供商和 GPT-6.1-Sol。临时 androidTest 入口调用实际 ThreadService/ChatService/DefaultPartnerChatService，已从交付源码移除；不需要额外 APK receiver。首轮临时入口被 JUnit 拒绝非 void 返回类型，未调用模型、未创建数据；修正后一次通过，三个场景合计 37.128 秒。

1. 新增独立 A/B 验收伙伴和 A/B 各一条主人私聊 fixture。在提问前，用 A 的实际工具搜索范围查询只有 B 记录包含的关键词，结果为空。主人向 A 询问该关键词对应的事实，模型调用 conversation_search，只返回 A 刚发出的搜索请求；B 的记录 ID、独占事实和标题没有出现，模型正确说明没有找到对应历史事实。（提问本身含关键词，因此发起请求后自己的请求也会命中。）
2. 通过现有 send_message 服务从 B 向 A 发出查找 A 主人私聊关键词和列出最近聊天的请求。A 的视角最新 USER 节点是 MessageOrigin.Partner，ownerTrigger 为 null。真实模型调用 conversation_search 和 recent_chats；结果仅有 A/B 私聊与验收群，没有任何 owner_thread、A 主人记录 ID 或其独占事实。模型明确说明该伙伴聊天视角无法访问主人单独记录。
3. 主人在全新分段自然问 A：“你是谁、能做什么、在哪些群？也告诉我你使用的模型。”没有提示工具名称。模型调用 my_environment({})，输出自己的姓名、default 头像类型、GPT-6.1-Sol/OpenAI、最近聊天参考和联系伙伴能力、自己的验收群及 A/B 成员、已授权 B。答案正确，无路径、URL 或其他伙伴配置。

保留以下新增验收对象便于复查：

| 对象 | ID |
| --- | --- |
| 隔离验证A-56e02098 | `84582957-5004-4cf1-aa59-d229075e602b` |
| 隔离验证B-56e02098 | `dd739cdd-8c94-4709-ac5d-b4db9fe7c266` |
| A 主人私聊 fixture | `14c14f91-e58d-43fb-8250-759db64a7603` |
| B 主人私聊 fixture | `b661985a-38c2-4295-85fa-af390242ba66` |
| 验收群 | `8c238921-f248-4695-a0e7-441c1c3fd025` |
| A/B 私聊 | `3ed5b70c-24af-306d-8fd7-0bf95132fac4` |
| A 伙伴视角 | `c06bf19b-b55a-432d-9906-70db2fc2c365` |

实测专用日志只保存文本、工具参数及脱敏工具输出，没有读取全量应用日志或 reasoning metadata：`/tmp/agent-isolation-real-model.log`、`/tmp/agent-isolation-real-device.log`。完整检查日志：`/tmp/agent-isolation-final-build.log`；SQL 回归：`/tmp/agent-isolation-final-sql.log`。

## 数据保留与收尾

全程覆盖安装，没有卸载 App 或清空数据。firstInstallTime 保持 `2026-10-06 10:03:47`；应用包此次覆盖安装时间 `2026-10-12 18:10:31`。原有配置未被验收 fixture 改写，新增伙伴和联系授权仅追加。

对验收前后的全部非 FTS 表逐行计算完整字段哈希，所有原行全部保留且字段完全一致：原有 44 个会话、140 个节点、6 个伙伴聊天、14 个成员、25 条聊天消息。实测后是 50/149/8/18/27，仅追加 6 个新分段、9 个节点、2 个聊天、4 个成员、2 条最终聊天消息和 2 条修订。工作空间、电脑、SSH key、托管文件、审计事件等原表也没有缺失或改写。数据库仍版本 36。核对结果保存在 `/tmp/agent-isolation-after/verification.json`。

最终测试 APK 的 DEX 检查确认不含 IsolationRealModelTest，已覆盖安装。已执行 adb -s emulator-5560 emu kill，返回 OK；最终 ADB 设备列表为空。已在本 worktree 执行 ./gradlew --stop，返回 2 Daemons stopped。

## 本轮之外的隔离风险

- 全局记忆按主人确认的规则可被任何启用全局记忆的伙伴共享和修改，伙伴回合也照常注入；这是明确允许的共享范围。
- 快捷消息、模式注入、世界书及工作空间是共享扩展资源。现有编辑共享资源可能影响其他绑定伙伴；本轮仅限制 assistantId 定向变更，未扩展共享资源的权限模型。
- 主人全局搜索和 Web 主人接口保留完整视图；它们的访问认证不属于本轮工具隔离范围。

- 已有历史消息或工具结果可能仍保存旧版本输出过的其他伙伴简介；本轮防止新的泄露，不清理或迁移历史记录。
- R3 本轮仅限制回忆工具。伙伴异步回复通过 ThreadService.deliverFrom 唤醒主人主线程时，原分段历史和 ThreadContext 的早期主人对话摘要仍可能进入 prompt；本轮没有改变主线程上下文策略，也没有约束模型转述已经注入的内容。

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

## 有意保持原样的调用方

- SearchVM、ImSearchVM、Web ConversationRoutes 的主人全局搜索不传 access：仍搜索全部主人私聊，排除伙伴视角。
- 记忆页面（AssistantDetailVM、ImMemoryVM、工具结果 UI）的主人编辑/删除不传 access，保留原行为，缺失删除仍是 no-op。
- 扩展服务直接调用不传 access 时保持主人完整目录与权限；工具包装层必须传入 access。
- 最近聊天参考在专业模式仍遵从伙伴开关，IM 默认启用；read_partner_chat 的最终消息输出和成员复查不改。

## 验证

P0 JVM：876 项全部通过；emulator-5560 的 PartnerRecallQueryTest：2 项通过（0.954 秒），覆盖双模式主人搜索、伙伴回合、SQL LIMIT 前过滤、成员撤销、主人全局搜索保持原样。群 prompt 的额外修复随最终全量验证复核。P1 与真实模型验收结果在第二个提交补齐。首次构建发现当前 worktree 缺失非敏感原生依赖，已从主仓库复制 rdp/build/native 和 .deps/p6a 源码压缩包（忽略文件，不提交）；没有复制签名或凭据配置。

## 本轮之外的隔离风险

- 全局记忆按主人确认的规则可被任何启用全局记忆的伙伴共享和修改，伙伴回合也照常注入；这是明确允许的共享范围。
- 快捷消息、模式注入、世界书及工作空间是共享扩展资源。现有编辑共享资源可能影响其他绑定伙伴；本轮仅限制 assistantId 定向变更，未扩展共享资源的权限模型。
- 主人全局搜索和 Web 主人接口保留完整视图；它们的访问认证不属于本轮工具隔离范围。

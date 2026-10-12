# P3 交付记录

分支：`feature/agent-p3`。依据 `AGENT_P3_SPEC.md` 及其全部前置规格和 NOTES；不改界面或数据库版本。

## 新建伙伴

- `buildCreatePartnerTools` 在 ChatService 注册，仅 IM、主人发起且存在 ownerTrigger；执行再次检查。写工具每次审批，要求非空 reason；拒绝未知字段，无法指定模型、能力、MCP 或工作空间。
- 创建一个 `Assistant(name, systemPrompt, avatar, useAssistantAvatar=true)`，其他字段保留默认；未指定头像时使用 Assistant 默认 Miffan，模型 id 为 null（App 默认），本地工具仅默认 TimeInfo。
- `ProfileAvatarValidation` 从 P2 提取头像校验与构造函数，P2/P3 共同调用；新建不接受 previous。emoji 校验仍用现有 Unicode 目录及字形簇规则。name/prompt 按 Unicode 码点限制 40/8000。
- 审批后在一次 SettingsStore.update 中读取最新设置、确认调用者存在、追加伙伴并写入双向 PartnerPair 授权；不回显提示词或配置。写入携带 RevisionOrigin(AGENT, ownerTrigger, reason)。
- AssistantRevisionRecorder 原有 before=null 分支能记录新增；测试确认新增只有一条 AGENT 修订、parentId=null，无虚构 baseline，快照和 trigger/reason 正确。
- 新建及 P2 回归：CreatePartnerToolTest、SelfProfileToolTest、AvatarPresetToolTest 通过；日志 `build/partner-tests.log`。

## 验证约束

按 P3 规格只使用 JVM 测试；未启动或操作模拟器、未安装 App、未做真实模型实测。日志和临时文件均在当前 worktree 的 `build/` 下。

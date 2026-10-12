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

## 群聊（与消息列表分组分开）

- `create_group_chat`、`invite_to_group_chat` 仅 IM 主人回合注册并在执行时复查，每次审批，严格使用 title/chat_id、member_ids、reason 参数。
- 创建将调用者放在成员首位，现有 createGroup 因此将其设为 host；校验存在的伙伴、排除主人占位 id、自己和重复成员，沿用 2–6 人限制。标题沿用服务已有的去空白与 24 Unicode 码点限制，返回实际保存的标题。
- 邀请先检查群聊存在且调用者是成员；非成员与不存在返回完全相同的 `Group chat does not exist.`。已有成员和重复邀请忽略。
- 新增 AgentAccess 版 `PartnerChatService.setMembers` 重载；DefaultPartnerChatService 在现有 managementLocks 内重新检查调用者成员身份，并合并最新成员后写入。避免审批等待或并发邀请用旧列表覆盖现有成员；保持原 host，不走移除成员/删除视角路径。主人界面原有两参数 setMembers 继续允许原管理行为。
- createGroup/setMembers 调用均包在 RevisionOrigin(AGENT, ownerTrigger, reason)；当前修订机制没有群聊/成员 subject，不新增数据库或虚构修订。
- GroupChatToolsTest 使用真实 DefaultPartnerChatService + 内存 PartnerChatStore，覆盖群主、仅新增、原群主保留、旧列表合并、非成员错误隔离、输入和 AGENT 来源；连同 PartnerChatTest 通过。日志 `build/group-tests.log`。

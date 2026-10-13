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

## 消息列表分组与置顶

- `view_chat_list` / `organize_chat_list` 同样仅 IM 主人回合注册与执行；前者只读且无需审批，后者整批一次审批、要求 reason。两个“组”在工具名、描述和参数中明确区分：群聊用 group_chat，列表分组用 folder。
- 可见范围：所有伙伴与主人的 thread（id=伙伴 id，显示名复用 partnerDisplayName），以及调用者在其中的伙伴私聊和群聊（显示名复用 partnerChatTitle）。不可见聊天不输出；pin/unpin/move_to_folder 引用它们与引用不存在 id 返回相同错误。
- 只读输出字段为 folders（folder_id/name/chats）、ungrouped、pinned；聊天条目严格只含 chat_id/name/type/pinned，不输出聊天摘要、内容、时间、未读数、配置、路径或凭据。空的可见投影分组仍按顺序显示，置顶只投影可见聊天。
- operations 每项用 `type` 区分，参数名与规格一致。顺序执行最多 50 项（空批次成功但不写入）；ref 是本批新建分组的非空唯一引用，只能用于后续 move_to_folder；folder_id/ref 互斥。成功返回 `success:true,folder_refs:{ref:folder_id}`，无 ref 的新建分组仍可在下一次 view 中查看。
- 在唯一一次 SettingsStore.update 的锁内读取最新设置并应用纯函数 applyChatListOperations；某一步不合法时抛出内部 ChatListOperationError，在工具边界转成 `success:false,operation_index:<从1开始>,error`。异常发生在持久化前，整批不会写入，之前生成的 ref/分组也不会留下。
- ChatOrganization 现有 createGroup/renameGroup 改为最多 30 个 Unicode 码点，避免接受 30 字符却被静默截成 24；moveGroup 支持任意非零有符号 Int 偏移并避免溢出，越界仍沿用无操作行为。原 UI 的 ±1 调用保持行为。
- 删除空分组检查完整成员列表，包括不可见/过期聊天和重复导入的同 id 分组；不开放其他删除。现有方法会规范化导入数据，工具在每步恢复不可见聊天的原置顶和分组成员序列，包括重复项，确保无关的不可见状态不变。
- 整理写入携带 RevisionOrigin(AGENT, ownerTrigger, reason)；目前 SettingsStore 的修订记录仅覆盖伙伴配置，chatOrganization 没有修订 subject，所以列表整理本轮没有可撤销修订记录；不扩展数据库。
- ChatListToolsTest 覆盖可见范围和字段、注册与审批、整批原子、审批等待期间最新设置、ref、隐形聊天同错、非空分组删除、操作上限、码点长度、非法字段/偏移、任意偏移和不可见导入状态保留。

## 构建环境

使用 Android Studio JBR、本机 Android SDK 和已有离线 Gradle 缓存。补齐 worktree 忽略的 `rdp/build/native` 与 `.deps/p6a` 非敏感原生依赖；未复制签名、凭据或 local.properties。Gradle 的用户级缓存锁需要沙箱外访问，已通过自动审批运行；没有网络依赖安装。

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/chenxiansheng/Library/Android/sdk' \
./gradlew --offline --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

首轮完整验收通过（日志 `build/final-checks.log`）；随后对最后的重复分组 id 防护与空批次规则再次运行同样三项任务，最终结果如下。

- 最终源码三项任务全部通过：`BUILD SUCCESSFUL in 5m 43s`，548 actionable tasks。日志 `build/final-source-checks.log`。
- JVM：158 个测试类，933 项，0 failure/error/skipped；包含前置隔离、R8/R9、P2 回归及 P3 三部分测试。
- Debug APK：构建通过，生成 arm64-v8a、x86_64、universal 三种 APK。
- Lint：0 error、382 warning，与前置交付记录数量一致；未新增抑制规则。
- 汇总：`build/verification.json`。所有 Gradle 调用均使用 `--no-daemon`，本轮进程正常结束；没有操作其他会话的 Gradle 进程或模拟器。

# R9 与头像预设交付记录

分支：`feature/agent-relay-avatar`。依据 `AGENT_RELAY_AVATAR_SPEC.md` 及 R1–R8/P2 前置文档；不修改界面或数据库版本。

## R9

- `ChatService` 在静态工具组装完毕后调用 `withAgentRelayApproval(access)`；动态电脑工具的每次快照也经过同一包装。仅 `access.ownerThreadNonOwnerTurn` 生效，专业模式同样受保护；主人回合、伙伴视角和群聊保持原规则。
- `AgentRelayApproval.kt` 的 `OWNER_THREAD_LOCAL_TOOL_ALLOWLIST` 集中列出本机读取工具。白名单以外的所有工具（包含未来新增工具、MCP、动态电脑工具）强制 `needsApproval = true`，清除 `autoApprovedBy` 免审批来源。
- `workspace_read_file` 只有 App 捕获的 workspaceTarget.kind 为 `LOCAL` 才豁免；远程、未知类型及缺少目标快照均审批。白名单保留工具自身已有审批/交互规则：`ask_user` 的 Pending 是收集主人回答所需，不能移除。
- `ChatService` 使用当轮的模型副本清空所有 `Model.tools`，因此 Search、UrlContext、ImageGeneration 都关闭，不修改保存的模型配置。外部搜索注册及 `my_environment` 能力列表使用同一副本，外部搜索仍需 R9 审批。
- 审批卡读取 `UIMessagePart.Tool.metadata["approval_reason"]`，值为 `"owner_thread_non_owner_turn"`。工具定义通过 `Tool.approvalReason` 提供该值，`GenerationHandler` 在进入 Pending 时持久化；与服务商 metadata 合并，保留原字段、审批记录和实际操作的 via。
- R8 的 send_message 执行逻辑不变，批准仍不新增长期联系授权。

### miffan_help 核实

未加入白名单。`MiffanHelpClient.load` 的自由文本 `topicQuery` 在本机 `findTopic` 匹配，manifest URL 固定且清空 query；请求未直接发送自由文本主题。不过匹配后仍请求 manifest 指定的主题文档路径（可反映选中的主题），并按 locale 替换路径。它并非仅本机读取工具，因此按更严格条件保留 R9 审批。依据为 `MiffanHelpTools.kt` 的 `load`、`resolveTopicUrl`、`fetchResource`；没有新增网络调用。

## 验证与环境

- R9 JVM 覆盖：白名单、保留 ask_user 交互、所有外传类别、新工具默认审批、远程/未知工作空间读取、免审批覆盖、其他回合保持原样、模型配置不被改写。
- 实际 GenerationHandler + 假 Provider 回归覆盖静态搜索、MCP、动态电脑工具进入 Pending、带 R9 原因且没有执行；metadata 序列化往返保留服务商字段。
- 按本轮规格，不启动或操作模拟器，不做真实模型实测、不安装 App；只使用 JVM 测试。
- 使用 Android Studio JBR、本机 Android SDK、既有离线 Gradle 缓存。补齐忽略的 `rdp/build/native` 和 `.deps/p6a` 非敏感原生依赖；未复制签名配置、凭据或 local.properties。
- 构建日志和临时文件均在当前 worktree 的 `build/` 下。

## 头像预设与恢复

- `update_my_profile` 增加 `avatar_preset`（`miffan`、`whale_girl`、`previous`）、`miffan_kind`、`miffan_palette`。工具描述与 schema 列出预设效果及枚举的实际 `@SerialName` 值；保持仅 IM 主人回合注册、每次审批、仅修改 `access.callerId`。
- `avatar_preset` 与 `avatar_emoji` 互斥；kind/palette 只允许与 `avatar_preset=miffan` 同用（缺失 preset 或其他 preset 均拒绝），非法值/非字符串返回 `success:false`。成功的 changed 使用提供的参数名，保留原有 note；不回显图片路径、emoji 内容或系统提示词。
- 执行时在 SettingsStore 更新锁内读取最新头像。Miffan 未提供的 kind/palette 沿用当前 Miffan 值，否则 rice/classic；保留已有 Miffan 的 colorSource。切换 Miffan/WhaleGirl 时通过 `characterMotionProfileOrDefault()` 保留当前角色 motionProfile，图片/emoji/legacy Dummy 使用兼容默认 Curious。
- 审批卡可注入现有 `RevisionService` 并调用公开的 `suspend fun previousAvatar(assistantId: Uuid): Avatar?`。结果为自己的修订历史中最近一个与当前头像不相等的完整 Avatar；包括图片、emoji、Miffan、WhaleGirl 和 Dummy，按完整值比较，跳过只改名字等重复头像记录。
- 历史仅查询 `RevisionSubject.ASSISTANT + assistantId`，并复核 revision 归属及快照 id，不扫描其他伙伴或记忆修订。执行用相同的历史读取方法，和更新锁内的最新头像比较；无候选返回 `success:false,error:"没有更早的头像"`，同一次调用中的名字/提示词也不修改。
- 保留 RevisionOrigin(AGENT, ownerTrigger, reason)；头像预设和 previous 都写完整助手快照，原有撤销可恢复名字/头像/提示词，权限保留规则不改。
- `my_environment.avatar`：仅显式 Miffan 改为 `{"type":"miffan","kind":"rice", "palette":"classic"}`（kind/palette 按实际头像输出）。其余头像仍是原来的字符串：Dummy=`default`、Emoji=`emoji`、Image=`image`、WhaleGirl=`whale_girl`。不含路径或其他伙伴配置。
- 新增 AvatarPresetToolTest 覆盖全部 kind/palette、默认与省略规则、跨角色 motionProfile、审批期间最新配置、互斥/非法输入、恢复图片/emoji、重复头像跳过、无历史/同头像历史、他人修订隔离、调用者删除和 AGENT/undo 往返；MyEnvironmentToolTest 覆盖全部 Miffan kind/palette 输出。

## 最终检查结果

最终源码执行下列三项任务全部通过：`BUILD SUCCESSFUL in 10m 17s`，552 actionable tasks。

```bash
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
ANDROID_HOME='/Users/chenxiansheng/Library/Android/sdk' \
./gradlew --offline --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

- JVM：153 个测试类，906 项测试，0 failure/error/skipped；包含原有 R1–R8/P2 及本轮 R9/头像回归。
- Debug APK：构建通过。
- Lint：0 error、382 warning，与前置交付记录数量一致；未增加抑制规则。
- 日志：`build/r9-tests.log`、`build/final-checks.log`；汇总：`build/verification.json`。Lint 较慢期间的只读线程诊断为 `build/lint-thread-dump.log`，确认在保存 Lint 的分析结果，无卡死或进程干预。
- 全程使用 `--no-daemon`，单次构建进程已正常退出；未操作其他会话的 Gradle 进程或模拟器。

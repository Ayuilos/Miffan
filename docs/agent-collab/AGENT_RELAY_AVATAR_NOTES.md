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

# R8 与 P2 交付记录

分支：`feature/agent-self-profile`；依据 `AGENT_SELF_PROFILE_SPEC.md`。不改界面或数据库版本。

## R8

- `AgentAccess.forTurn` 统一构建 `ownerThreadNonOwnerTurn`：主人私聊（chatId 为 null）且没有 ownerTrigger。伙伴消息和事件均适用。
- `buildPartnerMessageTools` 将同一个回合 access 传给 `DefaultPartnerChatService.needsApproval/send`。合法的伙伴联系在上述回合一律审批，包括已有长期授权的联系人；无效目标、环路、同群成员等仍按原规则拒绝。
- `send` 在这一类回合不写 partnerContactGrants，审批只允许当前消息；已有授权保留。主人发起、伙伴视角、群聊的原有授权/审批行为保留。
- 工具描述明确这一规则；回归覆盖已有授权仍需审批、批准前后授权集合不变、新联系人审批不创建授权、伙伴视角仍创建和使用长期授权。

## 本轮仅记录的其他内容外传渠道

主人私聊中的非主人回合仍可能读取已注入的历史和摘要。除 send_message 外，以下工具/能力可能把这些内容带出去，本轮按规格不处理：

- 工作空间写文件、shell、发布/共享文件（尤其共享工作空间与远程主机）；
- 已绑定 MCP 服务的写入或网络调用；
- 电脑操作（远程电脑输入、浏览器表单、上传等）；
- 剪贴板写入、日历写入、朗读、执行 JavaScript 等已开启的本地工具；
- 联网搜索、URL 抓取的请求参数；
- 共享扩展资源的修改（快捷消息、模式注入等）。

工具实际可用性仍遵从当前伙伴绑定和开关；R8 不改变这些权限。

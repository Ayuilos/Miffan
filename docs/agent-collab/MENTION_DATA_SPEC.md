# 规格：在伙伴线程里 @ 其他伙伴（数据部分）

## 背景

真机测试时发现，主人在和伙伴 A 的 IM 线程里提到另一个伙伴 B 的名字，比较弱的模型往往不知道 B 是 Miffan 里的伙伴，也想不到要调用 `send_message`。GPT-6 Astra 也是被主人强调了一轮之后才明白。

界面由 Claude 负责：

- 线程输入框里输入 `@` 时，弹出其他伙伴的列表；
- 选中后插入 `@名字 `（后面带一个空格）；
- 输入框和消息气泡里的 `@名字` 会高亮显示。

**消息里只存纯文本**：输入框不另外保存 mention 数据，文本是唯一来源。手动打出来的 `@名字` 和从列表里选的效果相同。

## 契约（已提交，签名不要改）

`data/partner/PartnerMentions.kt`：

- `PartnerMention(id, range)`
- `findPartnerMentions(text, names)`：每个 `@` 只匹配一次，名字长的优先，空白名字不参与匹配。

界面用它来高亮。请把 `PartnerPerspective.kt` 里的 `parseGroupMentions` 改成复用它，保持现有行为（包括忽略自己），已有测试必须继续通过。

## 任务

### 1. 给模型的提示

**适用范围**：只处理主人在 IM 线程里发的消息，即同时满足以下条件：

- IM 模式；
- 会话的 `chatId == null`；
- 消息节点 `origin == null`。

伙伴视角分段（`chatId != null`）、伙伴或事件投递进来的消息都不处理。

**匹配名单**：`settings.assistants` 中除当前伙伴以外的伙伴，按名字匹配。@ 当前伙伴自己的不算。

**注入方式**：

- 有匹配时，只在发给模型的那份消息里，在这条消息末尾追加一个文本部分。
- 存储的消息、界面上看到的内容都不变。
- 请求里所有符合条件的历史消息都按同样规则处理，这样上下文保持一致。

注入内容大致如下（措辞可以调整，但信息不能少）：

```
<partner_mentions>
主人在这条消息里 @ 了 Miffan 里的其他伙伴（不是普通人名）：
- 小B（联系人 id: <uuid>）：<简介，取 system prompt 第一行，最多 160 字>
需要联系时直接调用 send_message，to 填上面的 id，不必先调用 list_contacts。主人 @ 了谁，就表示已经同意你联系谁。
</partner_mentions>
```

实现方式由你决定，例如新增一个 InputMessageTransformer，或在 ChatService 组装消息时处理。要求：

- 不改动 `ai` 模块的序列化格式；
- 不破坏 prompt caching：同一条历史消息每次注入的内容必须相同。

### 2. @ 等于授权

- 当前这一轮由主人的消息触发（`ownerTrigger()`）时，如果这条消息 @ 了伙伴 B，那么这一轮里 `send_message(to = B)` 不需要审批。
- 执行时照常写入 `partnerContactGrants`，相当于主人通过 @ 回答了“首次询问”。之后可以在伙伴资料页撤销。
- 没有被 @ 的伙伴仍然走原来的审批流程。
- 同群、环路、深度和次数这些拒绝规则不受影响。

## 测试

- `findPartnerMentions`：
  - 名字长的优先；
  - 名字后面紧跟中文（`@小B帮我问问`）；
  - 同一条消息里有多个 @；
  - 空白名字；
  - 文本里没有 @。
- 注入：
  - 只作用于主人消息；
  - 跳过伙伴视角分段和 origin 不为空的消息；
  - @ 当前伙伴自己的不注入；
  - 存储的消息不变；
  - 同一条消息两次注入的结果完全相同。
- 授权：
  - 被 @ 的伙伴不需要审批，执行后写入 grant；
  - 没被 @ 的伙伴仍然需要审批；
  - 被 @ 的是同群成员时仍然被拒绝。

## 验收

- `./gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 全部通过。
- 在 emulator-5560 上用真实模型实测（网关已配好）：
  - 在伙伴 A 的线程里发“@B 帮我问问他今天怎么样”，确认 A 直接调用 send_message 联系 B，并且不弹审批；
  - 测试前先在 B 的资料页撤销 A 和 B 之间已有的授权，再测；
  - 没有界面时，可以直接用 adb 在输入框里输入文本。
  - 不能卸载或清空 app 数据。
- 提交到 `feature/partner-mention-data`，写 `docs/agent-collab/MENTION_DATA_NOTES.md`，把实测结果写进去。
- 有事实性问题直接在你的输入框里问。

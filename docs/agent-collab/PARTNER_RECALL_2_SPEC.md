# 规格：伙伴回忆第二轮——名字一致，可以读取私聊内容

## 真机现象（主人的备份，2026-10-12 14:08）

主人在“蓝色大肥鱼”的线程里问它：之前是不是和 @默认助手 交流过。它最后找到了那段私聊，但回答：

> 历史私聊的标题只显示「和伙伴的私聊」，没有标出名字，所以交流记录已经找到了，但仅凭返回的信息，还不能完全核实那位伙伴的身份。

为了找到这段私聊，它前后调用了 9 次工具，依次搜了“默认助手”“伙伴”“连接”“确认了”“主人想确认”“100.64.0.5”，最后才靠 IP 地址猜中内容。暴露出两个问题：

1. **名字为空的伙伴，在数据层各处的叫法不一致。**
   - 默认助手在设置里的 `name` 是空字符串，界面上显示为“默认助手”（`assistant_page_default_assistant`）。
   - `list_contacts` 和 @ 提示已经改成用“默认助手”（`BLANK_PARTNER_NAMES[1]`）。
   - 但伙伴数据层其余各处仍然回退成“伙伴”：视角分段标题“与 伙伴 的私聊”、同步消息前缀“[伙伴] …”、私聊和群的上下文、`partnerChatTitle`、回忆结果的标题“和 伙伴 的私聊”、群通知里的发送者名等。
   - 结果是同一个伙伴在不同地方有两个名字，模型无法把它们对上。
2. **只能靠关键词碰运气读取私聊内容。**
   - `recent_chats` 能列出私聊和 `chat_id`，但没有任何工具能按 `chat_id` 读取内容。
   - `conversation_search` 只能全文搜索，而用伙伴名字去搜，又搜不到那些正文里没出现名字的对话。

## 任务

### 1. 统一伙伴的显示名

- 新增一个数据层函数，例如放在 `PartnerMentions.kt` 旁边：`fun partnerDisplayName(assistant: Assistant?): String`。
  - 名字非空时返回名字；
  - 名字为空时返回 `BLANK_PARTNER_NAMES[1]`（“默认助手”）；
  - 伙伴已被删除（传入 null）时，返回“已删除的伙伴”或沿用“伙伴”，二者选一，并在说明里写明。
- 把下面这些地方的“伙伴”回退全部换成它：
  - `PartnerPerspective.kt`：同步消息前缀、私聊和群的上下文、主持名、`partnerChatTitle`；
  - `DefaultPartnerChatService.kt`：第 211、228、237 行附近；
  - `ConversationTools.kt`：`recallChatTitle`；
  - `ChatNotificationManager.kt`：第 265 行。
- 群里伙伴回复中的 @ 解析（`parseGroupMentions`）：名字为空的成员也要能用“默认助手”等显示名匹配，改为使用 `partnerMentionNames`。
- **已存储的数据不迁移。** 旧的分段标题和旧消息里的“[伙伴]”前缀保持原样；回忆结果的标题是实时计算的，会自动变对。

### 2. 新工具：按 `chat_id` 读取伙伴对话

- 只在 IM 模式下注册，和 `recent_chats`、`conversation_search` 放在一起。名称例如 `read_partner_chat`，参数为：
  - `chat_id`（必填）；
  - `limit`（默认 20，最大 50）。
- 只能读调用者自己是成员的私聊或群聊，否则返回错误说明，不泄露任何内容。
- 返回内容：
  - 聊天标题（规则同回忆结果）和类型；
  - 最近 `limit` 条消息，按时间正序；
  - 每条包含发送者显示名（主人用昵称或“主人”）、时间、正文；正文可截断，例如每条最多 2000 字。
- 数据来源用 `PartnerChatService.observeMessages`，也就是最终消息表，**不要读视角分段**，因为分段里有工具调用和名字前缀等噪音。
- 修改工具说明：
  - `recent_chats` 写明“伙伴对话可用 read_partner_chat 和 chat_id 读取内容”；
  - `conversation_search` 的结果里，伙伴对话同样提示可以继续用 `read_partner_chat` 读取。

## 测试

- `partnerDisplayName` 覆盖三种情况：有名字、空名字、已删除。
- 空名字的伙伴：在同步前缀、私聊和群上下文、回忆标题、群里 @ 解析中都显示为“默认助手”。
- `read_partner_chat`：
  - 成员可以读取，内容正序，`limit` 生效，正文截断；
  - 非成员被拒绝；
  - 不存在的 `chat_id` 返回错误说明；
  - 专业模式下不注册这个工具。

## 验收

- `./gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 全部通过。
- 在 emulator-5560 上用真实模型实测，场景尽量贴近主人的情况：
  - 让一个名字为空的伙伴和另一个伙伴私聊一次；
  - 然后在另一个伙伴的线程里问：“之前是不是跟 @默认助手 有过交流？聊了什么？”
  - **问题里不要提示它用哪个工具。**
  - 记录它调用了哪些工具、回答里能否说出对方是谁、聊了什么。
- 启动、关闭模拟器和 `./gradlew --stop` 的要求同上一个任务。测完把模式切回原来的轻松模式。
- 提交到当前分支 `feature/pro-hide-perspectives`，作为单独的提交；本规格文件一起提交。写 `docs/agent-collab/PARTNER_RECALL_2_NOTES.md`。

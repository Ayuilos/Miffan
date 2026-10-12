# IM 线程 @ 伙伴：数据交付说明

分支：`feature/partner-mention-data`。依据：`MENTION_DATA_SPEC.md`、`AGENT_COLLAB_PLAN.md`、P3/P4 数据交付说明。

## 实现

- 保持 `PartnerMention` / `findPartnerMentions` 的契约与实现，群聊 `parseGroupMentions` 改为复用共享解析器，保留主人昵称、长名字优先、去重、忽略发送者的行为。
- 新增 `OwnerPartnerMentions`：从原始消息纯文本解析伙伴，匹配时保留自己的名字以避免误匹配其他伙伴的短名前缀，再排除自己。未知名字、空白名字不产生提示或授权。
- 新增 `PartnerMentionTransformer`，在 `ChatService` 输入转换器的末尾注册。只处理 IM 模式、`chatId == null`、`origin == null` 的 USER 节点；所有符合条件的历史主人消息都附加一个独立 Text part，包含伙伴名、UUID、system prompt 第一行（最多 160 字符）及直接调用/已授权说明。
- 每轮从原始节点和设置快照建立 message id → 提示映射；输入模板、OCR、伙伴输出和系统事件不能新增授权。转换只影响 provider 请求副本，生成输出仍基于原始 messages，数据库与气泡不保存提示。
- 提示不包含时钟、随机值、当前 grant 状态；同一原文及伙伴配置生成的历史提示完全相同，授权变化也不改变提示。伙伴名字或设定本身变化会按新的配置解析和生成简介。
- `send_message.needsApproval` 只读取 `ownerTrigger()` 指向的本轮主人消息，且限定 IM 的普通线程。被 @ 的目标跳过首次联系审批，执行沿用规范化 `PartnerPair` grant 写入；历史 @、非主人回合、私有视角分段、专业模式不会获得该授权。
- 同群、自己、不存在的联系人、环路、深度、交换次数拒绝规则保留。拒绝仍先于实际投递和 grant 写入；没有被 @ 的目标仍走原审批流程。工具描述允许直接使用 `partner_mentions` 中的 id。
- 没有修改 `ai` 序列化、数据库结构、UI 绘制或持久化消息格式；原生依赖不纳入提交。

## 自动化验证

```bash
./gradlew --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug :app:lintDebug

# 移除临时 debug 输入助手后，对最终源码再验证
./gradlew --offline --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

- JVM：805 项，0 failure、0 error、0 skipped，比原有 794 项增加 11 项。
- 新测试覆盖共享解析器的长名字、紧接中文、多次 @、空白名字、无 @；历史提示与原文不变、重复请求一致、授权变化不影响提示、简介截断、自己/未知名字/来源/视角/模式隔离；本轮授权、未提及目标审批、历史和非主人不能继承、执行写 grant、撤回、同群拒绝。
- Debug 构建通过。
- 首轮与最终 Lint 均通过：0 error、376 warning、6 hint，未屏蔽规则。首轮冷构建耗时 13m 30s；移除临时助手后的最终三项检查全部成功，耗时 4m 53s，805 项测试仍全部通过。

## emulator-5560 真实模型与数据保留

- 使用已配置的 `10.0.2.2:3425` 网关及现有 Default Assistant / Translator，模型为 `GPT-6.1-Sol`。
- 测试前在 Translator 资料页点击 Default Assistant 对应的“Take back”；联系人区消失，只读读取 DataStore 确认 `partnerContactGrants = []`。
- 系统分享进入 IM 输入框时遇到既有 Base64 原文显示问题，没有发送该字串；改用临时 debug 剪贴板辅助入口输入中文，实际发送仍通过线程正常发送按钮。辅助入口在交付前移除。
- 2026-10-12 **11:42:39**，通过正常 IM 发送按钮发送“`@Translator 帮我问问他今天怎么样`”。A 输出“我帮你问问 Translator 今天怎么样。”，直接调用 `send_message(to = f5000000-0000-4000-8000-000000000001, text = 主人想问问你：你今天怎么样？)`；没有调用 `list_contacts`。
- 工具落库的审批状态为 `auto`、`approvalRecord = null`，没有审批卡、没有任何批准操作。工具返回 `status = replied`、`chat_id = dcc3d2e3-43e4-33b8-b8cc-3f2e4e095a31`；约 11:43:11 Translator 回复“我挺好的，谢谢主人关心！随时可以帮你们翻译或聊聊。你今天怎么样？”，A 随后将回复转告给主人，线程显示“Talked with Translator · View”。
- DataStore 的 grant 从空列表变为 `0950e2dc-9bd5-4801-afa3-aa887aa36b4e`（A）与 `f5000000-0000-4000-8000-000000000001`（B）的规范化伙伴对。
- A 的资料页显示“Partners it can contact → Translator → Take back”，可从正常界面撤回；只读比较 DataStore，所有伙伴配置完全一致。
- 主人消息落库只含原始 Text part“`@Translator 帮我问问他今天怎么样`”，整个新 IM 分段没有存储 `partner_mentions`。
- 升级前后只读复制数据库及 WAL 核对：数据库仍为 36；原有 **31 段会话、107 个节点、3 段伙伴聊天、8 个聊天成员、15 条聊天消息的 id 全部保留**。原有节点的 message/origin/reply_to 全部一致，原有伙伴聊天与聊天消息整行一致；双方私有视角末节点的 selected_child_id 更新、revision 各增加 1，是追加新节点的正常父节点修订。
- 本次真实模型发送新增 1 个 IM 分段、8 个节点（包括已有私聊的历史视角补同步及本次问答）、2 条私聊消息，复用既有 DIRECT 聊天；发送后共 32 段会话、115 个节点、17 条聊天消息。没有删除旧数据、卸载或清空 app。
- 最终交付 APK 已于 **11:47:08** 覆盖安装，临时助手标记不在最终 DEX 中。安装前后上述五张表整行完全一致；`firstInstallTime` 始终为 `2026-10-06 10:03:47`。全程未卸载、清空 app 或运行可能清库的设备测试；模拟器保留验收消息与新授权。

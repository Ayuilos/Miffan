# P4 数据交付说明：群聊与聊天内审批

分支：`feature/group-chat-data`。依据：`P4_DATA_SPEC.md`、`AGENT_COLLAB_PLAN.md` 的群聊决定和第 4 阶段、`P3_DATA_NOTES.md`。

## 实现

- 已实现全部 P4 `PartnerChatService` 方法；已提交的模型与接口签名保持不变。没有修改 `ui/` 文件；`RouteActivity` 仅调整通知 intent 到页面的映射，冷启动和已有 Activity 都支持 `chatId → Screen.PartnerChat`。
- 私聊和群聊的审批观察合并所有当前成员的私有视角，按消息出现时间排列 Pending 工具调用。答复先读取并验证成员视角，再由 `openThreadSegment` 加载运行时状态、调用 `ChatService.handleToolApproval`，沿用 CARD 审批记录和生成恢复流程，不改变 P3 跨审批等待最终回复的行为。
- 审批通知以群名或“小A · 小B”为标题，点击进入对应聊天。群消息完成通知和保活通知也携带群聊目标。
- 群管理验证 2～6 个互不重复、存在的伙伴；默认第一个成员主持。群名 trim，非空名称按 Unicode 字符截至 24 个；空白名称保存为空字符串，显示时实时使用当前成员名以“ · ”连接。改名为空白恢复默认显示。改名和成员/主持管理串行执行。
- 移除成员先更新成员表，取消它的排队/运行任务，停止视角运行时，等待视角写回退出，再删除分段及附件。被移除的主持由新列表第一个成员接任。新成员以加入时最后一条消息为游标，不补发历史；已有成员的加入时间和同步游标保留。
- 删除群停止所有轮次并删除群、成员、消息和视角，清理 `chatOrganization` 中的置顶和分组成员。视角清理按 `chat_id` 查询，包括中断清理可能留下的已移除成员分段。删除伙伴时，DIRECT 整段删除；GROUP 移除成员，剩余不足两个时删除群。
- GROUP 每条主人消息拥有独立轮次；无 mentions 时主持接，有 mentions 时有效成员同时发言。每轮最多预约 8 次发言，每位成员最多 2 次，预约时即占预算，保证并发也不会超限。忙碌成员通过成员级互斥锁排队，轮到它时同步最新消息；每条回复引用实际叫起它的消息。DIRECT 保留 P3 的串行/最近发言者规则。
- 群回复按长名字优先解析 `@名字`，识别 `@主人` 和主人昵称，忽略 @ 自己，保存解析出的 mentions 并同时调度其他成员。群视角的 `threadContext` 说明群名、成员介绍、主持和 @ 分工规则；联系人标注 `in_this_group`，同群成员只能通过群内 @ 联系，`send_message` 无需审批即返回 `refused`；群外伙伴和 owner 联系仍可使用该工具。群 @ 路由与私聊工具的调用链、六条交换预算分开。
- 群未读为本机 `ThreadListState.readAt[chatId]` 后的伙伴消息数；DIRECT 恒为 0。可注入 `PartnerGroupNotifications`；仅直接回应主人消息或 @ 主人的群回复通知，前台和关闭生成完成通知时不发，标题为群名，正文为“伙伴名：去 Markdown 文本”。普通成员间回复只更新未读。
- 群轮次持有 P3 `GenerationKeepAlive` 租约，覆盖并发、排队、审批等待和后续 @ 交接；每次生成仍保留自己的租约。没有新增进程被杀后的生成恢复机制。

## 存储

数据库 **35 → 36**，显式迁移已注册，Room schema 导出为 `36.json`：

- `partner_chat.host_id TEXT`，可空，既有 DIRECT 为 null。
- `partner_chat_message.mentions TEXT NOT NULL DEFAULT '[]'`，保存 UUID JSON 数组，既有消息默认为空数组。
- GROUP 的发言预算在调度器执行，避免被 P3 私聊存储层的六条交换预算提前截断；DIRECT 的原预算与回复预留不变。

## 首次 P4 交付验证

| 检查 | 结果 |
| --- | --- |
| `:app:testDebugUnitTest` | **786 项全部通过**，比 P3 增加 12 项。新增覆盖群管理、并发调度、忙碌排队、每轮/每人上限、长名字/主人/self mentions、加入游标、未读、通知决策与跳转、DIRECT 审批观察、GROUP 审批等待恢复及运行时 CARD 委托测试。 |
| `:app:assembleDebug` / `:app:assembleDebugAndroidTest` | 通过；最终 APK 已安装，临时 debug 广播入口已移除。 |
| `:app:lintDebug` | 通过，0 error、381 warning；没有屏蔽规则。 |
| `Migration_35_36_Test` | emulator-5560 上 **2 项全部通过**；使用独立 fixture/内存数据库，检查旧私聊、消息来源、视角游标保留、默认空 mentions、外键级联、主持/mentions/加入游标往返、八条群消息落库及 orphan 视角查询。 |
| emulator-5560 真实模型 | 通过，详情如下。 |

最终同一 Gradle 进程完成 JVM 测试、app/test APK 构建、Lint 和指定设备迁移类，全部成功，耗时 **5m 10s**。

验收命令：

```bash
./gradlew --offline --no-daemon :app:testDebugUnitTest \
  :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug

ANDROID_SERIAL=emulator-5560 ./gradlew --offline --no-daemon \
  :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.ayuilos.miffan.data.db.migrations.Migration_35_36_Test \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

## 真实模型实测与数据保留

2026-10-12，使用已配置的 `10.0.2.2:3425` 网关；模拟器停留在 Launcher，通过临时 debug 广播创建三个独立验收伙伴及群“P4真实模型验收”，未修改原有伙伴配置，也未修改 UI。临时接收器和 manifest 声明已从交付代码移除。

群 id：`6218c382-f886-494c-8bc0-596ee73f058b`。

1. **无 @ 主持接话**：00:56:10 开始，主人发送“请只回复P4_HOST_OK，不要@任何人，不调用工具。”；00:56:14 主持 `c6d3d239-973d-4437-a83a-163b1b402d80` 回复 `P4_HOST_OK`，另两位没有发言，mentions 为空，replyTo 指向该主人消息。
2. **主持 @ 分工**：00:56:40 开始，主人要求主持在最终回复中 @ 成员乙；主持输出 `@P4成员乙 请回复P4_MEMBER_OK。`，mentions 保存成员乙 `c39670ad-f6bb-4f61-8c4a-a2c0a72b5f95`，replyTo 指向主人消息。成员乙随后自动回复 `P4_MEMBER_OK`，replyTo 指向主持那条消息，00:56:47 两条回复均落库。
3. **后台通知**：设备通知标题为“P4真实模型验收”，正文带“P4主持：”和去 Markdown 的主持回复。成员乙回复没有 @ 主人，且引用的是主持消息，因此没有替换主持的通知。

升级前只读复制实际数据库和 WAL；实测后逐行比较原有字段：原有 **26 段会话、86 个消息节点、1 段私聊、2 个私聊成员、4 条私聊消息全部一致**，包括节点的 message/origin/revision 及私聊视角指针。数据库从 35 升至 36，实测仅新增 1 个群、3 个群成员、5 条群消息和 2 个视角分段/9 个节点（总计 28 段会话、95 个节点）。验收群和三个验收伙伴保留，方便后续 UI 验收。

最终安装后再次逐行核对，上述原有数据仍全部一致；`firstInstallTime` 始终为 `2026-10-06 10:03:47`，最终 `lastUpdateTime` 为 `2026-10-12 01:05:18`，app 和 test 两个包均保留。全程不卸载、不清空 app；只运行指定迁移类，测试 APK 保留。原生依赖没有纳入提交。

## 真机反馈修正

在包含群聊界面的 `023e1434a` 上继续实现三项反馈：

- 群工具构建时加载成员名单，`list_contacts` 为伙伴提供 `in_this_group` 布尔值。同群 `send_message` 跳过联系审批，返回 `refused` 和“对方就在这个群里，请在群里用 @名字。”，不创建私聊、不添加授权。执行时同时检查该轮成员快照和当前成员，避免移除成员后把原本跳过的审批变成授权；新一轮工具重新加载名单。群外私聊、DIRECT 和 owner 消息保持原流程，群 `threadContext` 明确上述规则。
- `createGroup` 和 `renameGroup` 将空白群名保存为 `""`。默认显示由当前成员名实时生成，成员调整、伙伴改名后随之变化；通知和群系统提示也使用该回退名称。
- `stripMarkdown` 的单、双下划线强调要求外侧不是 Unicode 字母或数字，并避免单下划线匹配双下划线的一部分，保留 `P4_MEMBER_OK`、snake_case 及字内双下划线；星号处理沿用原规则。测试包括标点/空白边界、Unicode 字符，以及不完整标记和正常强调混合出现的情况。

新增 8 项单元测试，并调整原有群管理、群私聊与上下文测试。本次只运行 JVM/Lint 检查，没有安装、卸载、清空或操作模拟器 app，原生依赖未纳入修改。

```bash
./gradlew --offline --no-daemon :app:testDebugUnitTest :app:lintDebug
```

`testDebugUnitTest`：**794 项全部通过**，0 failure、0 error、0 skipped。

`lintDebug`：检测到当前合入界面的 **17 个既有 error**（另有 376 warning、6 hint）：`ui/im/ImGroupPages.kt:141` 的空内容 Scaffold 未使用 padding；16 个 `im_gc_*` 字符串在日、韩、俄资源中缺少翻译。已核对这些问题存在于修改前的 HEAD；本次错误均位于未修改的界面/资源文件。遵守 P4 规范的 `ui/` 范围约束，没有修改界面或屏蔽 Lint。

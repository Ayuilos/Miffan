# P2 数据交付说明

分支：`feature/origin-data`。实现依据：`P2_DATA_SPEC.md` 与 `AGENT_COLLAB_PLAN.md` 第 2 阶段。

## 实现

- `MessageNode.origin` 经 `sendMessage` → `QueuedMessage` → `appendMessage` → Room 完整传递；编辑、分叉与流式合并保留来源。
- `ownerTrigger()` 只看当前路径的最后一个 USER 节点；它来自伙伴或事件时返回 null，不回退到更早的主人消息。
- 生成入口传入 `ownerTurn` 和 `memoryTrigger`；重新生成以实际截取的当前路径判断权限。
- 非主人回合不提供记忆工具、不运行自动记忆整理。之后的主人回合整理记忆时，也排除历史非主人回合及其回复，避免把事件或伙伴内容记成主人事实。
- 有待审批时，非主人消息排队；普通发送、立即发送队列消息、取消旧生成后实际发送前均有保护，不替主人结算审批卡。
- `deliverFrom` 按显式分段 → 最新未关闭分段 → 新分段选择，不走路由；复用尚未落库的分段，拒绝投递到其他伙伴的分段。使用 `answer = true` 的原前台服务保活路径；冷启动等待设置加载完成后再选伙伴。
- Event USER 节点产出事件行，Partner 节点隐藏；事件打断分组，事件回合不产生自动引用，也不参与并行批次的提问引用规则。
- 列表通过带来源的近期节点生成预览：事件显示 summary，`fromUser = false`；未读仍只数助手回复。
- Web DTO 附加当前节点 `origin` 和与候选消息逐一对应的 `origins`，全量响应、SSE 与节点差量均保留字段。
- debug 源集增加 `DeliverEventReceiver`；release 源集和 manifest 不含该入口。

## 权限与序列化审计

| 位置 | 判断与处理 |
| --- | --- |
| `ChatService` 自我修改工具构造 | 涉及权限；改用实际生成路径的 `ownerTrigger()`，文本按该 MessageRef 找回节点。 |
| `SelfConfigTools.update_my_preferences` | 原有空 trigger 拒绝逻辑保留，测试事件内容即使被原样引用也不能授权。 |
| `SelfConfigTools.request_web_search` | 原执行体缺少空 trigger 检查；补拒绝，阻止非主人回合修改能力。 |
| `ChatService.extractMemories` | 涉及记忆写入权限；无主人 trigger 整轮跳过，修订来源使用同一个 trigger。 |
| `GenerationHandler` | 不再根据 API 消息列表推导 trigger；调用方传入，非主人回合不构造记忆工具。已有记忆仍可作为上下文读取。 |
| `MemoryExtraction` | USER 计数和文本信号原本只决定整理时机；增加 Conversation 入口，过滤非主人回合后才计数和取窗口。 |
| `ChatService.sendMessage` / `sendQueuedMessageImmediately` / `sendMessageNow` | 涉及待审批结算；事件/伙伴进入队列，只有主人发送调用 `finishInterruptedPendingTools(repliedInChat = true)`。 |
| `ThreadService.send` | 是主人发送入口，签名没有 origin；其待审批强制分段规则继续只用于主人。`deliverFrom` 独立选择分段，绝不使用该强制路由。 |
| `ThreadContext` / `SegmentSummarizer` | 取文本与历史摘要，提供连续性上下文，不授予权限；保持。 |
| `SmartSegmentRouter` | USER/ASSISTANT 过滤仅用于话题文本；不授予权限，保持；`deliverFrom` 绕过它。 |
| `PromptInjectionTransformer` | USER 判断用于注入位置与 USER→工具回复相邻关系；不授予权限，保持。 |
| `TimeReminderTransformer` | USER 判断用于时间提醒位置；合成 USER 消息不会进入 ownerTrigger 判定，保持。 |
| `ThreadTimeline` | USER 判断用于引用与显示；排除非主人触发者和事件批次，来源决定 Event/隐藏节点。 |
| `AgentThreadVM` | 最新 USER 用于选择重试消息、排队消息分组，非授权者；按文件归属不修改 UI。 |
| `ChatService` regenerate / edit / fork / title / suggestion | USER 判断用于操作与取文本；生成权限仍经 ownerTrigger。编辑和分叉重建节点时额外保留 origin，避免来源丢失。 |
| `Conversation.updateCurrentMessages` | 同 ID 流式更新使用 `withMessage`，保留节点元数据；新增助手节点没有主人权限含义。 |
| `ConversationRepository` | 节点来源双向 JSON 映射，近期节点读取保留 origin。 |
| JSON 序列化 | MessageNode/Conversation 为 Serializable；测试 null、Event、Partner、分支、流式更新往返。 |
| 本地 / WebDAV / S3 备份恢复 | 整体备份 SQLite 数据库及 WAL/SHM，新增列随数据库保留；旧库恢复后走迁移。 |
| Markdown / 图片导出 | 现有展示型导出，不用于还原 Conversation；不改受限制的 UI 文件。可恢复数据备份与 Web API 均保留来源。 |
| Web 全量 / SSE / 节点差量 | DTO 同时携带当前来源和候选分支来源，节点比较自动涵盖新增字段；旧 web-ui 可忽略新字段显示普通 USER。 |
| `ChatService.ChatGenerationEnded` / `ChatNotificationManager` | 成功生成无条件发结束事件；通知仅检查后台、通知设置与审批状态，无“最新 USER 必须主人”的限制，事件回复沿用完成通知。 |
| `OcrTransformer` / `DocumentAsPromptTransformer` | 仅转换模型输入内容，保留 UIMessage ID；OCR 独立请求无权限工具，不根据转换后的 USER 推导授权，保持。 |
| `LiveSegmentHandoff` / `completedAssistantReplyId` | 最新消息用于落库交接与完成回执，不推导授权，保持。 |
| `MessageNodeDAO` 日活统计 | USER 计数是使用统计，非授权者；保持既有统计规则。 |

## 数据库迁移

数据库 33 → 34，显式 `Migration_33_34` 注册到 DI：

```sql
ALTER TABLE message_node ADD COLUMN origin TEXT NOT NULL DEFAULT '';
```

空字符串映射为 null，旧消息仍是主人消息，不回填。Room 导出的 `34.json` 与实体一致。迁移测试使用独立的 `migration-33-34-origin-test` 数据库，验证旧消息、revision、reply_to、树关系以及新增 Event/Partner JSON。

## Debug 触发命令

```bash
"$ANDROID_HOME/platform-tools/adb" -s emulator-5560 shell am broadcast \
  -a me.ayuilos.miffan.debug.DELIVER_EVENT \
  -n me.ayuilos.miffan.app.debug/me.ayuilos.miffan.debug.DeliverEventReceiver \
  --es text '收到一条系统事件，请简单汇报。' \
  --es summary '调试事件到达'
```

可追加 `--es assistant '<伙伴 UUID>'`、`--es segment '<分段 UUID>'`。assistant 缺省取设置中的第一个伙伴。text 给模型，summary 给事件行。无可用伙伴、UUID 错误或 extras 缺失时记录错误，不投递。

## 验证

| 检查 | 结果 |
| --- | --- |
| `./gradlew --offline :app:testDebugUnitTest` | 全部 752 项通过，包含冷启动补测。 |
| P2 定向测试 | 50 项通过，涵盖 ownerTrigger、分支、JSON/Web/fork、审批队列、自我修改、自动记忆、记忆工具与修订来源、投递分段/冷启动、时间线、预览。 |
| `:app:assembleDebug` / `:app:assembleDebugAndroidTest` | 通过。 |
| `Migration_33_34_Test` | emulator-5560 上 1 项通过；只运行该类，显式保留 APK。 |
| `:app:lintDebug` | 离线、全新 JVM 下通过；报告 0 error、396 warning、6 hint，未屏蔽任何 lint 规则。 |
| `:app:processReleaseMainManifest` | 通过；debug 合并 manifest 含接收器，release 合并 manifest 不含接收器或其 Action。 |
| app 数据保留 | 测试前后 firstInstallTime 均为 `2026-10-06 10:03:47`；`rikka_hub` 仍为 318464 字节，WAL/SHM 保留。没有卸载、清空 app 或运行其他设备测试。 |
| 实际模型 / 后台通知 | 完成通知和前台服务代码路径已审计；未发真实模型请求进行通知实测。 |

普通联网 lint 卡在 Google Maven 的依赖版本查询；构建和 lint 改用 `--offline` 使用已有缓存。一次长时间源码遍历后重启专用构建 JVM，最终以 `--no-daemon` 全部通过。未调整 lint 规则或屏蔽错误。

最终验收命令（同一轮全部通过，耗时 5m 54s）：

```bash
ANDROID_SERIAL=emulator-5560 ./gradlew --offline --no-daemon \
  :app:testDebugUnitTest :app:assembleDebug :app:lintDebug \
  :app:connectedDebugAndroidTest :app:processReleaseMainManifest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.ayuilos.miffan.data.db.migrations.Migration_33_34_Test \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

迁移测试仅指定本次新增类，保留 app/test APK：

```bash
ANDROID_SERIAL=emulator-5560 ./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=me.ayuilos.miffan.data.db.migrations.Migration_33_34_Test \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true
```

不执行卸载或 `pm clear`，不运行会清库的其他设备测试。

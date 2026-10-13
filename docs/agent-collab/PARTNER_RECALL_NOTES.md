# 伙伴对话回忆交付说明

分支：`feature/partner-recall-data`。依据：`PARTNER_RECALL_SPEC.md`、`AGENT_COLLAB_PLAN.md`、`P3_DATA_NOTES.md`、`P4_DATA_NOTES.md`。

## 实现

- `conversation_search` 在 IM 模式显式传入调用伙伴的 assistantId。FTS 在 SQL 内、排序和 50 条上限之前，保留原来的全部主人线程，并额外允许 `assistant_id` 等于调用者的私有视角；其他伙伴的视角仍排除。
- `searchMessages` / `MessageFtsManager.search` 新增可空 `perspectiveAssistantId`，默认 null。`ImSearchVM` 等界面调用未修改，主人的 IM 全局搜索仍排除全部视角；专业模式仍搜索原来的所有会话。
- FTS 返回视角的 `chatId`。工具用聊天和当前伙伴配置生成标题：DIRECT 为“和 <对方名> 的私聊”；GROUP 使用 `partnerChatTitle`，非空群名优先，空名按成员名以“ · ”连接。搜索条目保留原有 conversation_id、snippet、date，并在 IM 中增加 `owner_thread` / `partner_chat` / `group_chat` 来源，视角结果增加 chat_id。
- `recent_chats` 合并调用者的主人线程和它当前参与的私聊/群聊，用聊天的最后活动时间共同排序，合并后再取 limit。伙伴聊天使用 `observeSummaries`，尚未创建调用者视角的聊天也能列出。仍只返回标题、日期及标识/来源，不返回聊天内容。
- 主人线程查询增加默认 false 的 `newestFirst` 参数，只有 IM 工具开启，避免旧置顶话题提前占掉 limit；专业模式继续置顶优先，并保留原来的字段和范围。
- 两个工具在 IM 模式补充说明，告知模型能查询参与过的伙伴私聊和群聊。未新增摘要注入、数据库迁移、界面改动或资源文案。

## 验证

最终验证命令（离线、限制并行 worker 数以减少内存占用）：

```bash
./gradlew --offline --no-daemon --max-workers=2 \
  :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleDebugAndroidTest :app:lintDebug
```

最终同一 Gradle 进程全部成功，耗时 **4m 16s**。JVM：**848 项通过，0 failure、0 error、0 skipped**。Lint：**0 error、376 warning、6 hint**，未屏蔽规则。Debug APK 和 AndroidTest APK 均构建成功。

- 新增 5 项 JVM 工具测试：私聊、命名群/空名群和主人线程的标题/来源/标识；混合排序、limit、无视角聊天和不相关聊天排除；专业模式搜索和近期列表兼容；工具说明。原 FTS 模式隔离测试相应更新。
- 新增 `PartnerRecallQueryTest` 两项设备测试，已在 emulator-5560 通过。真实原生 FTS 验证：可搜到自己的私聊/群聊视角；55 个其他伙伴视角在 limit 前排除；全部主人线程仍可搜；主人全局搜索不返回视角；专业模式仍返回包括其他伙伴视角的 50 条结果。Room 验证默认置顶顺序不变、IM 最近活动顺序正确且排除视角。
- FTS 测试使用随机名称的独立 fixture 库，完成后仅删除该 fixture；排序测试使用内存库。Requery 对无名内存库绕过自定义原生扩展配置，因此首次 FTS fixture 出现 `no such function: jieba_dict`，改为独立文件库后通过。没有打开或清空用户数据库。

设备测试命令（保留 app 和 test APK）：

```bash
"$ANDROID_HOME/platform-tools/adb" -s emulator-5560 install -r \
  app/build/outputs/apk/debug/app-arm64-v8a-debug.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5560 install -r -t \
  app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
"$ANDROID_HOME/platform-tools/adb" -s emulator-5560 shell am instrument -w \
  -e class me.ayuilos.miffan.data.partner.PartnerRecallQueryTest \
  me.ayuilos.miffan.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

## 真实模型实测

2026-10-12，emulator-5560，使用既有 `10.0.2.2:3425` 网关及 `codex/gpt-6.1-sol` 模型。通过临时 debug 广播入口调用真实 `ThreadService.send`，按主人消息路径生成；临时 receiver 及 manifest 声明已从交付源码移除。未改原有伙伴或模型配置，新增独立验收伙伴“回忆验收甲”“回忆验收乙”。

1. 13:42:09，在甲的主人线程发送：“请联系 @回忆验收乙，告诉它：秋日茶会约定在银杏树下，周日下午三点。请让它确认地点和时间，并把答复转告我。”
2. 甲真实调用 `send_message`，@ 按既有规则免审批。乙从自己的私聊视角回复：“确认：秋日茶会的地点是银杏树下，时间是周日下午三点。”工具返回 replied，甲向主人转告。
3. 13:43:03，在乙的全新主人线程问：“你和回忆验收甲沟通过吗？请用 conversation_search 搜索你参与过的私聊，查清秋日茶会约定的地点和时间。”该线程未注入任何私聊摘要或约定内容。
4. 13:43:08，乙真实调用 `conversation_search({"query":"秋日茶会","limit":20})`。首条命中乙自己的私有视角，title 为“和 回忆验收甲 的私聊”，source 为 partner_chat，chat_id 为 `c56fb1e9-994f-39ef-9829-8a386450ff98`，snippet 包含地点和时间。结果还包含该视角中甲的消息，以及原范围内的主人线程；没有甲的私有视角。
5. 乙最终回答：“沟通过。我用 conversation_search 找到了参与过的「和 回忆验收甲 的私聊」……约定是：银杏树下，周日下午三点。”

验收标识，保留用于复查：

| 对象 | id |
| --- | --- |
| 甲 | `55603600-0000-4000-8000-0000000000a1` |
| 乙 | `55603600-0000-4000-8000-0000000000b1` |
| 甲主人线程 | `55603600-0000-4000-8000-0000000000a2` |
| 乙主人线程 | `55603600-0000-4000-8000-0000000000b2` |
| 私聊 | `c56fb1e9-994f-39ef-9829-8a386450ff98` |
| 乙私有视角 | `7537694e-348f-46f7-801e-3542fc4d421c` |

## 数据保留和收尾

验收前后只读复制实际数据库及 WAL，逐行比较全部原字段：原有 **36 段会话、126 个消息节点、4 个伙伴聊天、10 个成员、21 条聊天消息均完全一致**。实测仅新增两个主人线程、两个私有视角、7 个节点、1 段私聊、2 个成员、2 条聊天消息；总计 40 段会话、133 个节点、5 个聊天、12 个成员、23 条聊天消息。数据库始终为版本 36。

全程覆盖安装，没有卸载或清空 app 数据。原生依赖未纳入提交。

最终 APK 覆盖安装后再次核对全部原行，仍完全一致；安装包不含临时 PartnerRecallReceiver。firstInstallTime 保持 `2026-10-06 10:03:47`，lastUpdateTime 为 `2026-10-12 13:47:08`。已用 `adb -s emulator-5560 emu kill` 关闭模拟器（返回 OK）。已在本 worktree 执行 `./gradlew --stop`，返回 `No Gradle daemons are running.`；ADB 设备列表为空。

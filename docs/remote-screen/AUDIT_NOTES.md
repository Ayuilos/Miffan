# 授权留痕数据层

## 范围与行为

基线：`599282e6`。未执行 Git 写操作或提交。未改变 needsApproval 判断、授权卡片行为或工具执行权限。

- `ai/.../ToolApprovalRecord.kt`、`UIMessagePart.kt`：增加可空的每次调用记录和枚举；旧消息缺失字段仍可解码，merge 保留记录。记录采用首次决策优先，已批准后执行被打断不会把原批准改写成取消。
- `GenerationHandler.kt`：在原 Pending 分支记录 requestedAt；实际自动派发时根据工具的 `autoApprovedBy` 记录 AUTO_ALLOWED。普通观察工具不记录。数据库写入不在流式 token 路径。
- `Tool.kt`、`WorkspaceTools.kt`、`ComputerTools.kt`：增加纯来源回调，Shell 区分持久允许/关闭询问，电脑 Automatic 的动作标记 NO_ASK_SETTING，观察及前台操作不标记自动允许。
- `Assistant.kt` 增加仅用于记录的 `workspaceShellApprovalVia`。原有“始终允许”和设置页关闭询问都调用相同方法、保存相同 target，历史数据无法区分来源；缺少来源的旧目标许可按 STANDING_ALWAYS_ALLOW 记录。新设置操作保存 NO_ASK_SETTING，新卡片持久许可保存 STANDING_ALWAYS_ALLOW。该字段不参与权限判断；`RevisionService.kt` 与现有权限字段一起保留当前来源，不因恢复历史快照改写当前许可。
- `ChatService.kt`：记录允许、拒绝、作答、始终允许、聊天回复、停止。过期目标被原有校验拒绝时如实记为 DECLINED。仅新增最终决策被排入审计队列，避免 clear 后因后续对话重复写入旧记录。
- `PartnerScreenPage.kt` 仅增加 `PARTNER_SCREEN` 参数；普通 ViewModel `AgentThreadVM.kt` 和 `ThreadService.kt` 透传来源，其他 Compose 文件未修改。
- `WorkspaceToolTargetGuard.kt`：原绑定器只处理 workspace_*，电脑调用无法保留定义中的 host 快照；现同样绑定 computer_* 的目标显示信息。原执行目标校验范围未改变。
- `RemoteComputerControl.kt`：CAS 成功且 USER 状态实际变化后记录接管/交还；重复触摸、重复交还及普通 partnerActs 不产生记录。状态更新重试不会重复写入。共享屏幕记录不关联伙伴。

## 存储与 API

`audit_event` 无会话/伙伴/主机外键，删除这些对象不会级联删除记录。Room 版本 **30 → 31**，显式 Migration_30_31、schema 31.json；Koin 注册 DAO 所属数据库及 AuditRepository。

- `(kind, tool_call_id)` 唯一索引及 INSERT IGNORE 保留首次决策。屏幕事件的 tool_call_id 为 null，可记录多次真实变化。
- 每次插入和裁剪在一个事务里执行；按 at DESC、rowid DESC 保留最新 5000 条（同毫秒按插入顺序）。
- 摘要复用 approvalNotificationSummary，保存当时的语言、命令第一行/80 字符和电脑历史名称。无凭据/完整工具输出写入审计表。
- 独立 SupervisorJob + IO 队列串行写入，记录异常记日志并吞掉，不中断工具执行。已提交记录持久化；进程在异步队列落盘前终止可能丢失尚未提交的事件。

UI 使用 `data/audit/AuditRepository.kt`：

```kotlin
fun observe(assistantId: Uuid?, hostIds: Set<String>, limit: Int): Flow<List<AuditEvent>>
suspend fun clear(assistantId: Uuid?)
```

- assistantId 为 null：全部历史；非 null：该伙伴的记录，加上 hostIds 中主机的两类屏幕事件。
- 最新优先，limit 限制到 0..5000。返回对象镜像表字段，ID 为 String；kind、decision、via 解析为枚举，未知值为 null。
- clear(null) 清空全部；clear(partnerId) 仅清除该伙伴记录，不清除共享屏幕事件。clear 与已排队写入保持顺序，其失败向调用者报告。

## 验证

- `./gradlew :app:compileDebugAndroidTestKotlin :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --offline`：最终版本通过（31s）。单元测试 598 项，0 failures / 0 errors / 0 skipped。
- 新增 JVM 测试覆盖旧 JSON、merge、等待和各卡片来源/决策、聊天回复、停止、持久许可、自动允许两种来源、观察及前台动作排除、目标快照、审计行/未知枚举、SQLite 唯一约束和真实 SQL 裁剪；屏幕测试覆盖重复接管/交还和伙伴动作中的接管。
- 新增测试依赖 sqlite-jdbc 3.41.2.2（已有离线缓存），只在 JVM 测试使用；迁移 SQL 和生产裁剪 SQL 直接在 SQLite 内存库执行。
- `emulator-5560` 上实际执行 `Migration_30_31_Test` 和 `AuditDAOTest`：**OK (3 tests)**（0.423s），覆盖 Room schema 校验、旧数据保留、无外键、去重、伙伴/共享屏幕查询及 clear 范围。
- 实际应用数据库（run-as + sqlite3 -readonly）回读：`PRAGMA user_version` 为 **31**，`audit_event` 行数为 **0**。上面的迁移测试使用独立测试数据库，不冒充真实聊天产生的审计记录。
- **聊天端到端验证未完成**：安装最终 APK 后，打开聊天出现整机冻结（qemu CPU 0%、adb shell/截屏阻塞）。按规格以 `emulator -avd RikkaHub_API_35_16K -port 5560 -no-snapshot-load` 冷启动（额外使用 `-no-snapshot-save` 避免保存坏状态），仍复现；进一步尝试软件渲染及关闭 Vulkan。最后可进入聊天，但输入审批请求时出现 “Miffan isn’t responding”，尚未成功发送请求、生成审批卡片或获得 DECLINED/REPLIED_IN_CHAT 的真实聊天审计行。
- 最新 ANR：`Input dispatching timed out`，等待 KeyEvent 5116ms。主线程为 Runnable，位于 `androidx.compose.runtime.snapshots.Snapshot` → `SnapshotKt.takeNewSnapshot` → `Recomposer.performRecompose`。该栈未显示 AuditRepository/Room 写入阻塞，不能仅据此确定完整根因。按范围约束未修改 Compose；需 UI/模拟器问题解决后补验两条真实聊天路径。
- 本次从未点击 Allow，未执行任何远端命令。为了避免旧聊天触发电脑工具预加载，曾临时将测试伙伴 computerUse 设为 off；已还原并回读为 ask，Shell 审批一直为 true，其他通知/伙伴配置未改。未发送测试聊天消息。读取 ANR 使用的临时 adb root 已撤销，回读 shell UID=2000；模拟器已恢复默认图形后端并确认冷启动完成。
- `git diff --check` 通过。构建/测试日志位于本机 `/tmp/audit-build-final.log`，设备数据库测试输出 `/tmp/audit-task/instrumentation.log`。

## Claude 复核（2026-10-08）

- emulator-5560 上安装合并后的构建（数据库迁移到 31），轻松模式请求运行 `hostname`，用聊天消息改成 `uname -r`，再对新卡片点 “Not this time”。
- `audit_event` 实际写入两行：`Run command: hostname | REPLIED_IN_CHAT | CHAT_REPLY | cachyos` 与 `Run command: uname -r | DECLINED | CARD | cachyos`，均带 requested_at。
- 屏幕接管/交还需要可用的 SSH 连接，本次只有单元测试覆盖。

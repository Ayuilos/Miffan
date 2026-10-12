# 反馈助手交付记录

分支：`feature/bug-reporter`，依据 `BUG_REPORTER_SPEC.md` 及 R1–R9 前置规格/NOTES。

## 诊断与持久化

- `LocalToolOption.BugReport` 使用稳定序列化值 `bug_report`，默认关闭。扩展管理现有本地工具选项列表加入该项；设置页开关文案、图标和 UI 按规格留给主人接入。
- `ChatService` 依据伙伴自己的能力开关，在专业模式与 IM 模式中注册 `app_diagnostics`，沿用同一回合 `AgentAccess`。工具只接受显式 `AppDiagnostics` 元数据和错误记录供应函数，无助手配置、会话、提供商或数据库读取入口。
- 输出字段：`app_version`、整数 `version_code`、`build_type`、`android_version:{release,sdk}`、`device:{manufacturer,model}`、`interface_mode:easy/professional`、设备当前资源 `locale`、始终存在的 `recent_errors` 数组。
- `RecentErrorStore` 由 Koin 注册为 App 单例，文件位于 `filesDir/diagnostics/recent_errors.json`。IO dispatcher + mutex 串行读写，通过同目录临时文件、fsync 和 rename 替换；保留最近 20 条，`recent()` 从新到旧返回 10 条。没有数据库版本变更。
- `ChatService.addError` 异步记录异常本身的 message、报错标题、异常简单类名和 ISO 时间，不传入对话文本、conversationId 或助手配置。关闭/清空提示不删除诊断记录。取消异常不记录。
- 后台助手失败经 `BackgroundModelHealth` 的可选回调接入同一 store；持久化标题只有任务名，不保存模型名/配置，原有超时与取消过滤保持原样。
- `redactDiagnosticText(text: String): String` 在写文件之前处理 URL（仅 scheme/host，无端口、路径、query、fragment、userinfo）、sk-*、Bearer、key/token、常见凭据字段、长 base64/hex/base64url、邮箱、IPv4/IPv6；敏感片段替换为 `[已隐藏]`，IP 替换类型标记。脱敏后 message 最多 500 个字符；kind 是代码中的异常简单类名，不作为自由文本脱敏，以保留例如 IllegalArgumentException。
- `app_diagnostics` 是纯本机读取，加入 R9 本地读取白名单，非主人回合仍无需审批。其他工具的 R1–R9 规则不变。
- JVM 用例覆盖各类脱敏、URL host、环形上限/重启、最近 10 条、空列表输出、显式输出字段集合与伙伴配置/聊天隔离、能力 opt-in、R9 本机读取豁免、关闭报错提示后持久化仍保留，以及后台回调不传模型配置。

## 草稿与链接：界面接入约定

包：`me.ayuilos.miffan.data.bugreport`。

```kotlin
data class BugReportDraft(
    val title: String,
    val description: String,
    val actual: String,
    val expected: String,
    val logs: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val app_version: String? = null,
    val android_version: String? = null,
    val device_model: String? = null,
)
fun buildBugReportIssueUrl(report: BugReportDraft): String
fun BugReportDraft.toMarkdown(): String
```

- `draft_bug_report` 参数严格为上述名称，required 为 title/description/actual/expected，未知字段和非字符串（含 null）拒绝。title 去首尾空白后 1–80 个 Unicode 码点且不能带 bug: 前缀；三个必填正文字段不能全空白。输入错误返回 `{success:false,error:...}`，不抛出输入校验异常，不回显原始值。
- 每次 `needsApproval={true}`；工具构造、schema 和审批判断均不读取诊断、写文件或执行其他行为。只有生成器在主人批准后执行工具时才读取当前环境并生成链接。R9 集中包装继续生效，`send_message` 的 R8/R9 和长期联系授权逻辑未修改。
- 空缺/空白环境字段自动补齐：App versionName；`Android <release> (SDK <sdk>)`；`<manufacturer> <model>`。其他参数不从配置/聊天自动读取。logs 的来源约束写在工具描述与参数说明中，只允许选用诊断里的相关摘要。
- 所有字段生成链接/Markdown 前再次经过同一个脱敏函数。链接目标固定为 `https://github.com/Ayuilos/Miffan/issues/new?template=bug-report.yml`，UTF-8 URL 编码。工具 title 不含前缀，链接 title 包含 `bug: `，以保留表单标题前缀（URL title 预填会覆盖表单初始 title）。
- 主人已确认保留现有表单 id：工具 `app_version/android_version/device_model` 分别映射为链接 `app-version/android-version/device-model`，不修改 bug-report.yml。
- 链接上限 7500 字符，按 logs → description 顺序按 Unicode 码点截断，末尾附 `（内容过长，已截断，请在 issue 中补充）`。先把 logs 缩到能满足长度的最大值；若仍超长则 logs 只保留说明，再截 description。其他字段过大且两项截断后仍不能满足上限时明确返回错误，要求缩短其他字段，不返回超限链接。
- 公共纯函数同样校验/脱敏；不合法草稿或无法满足长度限制时抛 IllegalArgumentException，UI 可捕获并展示错误。`toMarkdown()` 输出完整的脱敏内容，不沿用 URL 截断；提供双语章节，省略 null 可选项。
- 成功输出严格为 `{success:true,issue_url:...,note:"The owner opens this link to review and submit on GitHub; you cannot know whether it was submitted."}`。没有网络调用、提交 API、token、浏览器 intent、logcat 或 UI 代码。
- JVM 覆盖审批前无诊断读取、每次审批、参数类型/必填/标题边界、自动环境补齐与显式值、全部字段脱敏、编码解码往返与表单 id、截断顺序/说明/长度、不可截断字段过大、Markdown、两种回合的 opt-in 注册。

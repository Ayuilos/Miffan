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

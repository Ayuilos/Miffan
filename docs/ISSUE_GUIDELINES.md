# Issue 提交规范 / Issue Guidelines

Issue 是 Miffan 的公开协作记录。清晰、可复现、经过脱敏的报告会更容易被定位和处理。
Issues are the project's public collaboration record. Clear, reproducible, and sanitized reports are easier to triage.

## 提交前 / Before opening an issue

1. 搜索已有 Issue、README 和相关文档，避免重复。
   Search existing issues, the README, and relevant documentation first.
2. 一个 Issue 只讨论一个问题或一个需求。
   Keep one problem or request per issue.
3. 确认使用的是较新的 Miffan 版本，并记录 Android 版本、设备型号和相关供应商/模型。
   Confirm the issue on a recent Miffan build and record Android version, device model, and relevant provider/model.
4. 删除 API Key、Token、Cookie、私人对话、文件内容、内网地址和未经脱敏的日志。
   Remove API keys, tokens, cookies, private conversations, file contents, internal URLs, and unsanitized logs.
5. 选择最匹配的 Issue Form；空白 Issue 默认关闭。
   Choose the closest Issue Form; blank issues are disabled by default.

## 哪个模板 / Which form

| 模板 | 适用范围 |
| --- | --- |
| Bug Report | 可复现的崩溃、错误、回归或功能失效 |
| Feature Request | 新能力、改进或产品建议 |
| 使用帮助 / Configuration & Support | 安装、配置、连接和使用问题 |

## 标题与内容 / Titles and content

模板会自动添加 `bug:`、`feat:` 或 `support:` 前缀。标题应简洁说明结果，例如 `bug: Gemini 流式回复在后台暂停`，不要只写“打不开”或“有问题”。
Forms add the `bug:`, `feat:`, or `support:` prefix automatically. Keep titles outcome-focused, such as `bug: Gemini streaming pauses in background`, rather than “doesn't work”.

Bug 报告至少应包含复现步骤、期望行为、实际行为、版本、Android 版本和设备型号。功能请求应说明要解决的问题、使用场景和考虑过的替代方案。使用帮助应说明目标、已尝试的操作和最小错误信息。
Bug reports should include reproduction steps, expected and actual behavior, versions, and device model. Feature requests should explain the problem, use case, and alternatives. Support requests should explain the goal, attempted steps, and the smallest relevant error.

## 处理方式 / Triage

维护者会根据可复现性、影响范围、产品方向、维护成本和安全风险进行分类。可能的结果包括补充信息、标记重复、暂不处理、修复后关闭或转为讨论。Issue 是异步协作渠道，不承诺固定响应时间；请在原 Issue 中补充信息，避免重复开帖。
Maintainers triage based on reproducibility, impact, product direction, maintenance cost, and security risk. An issue may be asked for more information, marked as duplicate, deferred, fixed and closed, or redirected to discussion. Issues are asynchronous; no fixed response time is promised. Add follow-ups to the original issue instead of opening duplicates.

安全问题请先阅读 [安全政策](../SECURITY.md)，不要在公开 Issue 中披露完整利用细节或敏感数据。
For security concerns, read the [security policy](../SECURITY.md) first and do not disclose full exploit details or sensitive data in a public issue.

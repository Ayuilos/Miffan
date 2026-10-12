# 规格：“反馈助手”伙伴模板（数据与工具部分）

前置阅读：
- `AGENT_ISOLATION_SPEC.md`（R1–R7）、`AGENT_SELF_PROFILE_SPEC.md`（R8）、`AGENT_RELAY_AVATAR_SPEC.md`（R9），以及对应的 NOTES；
- `docs/ISSUE_GUIDELINES.md`；
- `.github/ISSUE_TEMPLATE/bug-report.yml`。

所有新工具都必须满足 R1–R9。

## 目标

用户遇到问题时，可以把问题交给一个专门的伙伴“反馈助手”。它负责：
- 先判断是不是配置问题；
- 收集复现信息和诊断信息；
- 按 Bug Report 表单整理成一份报告，交给主人确认；
- 主人确认后，用浏览器打开一个**预填好的 GitHub New Issue 页面**，由主人用自己的 GitHub 账号提交。

App 本身**不提交**，不需要服务器，也不保存任何 token。

主人在 2026-10-13 的决定：
- 提交渠道只走 GitHub 预填链接；
- 诊断信息只包括版本、设备和最近报错的摘要，不附带 logcat；
- 其他伙伴遇到疑似 bug 时，可以**主动建议**主人反馈，但只能是提一句。

## 一、能力开关：`LocalToolOption.BugReport`

新增 `@SerialName("bug_report")`。打开后注册下面两个工具。专业模式和 IM 模式都可以用，开关显示在伙伴的本地工具设置里，和其他本地工具一样。设置页上的开关文案由我来写，你只需要把它加入现有的选项列表。

### 工具 1：`app_diagnostics`（只读，不需要审批）

返回内容：
- `app_version`（versionName）、`version_code`、`build_type`；
- `android_version`（release 和 SDK）、`device`（厂商和型号）；
- `interface_mode`（easy/professional）、`locale`；
- `recent_errors`：最近 10 条 App 报错，从新到旧。每条包含：
  - `at`：ISO 时间；
  - `title`：报错标题；
  - `message`：报错信息，截断到 500 个字符；
  - `kind`：异常类名，不带包路径。

**最近报错的持久化**：
- 现在 `ChatService.addError` 只把报错放在内存里，主人关掉提示或重启 App 后就没了。需要新增一个持久化的环形记录，最多保留 20 条。存在 App 私有文件里，**不改数据库版本**。
- `addError` 每次写入时同时记录一条，后台助手（background helper）已有的失败记录如果方便也接进来。
- **写入时就脱敏**，至少包括：
  - URL 只保留 scheme 和 host，去掉路径、查询参数和用户信息；
  - 形如 `sk-…`、`Bearer …`、`key=…`、`token=…` 的片段，以及长度 ≥ 24 的 base64 或十六进制串，一律替换为 `[已隐藏]`；
  - 邮箱地址；
  - IP 地址只保留类型，例如 `[IPv4]`。
- **不记录任何消息内容**：报错里只保存异常信息本身，不能拼入对话文本。

**隔离要求**：这个工具只返回 App 和设备级别的元数据，以及脱敏后的报错，不能返回任何伙伴的配置（R5）或聊天内容（R1）。这一点要有测试覆盖。

### 工具 2：`draft_bug_report`（每次都需要审批）

参数名和 `bug-report.yml` 的字段 id 对齐：

| 参数 | 必填 | 说明 |
|---|---|---|
| `title` | 是 | 不含 `bug:` 前缀（表单会自动加），1–80 个字符 |
| `description` | 是 | 问题描述，包含复现步骤 |
| `actual` | 是 | 实际行为 |
| `expected` | 是 | 期望行为 |
| `logs` | 否 | 只放 `app_diagnostics` 里相关的报错摘要 |
| `provider`、`model` | 否 | 对应表单里的提供商和模型 |
| `app_version`、`android_version`、`device_model` | 否 | 不填时，由工具从诊断信息里自动补上 |

工具的行为：
- **审批前**：工具什么都不做。界面会用审批卡展示全部内容，审批卡由我来做。
- **执行（主人批准后）**：
  1. 对所有字段再脱敏一次，规则和上面相同。
  2. 生成预填链接：`https://github.com/Ayuilos/Miffan/issues/new?template=bug-report.yml&title=…&description=…`，其他字段同样作为参数加上，并正确做 URL 编码。
  3. 返回 `{"success":true,"issue_url":…,"note":"The owner opens this link to review and submit on GitHub; you cannot know whether it was submitted."}`。打开浏览器这一步由界面负责，工具本身不打开。
- **链接长度**：整条 URL 不超过 7500 个字符。超出时，按 `logs` → `description` 的顺序截断，并在被截断的字段末尾注明“（内容过长，已截断，请在 issue 中补充）”。
- **公开函数**：把“字段 → 链接”的构造写成纯函数，例如 `buildBugReportIssueUrl(report: BugReportDraft): String`，再加上 `BugReportDraft.toMarkdown(): String`（用于“复制内容”按钮）。界面会直接调用这两个函数，所以函数签名和参数名请写进 notes。

## 二、伙伴模板“反馈助手”

在 `ImPartnerTemplates.kt` 里新增一个模板，需要中英两套名字、简介和设定。模板创建的伙伴默认打开以下能力：
- `BugReport`；
- Miffan 使用帮助；
- 保留 `TimeInfo`。

如果 `ImPartnerTemplate` 目前不支持指定本地工具，请给它加一个字段。

设定（system prompt）要覆盖这些要点，用词由你组织，要简短、自然：
- 先理解问题。如果像是配置或用法问题，先用 Miffan 使用帮助查，能解决就直接给出解决办法，**不必报 bug**。
- 确认是 bug 后，追问缺少的信息：复现步骤、期望结果、实际结果、用的哪个伙伴、什么模型。每次只问一两个问题。
- 调用 `app_diagnostics` 补齐环境信息，从中挑出相关的报错放进 `logs`。
- **一份报告只写一个问题。**
- **不放**私聊内容、API key、文件内容、内网地址。
- 用 `draft_bug_report` 生成草稿，并告诉主人：
  - 提交会在 GitHub 上完成，提交后内容公开可见；
  - 主人需要有 GitHub 账号。

## 三、其他伙伴主动建议反馈

满足下面两个条件时，给伙伴注入一段简短提示：
- IM 模式；
- 存在至少一个**开启了 `BugReport` 的其他伙伴**。

提示内容：如果这一轮里出现了疑似 Miffan 自身问题的报错（工具失败、App 报错、模型接口异常等），可以在回复末尾**问主人**要不要交给 @〈反馈助手名字〉；主人同意后，再用 `send_message` 把报错原文、当时在做什么、自己用的提供商和模型整理好发过去。

注入方式参考现有的 `PartnerMentionTransformer` 或 `threadContext`，选更合适的那个。

规则：
- 只能“问”，**不能**在主人同意之前联系反馈助手。
- 如果一个伙伴自己就开启了 `BugReport`，它不会收到这段提示。
- 联系授权沿用现有规则，不新增例外：
  - 主人 @ 了反馈助手，当轮免审批；
  - 否则第一次联系需要审批，之后长期授权；
  - R8 和 R9 规则不变。

## 不做

- 自动提交、服务器接口、GitHub token。
- logcat。
- 界面：审批卡、按钮、复制、打开浏览器、步骤文案、本地工具开关的文案和图标，都由我来做。

## 测试

- **脱敏**：每一类敏感内容都有用例；URL 只保留 host。
- **持久化**：环形记录保留 20 条，重启后仍在，`app_diagnostics` 只返回最近 10 条。
- **`app_diagnostics`**：输出中不含伙伴配置和聊天内容。
- **`draft_bug_report`**：
  - 每次都需要审批；
  - 参数校验；
  - 未填的环境字段会自动补齐；
  - URL 编码正确，可以解码回原字段；
  - 超过 7500 字符时按顺序截断，并带上截断说明；
  - `toMarkdown` 的输出正确。
- **模板**：创建出来的伙伴默认开启了上面列出的能力。
- **主动建议提示**：
  - 存在反馈助手时注入；
  - 不存在时不注入；
  - 反馈助手自己不会收到。

## 验收

- `./gradlew :app:testDebugUnitTest`、`:app:assembleDebug`、`:app:lintDebug` 全部通过。
- **不要使用模拟器**：另一个会话可能正在用，内存也很紧张。只跑 JVM 测试，在 notes 里写明。
- 交付：
  - 提交到 `feature/bug-reporter`，按“诊断与持久化”“草稿与链接”“模板与主动建议”分成三个提交；
  - 写 `docs/agent-collab/BUG_REPORTER_NOTES.md`；
  - 日志和临时文件放在 worktree 的 `build/` 下。
- 有事实性问题，直接在输入框里问。

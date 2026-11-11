# 伙伴帮忙配置 App：安全输入框方案

原型：https://claude.ai/artifact/GxoKz5hxmpPp2gJ3x6ENMr（2026-11-11，待确认）

## 目标与边界

用户在聊天里说"帮我加个 DeepSeek，然后让小鲸用 DeepSeek V3"，伙伴就能把供应商、模型、伙伴用哪个模型这些繁琐的配置做完。API Key 由用户填进聊天里的**安全输入卡**：App 直接写进配置，模型从头到尾拿不到 Key 的任何字符。

- 本轮范围：供应商（添加、改名、开关、自定义地址）、模型（拉取、添加、移除、能力）、伙伴用哪个模型、全局默认模型（聊天 / 快速 / 标题等）。
- 第二步再接同一套机制：搜索服务的 Key、MCP 服务器的鉴权请求头、模型的自定义请求头。
- 不在本轮：删除供应商（Key 一删撤销不回来，只做"停用"）；专业模式（先做轻松模式，专业模式的扩展管理以后再接）；OpenRouter / ChatGPT 订阅的登录授权（继续走现有的浏览器登录，伙伴只能引导用户去点）。

## 现状

- 伙伴能改的只有自己的资料、偏好、能力开关、新建伙伴、群聊和聊天列表；高级模式下的扩展管理能改快捷消息、注入、绑定，明确排除凭据。
- 没有任何工具能动 `Settings.providers`、`Assistant.chatModelId` 或全局的 `*ModelId`。
- `ToolApprovalState.Answered(answer)` 会把答案作为工具结果交给模型，**不能**拿来传 Key。
- 引导页（`ui/im/welcome/`）已经有 Key 输入框（锁图标、等宽字体、掩码回显）和 `OnboardingConnector.verify()`（拉取模型列表来校验 Key、对失败分类），可以直接复用。

## 交互

### 1. 连接供应商卡（安全输入卡）

伙伴调用 `connect_provider` 后，聊天里出现一张卡：

- 顶部：品牌图标 + 供应商名 + **目标域名徽章**（`api.deepseek.com`），一句话"Key 只存在这台手机上，只发给这个地址，伙伴看不到"。
- 中间：Key 输入框（密码样式、可点眼睛临时显示、键盘关闭联想与学习）。空着时右侧是"粘贴"，有内容时是"连接"。附"还没有 Key？去官网申请 ↗"。
- 提交后卡片内原地显示"检查中"→ 结果：
  - **连上了**：显示"找到 N 个模型"，卡片收起成一条记录（✓ DeepSeek 已连接 · sk-••••••），伙伴继续说话。
  - **Key 不对 / 连不上**：错误留在卡片内，用户可以直接改了再试；这些尝试**不**回传给模型。连不上时额外给"先保存，稍后再试"。
  - **取消**：卡片上的"不连了"。
- 自定义服务（OpenAI 兼容）：伙伴在调用时给出名字和地址，卡片里地址只读展示，用户觉得不对可以点"地址不对"拒绝。
- 已存在且有 Key 的供应商重新填 Key：同一张卡，标题写"更换 Key"。

一张卡就是这次添加的批准，不再多一张"是否添加"的审批卡。

### 2. 配置变更卡（沿用扩展管理的预览 → 批准）

选模型、给伙伴换模型这类改动，伙伴先预览，聊天里出一张变更卡：每条改动一行（图标 + 短句，例如"小鲸 → DeepSeek V3"、"添加 3 个模型"），底部"好的 / 不用了"。批准后变成可撤销的变更记录，和现在的审批卡记录一致。

### 3. 用户把 Key 直接发在聊天里

输入框发送前识别像 Key 的文本（`sk-…`、`AIza…`、长随机串，规则复用 `DiagnosticRedactor`）：

- 有待填的安全输入卡时：提示"这像是 API Key，填进上面的安全框吧"，一键转进去，聊天里不发。
- 没有待填卡时：提示"这像是 API Key，发出去伙伴就能看到"，给"仍然发送 / 不发了"。

## 工具设计

只在轻松模式、主人发起的回合（`ownerTrigger != null`）里提供；群聊里其他伙伴触发的回合、中继回合一律拒绝。

| 工具 | 作用 | 审批 |
|---|---|---|
| `app_settings_catalog` | 读：供应商（id、名字、预设/自定义、接口类型、**域名**、开关、Key 状态 `set/unset`、模型数）、模型（id、显示名、能力）、各处在用的模型（全局各角色、每个伙伴）、可选的预设供应商列表 | 无 |
| `connect_provider` | 用预设添加，或用（名字、类型、地址）添加自定义服务，或给已有供应商换 Key；弹安全输入卡 | 卡片本身 |
| `app_settings_preview` / `app_settings_apply` | 预览并应用一组改动：开关/改名供应商、添加/移除模型、改模型能力、设置伙伴模型、设置全局默认模型、改自定义服务地址 | 变更卡 |

`connect_provider` 的结果只有：

```json
{"status": "connected", "provider_id": "…", "models": [{"model_id": "deepseek-chat", "name": "DeepSeek V3", "abilities": ["tool"]}]}
{"status": "saved_unchecked", "provider_id": "…"}
{"status": "cancelled"}
```

模型列表交给伙伴后，伙伴可以给建议，但选哪些、给谁用，仍然通过变更卡由用户确认（与引导页"不替用户预选模型"的决定一致）。

## 安全设计

### 输入通道

- 新增 `SecretSlot`：伙伴调用 `connect_provider` 时，App 在内存里生成一个槽位，**绑死**到（供应商 id、字段 `apiKey`、当时展示的域名、这次工具调用 id）。模型拿不到能转交的引用，Key 只能落到这一个字段。
- 用户提交时，App 先核对该供应商当前的域名仍等于卡上展示的域名，不一致就拒绝写入并提示。
- Key 走独立的写入路径：卡片 → `SecretSlotService.fill()` → `settingsStore.update`。审批状态只记 `Approved`，**不用** `Answered(answer)`；工具结果由 App 生成，只含上面的状态。
- 只能在手机 App 里填写：web 界面（`web/routes/ConversationRoutes.kt`）、通知、伙伴页对这类调用只显示"请在手机上填写"，接口层也拒绝。

### 防止 Key 被导去别处

- 预设供应商的地址，工具一律不能改（用户自己在设置页改不受影响）。
- 有 Key 的自定义服务改地址：应用时**同时清空 Key**，伙伴需要再发一张安全输入卡，用户会在卡上看到新域名。
- 工具不提供：复制供应商、模型的 `providerOverwrite`、自定义请求头和请求体、余额接口路径这类能把请求导向别处的字段。

### 所有出口都不带 Key

- `app_settings_catalog` 和预览只给"已设置 / 未设置"，不给前后几位。
- 校验和拉取模型的报错先抹掉 Key 原文和 `DiagnosticRedactor` 能识别的凭据，再截断，才进入卡片和工具结果。
- 剪贴板能力读到像凭据的内容时返回"[已隐藏一段像密钥的内容]"；安全卡里点"粘贴"后，App 清空剪贴板（API 28+）。
- 变更记录（撤销）：新增 `RevisionSubject.PROVIDERS`，快照里的密钥字段一律抹空；撤销时按供应商 id 保留当前的 Key，只有被撤销的正是"填 Key"时才清空 Key。重做"填 Key"不恢复 Key，而是提示重新填写。
- 通知、审计摘要、bug 报告、日志：只写"连接了 DeepSeek"，不带 Key。
- Key 输入框：`KeyboardType.Password` + 关闭自动纠错（输入法进入无痕模式），卡片显示期间给窗口加 `FLAG_SECURE`，防录屏和最近任务缩略图。

## 谁能用

新增伙伴能力 `app_settings`（"帮你配置 App：AI 服务和模型"），默认关闭，伙伴可以用 `request_ability` 申请，用户也可以在伙伴资料页打开，与日历、剪贴板等能力一致。

## 分阶段

1. **P1 安全输入卡 + 连接供应商**：`SecretSlot`/`SecretSlotService`、`connect_provider`、`app_settings_catalog`、卡片 UI（复用引导页的 Key 输入框和 `OnboardingConnector.verify`）、web/通知拒绝、报错脱敏、剪贴板清空与隐藏。
2. **P2 模型与分配**：`app_settings_preview/apply`、变更卡、`RevisionSubject.PROVIDERS` 撤销、地址变更清 Key。
3. **P3 发送前识别 Key**：输入框识别与转入安全卡。
4. **P4 其他凭据**：搜索服务 Key、MCP 鉴权请求头、自定义请求头，接同一个 `SecretSlot`（字段类型扩展）。
5. 帮助文档（miffan-help）同步一篇"让伙伴帮你配置"。

## 验收

- 单元测试：用一个哨兵 Key（`sk-SENTINEL-…`）走完连接、换模型、撤销、重做、导出 bug 报告，断言它不出现在任何消息、工具结果、审批记录、变更快照、通知文本、web 接口响应里。
- 单元测试：域名不一致拒绝写入；改自定义服务地址会清 Key；web 路由回答安全卡被拒；非主人回合调用被拒。
- 模拟器（脚本化假 LLM）：添加预设供应商、Key 错误重试、连不上先保存、自定义服务、取消、给伙伴换模型、撤销。
- 真机：DeepSeek / Gemini 真 Key 各走一遍；第三方输入法不出现联想；最近任务缩略图被遮挡。

## 已定（2026-11-11，用户同意全部建议）

1. **能力默认关闭**，由伙伴用 `request_ability` 申请，或主人在伙伴资料页打开。
2. **安全卡就是批准**：一张卡完成"添加 + 填 Key"，不再弹审批卡。
3. **模型看不到 Key 的任何字符**，只有"已设置 / 未设置"；掩码只在主人的卡片记录里显示。
4. **改自定义服务地址会清空 Key**，需要重新填写。
5. **本轮不能删除供应商**，只能停用。

## P1 实现记录（2026-11-11）

- 数据：`data/appsettings/`（`AppSettingsCatalog`、`ProviderConnect`、`ProviderConnectCards`、`CredentialRedaction`）。
- 工具：`data/ai/tools/AppSettingsTools.kt`（`app_settings_catalog`、`connect_provider`）；能力 `PartnerAbility.APP_SETTINGS` / `LocalToolOption.AppSettings`；只在轻松模式、主人回合提供。
- 卡片：`ui/im/thread/ThreadConnectProviderCard.kt`。卡片显示期间加 `FLAG_SECURE`；"粘贴"读一次剪贴板后清空。
- 防护：`ChatService.completeSecretCard` 是批准或回答这类调用的唯一入口，判断逻辑在 `secretCardDecisionIds`；网页端、伙伴屏幕、"全部允许"都只能拒绝。卡片完成前会先加载已被释放的会话（`loadStoredSession`）。
- 剪贴板能力读到像 Key 的内容会替换成"[已隐藏一段像密钥的内容]"。
- 与方案的出入：连接结果暂不自动添加模型（P2 由变更卡添加）；拒绝时伙伴收到通用的"用户拒绝"结果，没有单独的 `cancelled` 状态。
- 验证：`AppSettingsTest`（13 项，含哨兵 Key 全出口检查）；模拟器上用脚本化假模型走完自定义服务、预设 DeepSeek（真实接口拒绝错误 Key）、报错脱敏、粘贴并清空剪贴板、剪贴板隐藏、取消、参数无效。服务端记录的所有聊天请求中 Key 出现 0 次；卡片显示时截屏为黑屏。

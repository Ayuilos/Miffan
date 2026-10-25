# 对话式引导重构计划

原型：https://claude.ai/artifact/Gi9LAkPummfQYkLA69mivD（2026-10-24 确认方向）

## 目标与边界

新用户第一次打开 Miffan，不再面对一个“选 AI 服务商”的设置页，而是直接进入和 Miffan 的对话：Miffan 先自我介绍，在对话里带着用户连上 AI 服务、选一个伙伴（或自己捏一个），然后由伙伴说出第一句真正由模型生成的话。

- 一套引导同时服务轻松模式和专业模式，删除旧的 `OnboardingPage`（专业模式）和 `ImOnboardingPage`（轻松模式）。
- 不按地区区分用户。“一键登录”放哪家只是数据，现在是 OpenRouter。
- 不做任何“猜”：不根据 Key 推测服务商。用户先选服务商，再粘贴这一家的 Key。
- 不在本轮：新的一键登录服务商（以后加，只是往数据里加一项）、引导内填写 WebDAV / S3 账号。

## 已确定的交互

### 开场

- 打开 App 进入一个长得和聊天页一样的引导页：顶部是 Miffan 的头像和名字，状态写“还没连上大脑”；输入框是灰的，提示“连上大脑后就能和 Miffan 聊天”。
- Miffan 依次发三句预设的话（带“正在输入”动画），然后发出“给 Miffan 连上大脑”卡片。
- 这些预设台词不是模型生成的，也不写进任何对话记录（见“待定”第 1 条）。

### 连上大脑卡片

一张卡片，从上到下：

1. **用 OpenRouter 登录**：浏览器授权（沿用 `OpenRouterAuthService`），有免费模型。
2. **用 API Key 连接**：副标题“先选服务商：DeepSeek、Kimi、OpenAI、Gemini 等 19 家”，打开服务商选择面板。
3. 底部两个小按钮：**从备份恢复**、**我是老手，用专业模式**。

用户的每个选择都以“我”的气泡回显在对话里（如“用 DeepSeek”），之后的进展都以 Miffan 的消息或卡片出现。卡片在用户选过之后仍保留，可以回头换一种方式。

### 用 API Key 连接

- 服务商面板：底部弹出，带搜索；每行是品牌图标（`AutoAIIcon`）、名字、一句说明；OpenRouter 带“一键登录”标签；最后一项“其他服务（自己填地址，兼容 OpenAI 接口）”。
- 选了一家后，Miffan 说“好，把 DeepSeek 的 API Key 粘贴到下面就行”，并附“还没有 Key？去 DeepSeek 官网申请一个 ↗”（打开这家的 Key 申请页）。
- 输入框变成 Key 输入框（带锁图标，等宽字体），右侧按钮：空的时候是“粘贴”（读剪贴板直接提交），有内容时是“连接”。
- 输入框下方说明：“Key 只保存在这台手机上，只用来连接 DeepSeek。”
- 提交后，“我”的气泡只显示掩码（`sk-7f3a••••••0c21`）。
- “其他服务”需要地址和 Key：在对话里出一张小表单卡片（地址、Key），提交后走同样的检查。

### 检查与挑模型

一张进度卡片，逐行更新：

1. “用这个 Key 连一下 DeepSeek”（转圈）→“Key 能用”（对勾）。
2. “挑一个合适的模型”→“挑好了模型 · DeepSeek 的日常聊天模型，之后随时能换”。
3. 系统行：“已连接 DeepSeek · 以后可以在「我 › AI 服务」里更换”。

失败时该行变成红叉：“DeepSeek 说这个 Key 用不了”，下面一句人话原因，卡片上有“换一个 Key”和“去 DeepSeek 看看”。网络不通、服务商出错、找不到可用的聊天模型，各有自己的说法（见“连接与错误分类”）。

OpenRouter 路线：进度卡片先显示“等你在浏览器里登录 OpenRouter”，带“取消”和“浏览器没打开？”；回到 App 后变成“登录成功”→“挑好了：免费模型（每天有次数限制，之后可以换成付费模型）”。Miffan 额外说一句“免费模型偶尔会慢一点，每天的次数用完了我会告诉你怎么办”。

### 选伙伴

连上之后 Miffan 说“连上啦！最后一步：想让谁陪你聊？可以就是我，也可以换一个，或者自己捏一个”，然后发出伙伴卡片：

- Miffan（就是我！什么都可以聊）
- 蓝色大肥鱼（说明里写明“会换成蓝色主题”）
- 现有四个模板伙伴（`imPartnerTemplates`，不含反馈助手）
- 最后一行“自己捏一个 · 起名字、挑样子、定性格”

点一个就是选定，不需要再按确认。

### 自己捏一个

对话里出一张卡片，顶部是实时预览（头像、名字、性格）：

- **样子**：一排 Miffan 配色（六种），外加“更多”（打开现有 `ImLookEditor`，可换造型、用照片）。
- **名字**：必填，没填时“就是 TA 了”不可点。
- **性格**：可多选标签（沿用 `ImCustomPartnerStep` 的 `TRAITS`）。
- **还想补充点什么**：可不填。
- 按钮：“就是 TA 了”“回去选”。Miffan 提示“这些以后在伙伴资料里都能改”。

system prompt 的拼法与现有“添加伙伴 › 自己捏”完全一致（抽出共用函数，见下）。

### 伙伴先开口

- 选 Miffan：引导结束，打开 Miffan 的聊天，Miffan 用模型说出第一句。
- 选别的伙伴或自己捏的：Miffan 说“好，我把 X 叫来。想找我的话，我一直在「伙伴」页里”，随后打开 X 的聊天，时间线里一行“你添加了 X”，然后 X 用模型说出第一句。
- 第一句话下面给 3 个开场建议（介绍一下你自己 / 你能帮我做什么？/ 随便聊聊），点了就是发送。
- 选大肥鱼同时换主题（沿用 `withWhaleThemeTrial`）。

### 从备份恢复

- Miffan：“欢迎回来！你的备份放在哪儿？”，给出选项：手机里的备份文件 / WebDAV / S3 / 其他 App（Chatbox、Cherry Studio）。
- 手机里的备份文件：直接打开系统文件选择器，恢复后弹出现有的重启提示（`BackupDialog`）。
- 其余选项进入现有的轻松模式备份页（`ImBackupPage` 及其 WebDAV / S3 分页），它们需要填账号，不在对话里做。
- 恢复的用户跳过选伙伴（备份里已有伙伴）。

### 专业模式

- Miffan 解释一句专业模式是什么、之后能在设置里切回来，给出“切换到专业模式”和“还是留在这里”。
- 切换后：界面模式改为专业，引导标记完成，打开服务商设置页（`SettingProvider`）。配好模型后回到专业模式首页。
- 专业模式下删光了所有模型的用户，下次启动也进入这个对话式引导，直接从“连上大脑”开始，不出现选伙伴。

## 架构

### 引导阶段与启动路由

新增设置项 `onboarding: OnboardingState`（`done: Boolean`）。启动时：

| 条件 | 进入 |
|---|---|
| `isNotConfigured()` | 引导页，从开场开始 |
| 已配置、轻松模式、`!done` | 引导页，直接到选伙伴（中途退出 App 后回来） |
| 其余 | 照旧（`navigateHome`） |

- 迁移：首次创建这个设置项时，`launchCount > 0` 或已有保存的服务商就写 `done = true`，与 `initialInterfaceMode` 同样的判断，老用户永远不会看到选伙伴。
- 引导完成（选定伙伴、恢复备份、切到专业模式）时写 `done = true`。

### 引导页状态

`ImWelcomeVM` 持有一个由事件驱动的状态机，对话内容由状态推导，而不是把每条气泡存下来：

```
Greeting → Connect → (ProviderSheet) → KeyInput(provider) → Checking(provider) → KeyFailed(provider, reason)
                   → OpenRouterWaiting
                   → Backup / Pro
Connected(provider, free) → PickPartner → Diy → Finishing(partner)
```

- 消息列表 = 当前状态对应的历史气泡（纯函数生成，方便测试和快照）。
- 进程被杀后：阶段由设置推导（未配置 → `Connect`，已配置未完成 → `PickPartner`），开场台词直接显示、不再播放动画；Key 输入内容不保存。
- OpenRouter 授权完成时 `OpenRouterAuthService.state` 变为 `Connected`，VM 监听它进入 `Connected`（与现在一样，回调可能在进程重建之后才到）。

### 连接与错误分类：`OnboardingConnector`（新，data 层）

```kotlin
suspend fun connect(provider: ProviderSetting, apiKey: String): ConnectResult
sealed interface ConnectResult {
    data class Ok(val settings: (Settings) -> Settings, val model: Model)
    data object KeyRejected      // 401 / 403
    data object Unreachable      // 网络错误、超时
    data class ServiceError(val code: Int?, val message: String)
    data object NoChatModel      // 列表里没有能聊天的文字模型
}
```

1. 用带新 Key 的 provider 副本调用 `ProviderManager.getProviderByType(..).listModels(..)`，以此验证 Key（OpenAI 兼容、Gemini、Claude 都支持）。
2. 用 `ModelRegistry.resolveCapabilities` 补全能力，交给 `pickOnboardingChatModel` 挑聊天模型。
3. 成功后一次 `settingsStore.update`：写入 Key、启用该 provider、写入挑中的模型、设为 `chatModelId`，再套 `withSuggestedFastModel`。没成功就不写任何设置。

`pickOnboardingChatModel(provider, models)`（纯函数，单测覆盖）：

- 先按每家服务商的偏好列表（`OnboardingProviderHints`，按 provider id，写首选模型 ID 前缀，实现时核对各家当前的模型名）匹配。
- 没有命中就回退：只留 `ModelType.CHAT`、输入输出都是文字、不是图像 / 语音 / 向量模型（复用 `listedFor(ModelType.CHAT, textOnly = true)`），优先支持工具调用的，再按名字稳定排序取第一个。

`OnboardingProviderHints` 同时放“去哪里申请 Key”的网址和服务商面板里的一句说明，数据层一处维护。

### 伙伴先开口

在 `ThreadService` 加 `greetNewOwner(assistantId)`，内部调用现有的 `deliverFrom`：

```kotlin
deliverFrom(
    origin = MessageOrigin.Event(kind = "partner_added", summary = "你添加了 $name"),
    assistantId = assistantId,
    content = listOf(UIMessagePart.Text(greetingInstruction)),
)
```

- 时间线显示 summary 那一行，模型读到的是引导语（“主人刚认识你，用你的性格打个招呼，问一个轻松的问题，不超过两句”）。事件来源不带主人权限，与伙伴私聊回传一致。
- 选 Miffan 时 summary 用“开始和 Miffan 聊天”。
- 开场建议用现有 `quickMessages` 机制：引导完成时给该伙伴挂 3 条建议（`ThreadEmptyState` 已经会显示）。若第一句已出现，改为在第一条回复下方显示，需要在时间线加一种只在“只有一条伙伴消息”时出现的建议行。

### 自己捏：共用拼装

把 `ImAddPartnerPage` 里的 `TRAITS`、prompt 拼装和 `randomMiffan` 抽到 `ui/im/PartnerDraft.kt`：

```kotlin
data class PartnerDraft(val name: String, val avatar: Avatar, val traits: List<Int>, val extra: String)
@Composable fun PartnerDraft.toAssistant(): Assistant
```

“添加伙伴 › 自己捏”和引导里的捏伙伴卡片都用它，保证两边生成的伙伴一样。

### 界面组件

全部放在 `ui/im/welcome/`：

- `ImWelcomePage`：脚手架，顶栏、对话列表（`reverseLayout`，新消息贴底）、底部输入区（灰色提示 / Key 输入 / 关闭）。
- `WelcomeBubbles`：Miffan 气泡、“我”的气泡、系统行、正在输入。样式与 `ThreadPartnerMessage` 和时间线系统行保持一致，能直接复用的直接复用。
- `ConnectCard`、`ProviderSheet`、`CheckingCard`、`PartnerPickCard`、`DiyPartnerCard`、`ChoiceChips`。
- 头像一律用 `AssistantAvatar` / `MiffanMascot`，按语义状态传参（连接中 Thinking、失败 Error、成功 Happy），不复制绘制逻辑。

删除：`ui/pages/onboarding/OnboardingPage.kt`、`OnboardingTheme.kt`、`ui/im/ImOnboardingPage.kt`、对应的 androidTest，以及只被它们用到的字符串。仍被别处使用的 `onboarding_page_retry`、`onboarding_page_recommended_badge` 改名后保留。

## 开发步骤

1. **数据层**：`OnboardingState` 设置与迁移、`OnboardingConnector`、`pickOnboardingChatModel`、`OnboardingProviderHints`、`ThreadService.greetNewOwner`、`PartnerDraft` 抽取。单测：模型挑选、错误分类（用 MockWebServer 模拟 401 / 超时 / 500 / 空列表）、迁移判断、prompt 拼装与原来一致。
2. **引导页**：`ImWelcomeVM` 状态机与消息推导（单测），各卡片组件，接上连接、OpenRouter、伙伴、捏伙伴。
3. **路由与收尾**：`RouteActivity` 启动判断、专业模式分支、备份分支、进程重建恢复、返回键（引导中返回键退出 App，面板和捏伙伴卡片优先关闭自己），删除旧引导。
4. **字符串**：先写英文和简体中文；其余四种语言（ja、ko、ru、zh-TW）按规则交给 herdr 里的 haiku 代理翻译。
5. **验收**：在 `emulator-5562`（`Miffan_Fresh_API_35`）全新安装后逐条走：
   - OpenRouter 一键登录（真实授权）
   - API Key：用本机的模拟 OpenAI 服务，以“其他服务”填 `http://127.0.0.1:<port>/v1`，覆盖成功、Key 被拒、服务出错、空模型列表
   - 选 Miffan / 选模板伙伴 / 选大肥鱼（主题切换）/ 自己捏
   - 从备份恢复（本地文件）、切到专业模式
   - 选伙伴前杀掉进程再打开，应直接回到选伙伴
   - 老用户覆盖安装不出现引导

## 待定

1. **预设台词是否进入 Miffan 的聊天记录**。建议不进：它们不是模型说的，写进去会被当成上下文；引导结束后 Miffan 的聊天从“开始和 Miffan 聊天”那一行开始。
2. **Key 能用但余额不足**。列模型通常不需要余额，余额问题要到第一句话才暴露。建议第一句话失败时，在聊天里显示现有的错误提示，并附“去「我 › AI 服务」看看”；需要确认现有错误提示对新用户足够友好。
3. **服务商面板的顺序**。建议沿用 `DEFAULT_PROVIDERS` 的顺序，OpenRouter 置顶（因为能一键登录），Magpie 放到最后。

## 实现记录（2026-10-25）

三条待定按建议执行：预设台词不进聊天记录；余额问题在第一句话失败时提示并可去「我 › AI 服务」；服务商顺序为默认顺序、OpenRouter 置顶、Magpie 置底。

和上面方案不同的地方：

- 设置项是一个布尔值 `Settings.onboardingDone`（不是 `OnboardingState` 对象），由 `OnboardingMigration` 写入；阶段判断在 `data/onboarding/Onboarding.kt` 的 `onboardingStage()`。
- 没有新增 `ThreadService.greetNewOwner`：`ImWelcomeVM` 直接调用 `deliverFrom`，引导语和时间线那一行都走字符串资源。
- 开场建议不写入 `quickMessages`：聊天里只有一条伙伴消息、主人还没说话时，在下面显示伙伴自己的快捷消息，没有就显示三条默认开场（`ThreadStarterChips`）。
- 拉模型列表失败改为抛出带状态码的 `ModelListException`（OpenAI、Claude、Gemini 三种接口都是），Gemini 失败时不再静默返回空列表。错误提示里新增「余额不够」一类（402、insufficient 等），并给出「去看看」入口。
- 选 Miffan 或任意伙伴完成引导时，默认助手若没有名字会被命名为 Miffan，和引导里的自我介绍一致。
- 失败后的重试按原因区分：Key 被拒 / 没有聊天模型 →「换一个 Key」；网络或服务出错 →「再试一次」（同一个 Key，只在内存里）；需要填地址的服务 →「改一下再试」（表单带上刚填的地址）。
- 兜底挑模型时，名字里带 thinking / reasoner 的模型排在后面。
- 选错了能回头（2026-10-25 加）：粘贴 Key 时 Miffan 那句下面有「换一家」；连上之后、选伙伴之前，「已连接 X」下面有「换一个」，会撤掉刚写入的 Key 和模型（同一次运行里精确恢复连接前的设置，重启后则清空该服务的 Key 和模型、删掉手填的服务）；选伙伴改为先选中、再点「就是 TA 了」。
- 模型由用户自己选（2026-10-25 改）：不再自动挑模型，也不做推荐。Key 验证通过后，Miffan 问「想用 X 的哪个模型聊天？」，列出这家所有能聊文字的模型（按名字排序、无预选，超过 6 个时带搜索），用户选中并点「用这个模型」后才保存。验证与保存分开：`OnboardingConnector.verify` 只拉列表，`save` 才写入设置。`OnboardingProviderHints` 里的首选模型列表已删除。OpenRouter 一键登录后同样让用户选：登录时已先接入免费路由 `openrouter/free`（浏览器回来时可能已是新进程，必须当场存），随后拉 OpenRouter 的模型列表，免费路由第一、其他 `:free` 模型其次（都带「免费」标记）、其余按名字；选了别的就把聊天模型换过去，选免费的则后台快速模型也只从免费模型里配。拉列表失败时直接用已接入的免费路由。

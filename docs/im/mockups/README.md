# Miffan IM 设计稿

风格探索已评审，三个方向的 UX 均达标，配色由自定义主题决定。现已按 [设计说明](../DESIGN_BRIEF.md) 完成 01～25 共二十五张完整稿，其中七张已按第一轮评审覆盖修正；另保留原六张 a/b/c 风格探索图。完整稿重点为 IM 信息结构、交互路径、状态反馈与可发现性。

## 第二轮：第一轮修正与第二批关键状态

已按 [设计说明](../DESIGN_BRIEF.md)流程第 3 步完成本轮 **7 张覆盖重画 + 10 张新增**。开工时先通过 view_image 载入 [02-thread-parallel.webp](02-thread-parallel.webp) 和当时版本的 [09-partner-profile.webp](09-partner-profile.webp)作为中性主题、字号层级和角色参考。全部使用 imagegen 技能的**内置 image_gen 工具**，未使用 CLI 回退。

### 本轮覆盖重画

| 文件 | 修正点 | 提示词要点 |
| --- | --- | --- |
| [01-chats-tab.webp](01-chats-tab.webp) | 平铺六行、置顶/未读/输入状态；左滑置顶与不显示；说明保留记录、新消息重新出现。 | 替换删除动作及图标；隐藏语义说明；四 Tab。 |
| [03-thread-topic-filter.webp](03-thread-topic-filter.webp) | 猫名筛选与关闭；隐藏其他话题；去掉回复条；占位明确自动归入本话题。 | 仅猫名消息；组首头像；精确占位，无引用叠加。 |
| [06-settings-history.webp](06-settings-history.webp) | 三来源人话总结；展开后人话总结/原文差异切换；真正提示词红删绿增；触发跳转与旧版恢复。 | 人话摘要保留在上；选中原文差异；提示词文本而非标签差异。 |
| [07-memory.webp](07-memory.webp) | 卡片改平铺分隔行；每行由 Miffan 记下及日期；来源可跳转、可删除；历史入口。 | 精确顶部说明；三条同来源身份；禁用独立卡片。 |
| [09-partner-profile.webp](09-partner-profile.webp) | 发消息主按钮；名字旁编辑名字/头像/性格；原记忆/能力/历史/背景保留；底部清空和二次确认删除入口。 | 缩小头像腾出操作空间；主次清晰；删除小红字。 |
| [11-me-tab.webp](11-me-tab.webp) | 中性 AI 服务连接图标；新增备份恢复/通知/语言/关于；高级保留专业模式切换；个人资料与四 Tab。 | 紧凑平铺设置行；无厂商商标；高级展开。 |
| [12-onboarding.webp](12-onboarding.webp) | 直接列纯文本服务选项；一键登录优先；API Key 选项；已选中一项才启用继续。 | 无商标的中性图标；单选态；继续按钮可用的因果。 |

### 第二批 16～25：关键状态

| 文件 | 画面 | 必须体现与检查结果 | 提示词要点 |
| --- | --- | --- | --- |
| [16-thread-generating.webp](16-thread-generating.webp) | 生成中 | 刚发送用户消息；末尾小号吃饭 Miffan 与正在输入气泡；无进度条或模型信息。 | 碗上张嘴与饭粒表达吃饭；历史头像静态；末尾等候。 |
| [17-thread-tool-status.webp](17-thread-tool-status.webp) | 工具状态 | 完成状态查了3网页可折叠；另一个查询展开为正在查资料；回复正文同时正常显示。 | 两条人话状态，完成与进行并存；隐藏原始工具细节。 |
| [18-thread-permission-card.webp](18-thread-permission-card.webp) | 权限确认卡片 | 用户新闻请求；伙伴解释开启能力；允许/这次不用双按钮；未授权状态清楚。 | 卡片明确对应资料卡能力开关；语气友好；不抢先执行。 |
| [19-thread-error.webp](19-thread-error.webp) | 出错与重试 | 网络提示与重试；中性表情、小提示标记；不哭、不显示错误码。 | 点嘴竖眼不改形态；小蓝感叹标记；柔和可恢复反馈。 |
| [20-thread-memory-notice.webp](20-thread-memory-notice.webp) | 记忆系统提示 | 用户橘猫事实；居中灰色记忆通知；查看/撤销；随后伙伴回复。 | 系统记忆与伙伴气泡区分；精确通知；可回溯可撤销。 |
| [21-thread-quote-jump.webp](21-thread-quote-jump.webp) | 点击引用后定位 | 滚动回原消息并短暂高亮；底部回到最新悬浮按钮；定位不改变输入语义。 | 高亮用户原问题而非回复；较新消息在视口下方；回到最新可见。 |
| [22-thread-voice.webp](22-thread-voice.webp) | 按住说话录音态 | 按住说话录音态；大号波形；0:04；松开发送/上滑取消；文字输入被替换。 | 原生录音反馈；手势说明明确；无手部插画。 |
| [23-thread-attach-panel.webp](23-thread-attach-panel.webp) | 附件面板 | 加号选中；输入框下方展开三项图标网格；仅照片/拍照/文件。 | 单行三格附件；面板依附输入区；无其他工具。 |
| [24-search.webp](24-search.webp) | 搜索结果 | 搜索团子；无伙伴匹配则隐藏该组；聊天记录显示头像/名字/命中片段/日期。 | 三行来源与局部团子高亮；不呈现空伙伴组。 |
| [25-thread-empty.webp](25-thread-empty.webp) | 第一次与写作搭子聊天 | 新伙伴写作搭子；大号 Miffan 欢迎、自我介绍；三个可点击直发建议；空时间线。 | 建议与已发送气泡位置区分；角色无身体；自然开始聊天。 |

### 本轮核对与重生成

- **引用规则**：02、14 的并行批次三条回复均保留引用条，未改文件。03 的单话题连续回复不加引用条，也不叠加“回复 Miffan”；占位文字明确在“猫的名字”中发言。本轮其他单线相邻回复均不加引用条，21 定位后的天气回复仍属于并行批次，保留引用。
- **列表与资料路径**：01 平铺行与分隔线；左滑“不显示”保留历史，新消息恢复列表项。07 也改为平铺分隔行并明确记录伙伴身份。09 把“发消息”作为主操作，编辑入口就在名字旁，真正删除记录位于资料卡弱化区域并写明会再次确认。
- **状态表达**：16 只在末尾小号 Miffan 的张嘴／饭粒中表达吃饭，旁边是“正在输入…”。19 保留竖椭圆眼和点嘴的中性脸，以小蓝提示标记和“重试”表达网络问题，未使用哭脸、眼泪或夸张警报。
- **服务与商标**：11 使用中性链接图标；12 为满足直接列出服务选项的要求，使用服务商的纯文本名称与相同中性链接图标，不显示厂商商标图形。没有模型名、token、参数或原始工具调用信息。
- **权限与记忆**：18 显示两个选择，说明能力开关的含义，授权前不显示已查询到新闻的结果；20 的灰色系统记忆提示和伙伴回复分开，查看与撤销为独立文字操作。
- **录音、附件、搜索、首次聊天**：22 普通文字输入被录音态替换，波形、0:04、松开发送和上滑取消齐全；23 只有照片、拍照、文件；24 选择无伙伴名称匹配的状态，所以不呈现空的伙伴分组；25 的建议在空白时间线中央，和已发送消息区别清楚。
- **17 已定点重生成**：首版折叠状态行出现两个右箭头；采用修正版，只保留末尾一个展开箭头，展开进行态和正常正文保持可见。首版未复制到仓库。
- **范围核对**：本轮仅覆盖 01、03、06、07、09、11、12；新增 16～25；02、04、05、08、10、13、14、15 与六张 a/b/c 探索图保持原文件，设计说明、体验计划和代码均不修改。

### 已知瑕疵与沿用记录

- 本轮 17 张均为 **853×1844**，未拉伸、裁切或用程序改图；关键中文和交互控件经逐张检查，没有关键缺失或文字错乱。
- 06 用红／绿底色和减号／加号展示原文删改；正式原生实现可进一步强化长文本差异的字符级对比。本图已显示完整提示词句子的旧值与新值。
- 16 为静态吃饭姿态，饭粒在小头像内较细；静态稿不表达完整动画节奏，保持可读碗、脸与饭粒为本轮验收点。
- 12 只展示“已选中一项，继续可用”的状态，并通过提示明确选择前不可继续；未额外生成独立的未选中截图。
- **15 按用户要求未重画**，因此仍沿用第一轮的“置顶／删除”左滑示意。当前会话列表行为以本轮 01 的“置顶／不显示”为准，15 仅作保留的主题示意。
- 第一轮的 04 菜单避让导致选中气泡略向下移、05 通知短语换行、13 缩略预览小字与稍长画布等记录继续保留在下方“重生成记录与已知瑕疵”中。

### 第二批实际生成提示词

下方为 16～25 最终成品的实际提示词；七张重画图的实际提示词已替换下方“完整稿实际生成提示词”中对应记录，并标注第二轮重画。

<details>
<summary>16-thread-generating.webp · 生成中</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 16-thread-generating:
Chat header back/Classic bowl/“Miffan”; time separator“今天21:03”. Small older exchange right“今天有点累”; left resting bowl“那就慢慢来，我在。” (single topic adjacent reply NO quote).
Latest user right“帮我想个轻松的晚餐吧”, optional user avatar at new group head. At true END of timeline below user, tiny active28-36dp Classic Miffan avatar and a small gray-blue bubble exact“正在输入…” with three soft dots.
ACTIVE mascot is EATING rice expression, not just default face: TWO gentle oval eyes, rounded open mouth ON bowl, tiny readable white/gold rice grain AT mouth, small grain approaching mouth from rice rim, visibly content while eating. Rice mound still present and rim/face/bowl readable. No hands/arms/chopsticks, no spiral eyes, no tears, no sad face. Head mascot appbar and old message avatar remain idle; only final avatar eats. Keep status bubble SMALL and native, not giant empty reply.
No progress bar, percentage, spinner dashboard or model information. Composer microphone/“发消息…”/plus still present. Latest user message + eating typing indicator causal order, no finished answer yet.
```

</details>

<details>
<summary>17-thread-tool-status.webp · 工具状态</summary>

```text
Use case: precise-object-edit.
Asset type:17-thread-tool-status Android UI.
Input image1 EDIT TARGET: generated screen17. Change ONLY the redundant double chevrons in the TOP COLLAPSED status row.
Current top row shows globe, “查了 3 个网页”, then a chevron immediately after text AND another at far trailing right. REMOVE the chevron immediately after text. Keep exactly ONE right-chevron glyph at the FAR RIGHT. Do not duplicate it. The row should visually read “查了 3 个网页 ›” with the single chevron as native trailing affordance.
Preserve EVERYTHING ELSE: exact853x1844 portrait dimensions; header Miffan and terracotta bowl, statusbar and gesturebar; time“今天21:03”; user“帮我整理一下今天的新闻”; one group-head bowl avatar; expanded ongoing row“正在查资料…” with down chevron and detail“正在核对最新消息”; normal answer“我先把目前找到的重点整理给你：”“城市出行：留意交通变化。”“生活资讯：关注身边的新消息。”; microphone, “发消息…”,plus composer. Keep neutral cool-white/pale-blue native theme and Chinese readable.
No added content or redesigned hierarchy, no vendor logos, no model names/tokens, no progress bars. Bowl face and rice intact, no body/hands/tears. Only delete that one redundant chevron.
```

</details>

<details>
<summary>18-thread-permission-card.webp · 权限确认卡片</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 18-thread-permission-card:
Header back/bowl/Miffan; centered“今天21:03”. Right user exact“帮我查一下今天的新闻”. Left group one bowl avatar beside a rounded permission CARD.
Card heading“打开伙伴能力”, generic globe/neutral search icon. Main body exact“我需要打开‘查查网上的新消息’才能帮你”.
Card friendly helper“这是伙伴的能力开关，允许后会开启联网搜索。”.
Two clearly separate equal controls INSIDE CARD: primary blue“允许”, secondary outlined“这次不用”. Optional small capability row“查查网上的新消息” and OFF toggle (disabled illustration) can be shown, but not necessary. Main actions both visible and no automatic enable before consent. No provider name or raw permission/API strings. No actual reply pretending news retrieval happened. Composer mic/“发消息…”/plus at bottom, no quote strip in this single topic, no tabs.
```

</details>

<details>
<summary>19-thread-error.webp · 出错与重试</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 19-thread-error:
Header back/Classic bowl/Miffan; time separator“今天21:03”. Right user“帮我想个轻松的晚餐吧”. Under it left partner position shows SMALL soft gray-blue notice bubble exact“网络有点卡，没收到回复” and a clearly outlined compact button“重试” inside or immediately beneath bubble.
Mascot next to notice is NEUTRAL Classic terracotta bowl with golden rice and two upright oval eyes and tiny round DOT mouth. A SMALL BLUE circular exclamation badge“!” anchored near upper-right rim, separate from eyes/face. Neutral positive still, NEVER downturned mouth, crying face, tears, sad eyes, sobbing expression, sweat drop or broken bowl. Badge is the sole error cue besides readable text. No red dramatic full-screen warning or error codes.
Older one-line exchange can be above, but no finished reply for failed message. Single topic immediate reply NO citation. Composer microphone/“发消息…”/plus, statusbar/gesturebar. Retry action very clear, no progress bar or technical data.
```

</details>

<details>
<summary>20-thread-memory-notice.webp · 记忆系统提示</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 20-thread-memory-notice:
Header back/bowl/Miffan; divider“今天21:03”. Right user EXACT“我养了一只叫团子的橘猫”. Directly BELOW this user message centered GRAY SYSTEM NOTICE outside chat bubbles, exact“Miffan 记住了：养了一只叫团子的橘猫 · 查看 · 撤销”. Render readable2-3 lines; keep “查看” and “撤销” distinct blue text links with dot separators; avoid splitting “团子” or words if possible.
Next left partner with one neutral happy bowl avatar and reply“记住啦，团子一定很可爱。” NO quote (single topic adjacent reply), no repeated memory text in reply. Causal sequence user fact → system notice with review/undo → partner reply. Nothing adds vendor/model detail.
Composer mic/“发消息…”/plus. Quiet system feedback separate from partner voice. Keep all strings full and exact, no fake letters.
```

</details>

<details>
<summary>21-thread-quote-jump.webp · 点击引用后定位</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 21-thread-quote-jump:
Header back/bowl/Miffan. Timeline SCROLLED BACK to original message being quoted, not at latest. Older exchange top right“今天想做点小事”; left one bowl“可以，慢慢来。” no citation for this adjacent single-line exchange.
Center divider“今天21:03”. A user GROUP with ONE user avatar at first only, TWO closely grouped right bubbles: “明天杭州会下雨吗”, “帮我想个猫的名字”. Preserve chronology from screen02.
The SECOND original cat question is temporarily HIGHLIGHTED with a noticeably brighter lavender/sky-blue rounded background and subtle blue border/halo; tiny label“原消息” allowed. Do not highlight a reply instead. This is the scroll target from tapping a cat quote.
Below a left partner weather reply with own blue quote“↩ 明天杭州会下雨吗” and body“明天可能有雨，出门记得带伞。”; then right user“番茄炒蛋先放哪个” begins at lower viewport edge; all later cooking/cat replies remain offscreen. Do not show an out-of-order extra cooking question above weather reply.
A floating compact pill near lower center above composer EXACT“回到最新 ↓”, white/light-blue shadow surface, down arrow visibly part of button. Must not be hidden behind composer. Bottom mic/“发消息…”/plus fixed. No global topic filter or reply-preview composer (this is jump, not filter). Only original user cat bubble highlighted.
```

</details>

<details>
<summary>22-thread-voice.webp · 按住说话录音态</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 22-thread-voice:
Header back/bowl/Miffan; divider“今天21:03”. Short single-topic conversation right“我想聊聊今天的事”; left one bowl“我在，慢慢说。” with no quote. Lots of room for recording state at bottom.
Composer has CHANGED to active PRESS-TO-TALK. Above input area a LARGE visible blue live audio WAVEFORM consisting smooth varying-height vertical sound bars, with bold TIMER exact“0:04”. Clear instruction exact“松开发送 · 上滑取消”. Large rounded active hold button at bottom labeled exact“按住说话” with microphone icon, glowing subtle blue held-state outline (not neon), no hand/finger illustration. Small upward chevron is allowed for slide-up cancel cue.
Do NOT show ordinary “发消息…” typing field simultaneously with recording. Do not add send or stop buttons that replace release-to-send behavior. Plus button can remain small at right outside hold area. No progress bar, no model or technical audio settings. Statusbar and gesturebar complete. Recording controls high contrast and full-size native touch target.
```

</details>

<details>
<summary>23-thread-attach-panel.webp · 附件面板</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 23-thread-attach-panel:
Header back/bowl/Miffan; time“今天21:03”. Short single-topic exchange right“我想给你看看这份材料”; left one bowl“发给我吧，我们一起看看。” no quote.
Near lower screen composer row microphone, “发消息…”, plus SELECTED blue round button. DIRECTLY BELOW composer an EXPANDED IM ATTACHMENT PANEL, shared pale gray-blue surface with top separator and EXACTLY THREE equal icon-grid cells in one horizontal row: generic image-outline icon +“照片”; generic camera-outline icon +“拍照”; generic document-outline icon +“文件”. All icons comfortable48dp targets, labels16sp. No fourth option, no locations, contacts, drawing, tools, app logos, recently used thumbnails or extra attachment categories. Panel belongs to input, not detached floating card. Gesturebar below panel, no bottom tabs. No keyboard simultaneously.
```

</details>

<details>
<summary>24-search.webp · 搜索结果</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 24-search:
Search screen header back chevron and rounded active search field query EXACT“团子” with magnifying glass and clear X. Results GROUPED. No partner NAME matches “团子”, so DO NOT render any “伙伴” section, placeholder or “无匹配” row.
Show section heading“聊天记录”, optional small“3条”. Exactly THREE flat separated result rows, each small recognizable bowl avatar, partner name, highlighted matching snippet and date:
1 Classic bowl “Miffan”; snippet“我养了一只叫团子的橘猫”; date“10月3日”.
2 green sprout bowl “抹茶”; snippet“团子这个名字很适合它。”; date“10月4日”.
3 terracotta bowl “写作搭子”; snippet“可以写一个团子的冒险故事。”; date“10月5日”.
In EACH snippet ONLY substring“团子” highlighted with readable pale-yellow or blue background and dark text (not the entire row), full context visible. Dates right aligned, partner names bold and source rows tappable with discreet chevron. No separate result cards, no vendor logos. No bottom tabs on search detail, no keyboard needed (results ready). All3snippets and identities readable.
```

</details>

<details>
<summary>25-thread-empty.webp · 第一次与写作搭子聊天</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 25-thread-empty:
New empty-thread screen header back chevron, small Classic rice bowl avatar and name EXACT“写作搭子”. NO existing message history, timestamps or reply bubbles.
Centered in empty timeline a LARGE friendly Classic terracotta bowl greeting (golden rice mound, face ON bowl, gentle round cheerful smile, two oval eyes). No waving hands or arms.
Directly underneath one concise introduction EXACT“我是写作搭子，陪你把想法写成文字。”.
Then THREE separately tappable suggestion-topic bubbles/chips, full-size rounded pale-blue with subtle send-arrow glyph, labeled EXACT“帮我写一段开场白”, “把这句话说得更自然”, “一起想三个故事点子”. Vertical stack to fit comfortably; tapping suggestion directly sends it. Small helper“点一个话题，就能开始聊” allowed. These are suggestions, not already sent messages, distinguish centered placement from right user chat bubbles.
Normal bottom composer mic/“发消息…”/plus and gesturebar. No “新对话” or new-chat FAB, no onboarding service selector, no bottom nav, no vendor/model labels. Single approachable first-chat screen.
```

</details>

## 完整稿

第一批为 01～15。本轮 01、03、06、07、09、11、12 已覆盖重画（见上方第二轮修正表）；02、14 保持并行引用规则无需改动，15 按要求保留首轮主题版。配色仅承载主题，不作为本轮 UX 评审项。所有图片由 **imagegen 技能的内置 image_gen 工具**生成或转换，未使用 CLI 回退。

### 逐张画面与交互要点

| 文件 | 画面 | 体现的交互要点 | 提示词要点 |
| --- | --- | --- | --- |
| [01-chats-tab.webp](01-chats-tab.webp) | 消息 Tab 修正（第二轮重画） | 平铺六行、置顶/未读/输入状态；左滑置顶与不显示；说明保留记录、新消息重新出现。 | 替换删除动作及图标；隐藏语义说明；四 Tab。 |
| [02-thread-parallel.webp](02-thread-parallel.webp) | 并行话题时间线 | 同一分钟三问题、回复交错；四个发送者分组，组首头像；三色引用；语音与加号。 | 四组六气泡；最后两条伙伴回复共用组首头像；仅时间分隔条。 |
| [03-thread-topic-filter.webp](03-thread-topic-filter.webp) | 只看话题修正（第二轮重画） | 猫名筛选与关闭；隐藏其他话题；去掉回复条；占位明确自动归入本话题。 | 仅猫名消息；组首头像；精确占位，无引用叠加。 |
| [04-thread-long-press.webp](04-thread-long-press.webp) | 长按消息菜单 | 选中最后一条伙伴回复；弹出回复、复制、只看这个话题、重新生成、更多五项菜单。 | 长按高亮与遮罩；菜单锚定消息；五项动作完整且可读。 |
| [05-thread-self-config.webp](05-thread-self-config.webp) | 一句话改设定 | 用户自然语言改设定；居中系统提示与查看/撤销；后续回复简短、无表情。 | 逐字用户请求与系统提示；系统反馈不作为伙伴气泡；动作清晰。 |
| [06-settings-history.webp](06-settings-history.webp) | 设定历史修正（第二轮重画） | 三来源人话总结；展开后人话总结/原文差异切换；真正提示词红删绿增；触发跳转与旧版恢复。 | 人话摘要保留在上；选中原文差异；提示词文本而非标签差异。 |
| [07-memory.webp](07-memory.webp) | 记忆列表修正（第二轮重画） | 卡片改平铺分隔行；每行由 Miffan 记下及日期；来源可跳转、可删除；历史入口。 | 精确顶部说明；三条同来源身份；禁用独立卡片。 |
| [08-partners-tab.webp](08-partners-tab.webp) | 伙伴 Tab | 六位不同角色伙伴含鲸鱼头；四类推荐模板，每卡添加；伙伴 Tab 选中。 | 我的伙伴网格与推荐模板分区；四按钮不遗漏。 |
| [09-partner-profile.webp](09-partner-profile.webp) | 伙伴资料卡修正（第二轮重画） | 发消息主按钮；名字旁编辑名字/头像/性格；原记忆/能力/历史/背景保留；底部清空和二次确认删除入口。 | 缩小头像腾出操作空间；主次清晰；删除小红字。 |
| [10-discover-tab.webp](10-discover-tab.webp) | 发现 Tab | 画图、翻译、收藏、文件与工作区、扩展五入口；发现 Tab 选中。 | 五卡可点击箭头；友好摘要；无技术仪表盘。 |
| [11-me-tab.webp](11-me-tab.webp) | 我页修正（第二轮重画） | 中性 AI 服务连接图标；新增备份恢复/通知/语言/关于；高级保留专业模式切换；个人资料与四 Tab。 | 紧凑平铺设置行；无厂商商标；高级展开。 |
| [12-onboarding.webp](12-onboarding.webp) | 连接引导修正（第二轮重画） | 直接列纯文本服务选项；一键登录优先；API Key 选项；已选中一项才启用继续。 | 无商标的中性图标；单选态；继续按钮可用的因果。 |
| [13-mode-choice.webp](13-mode-choice.webp) | 老用户模式选择 | 升级弹窗；左右轻松聊天（新）/专业模式预览与选择；我→高级可切换；继续按钮。 | 双列选择卡、IM 与专业结构缩略预览；专业预览仍隐藏禁用信息。 |
| [14-thread-dark.webp](14-thread-dark.webp) | 并行时间线深色版 | 02 相同内容与分组；深色可读；三色引用和组首头像规则一致。 | 仅切换深色表面与文字；六气泡/四组与02相同。 |
| [15-chats-whale.webp](15-chats-whale.webp) | 蓝色大肥鱼主题消息页（首轮保留） | 首轮六伙伴、平铺分隔行、输入状态与导航；保留旧置顶/删除左滑示意。 | 本轮按要求不重画；当前列表动作参照01置顶/不显示。 |

### IM 约定与检查

- **平铺列表**：当前 01 和 07 共用页面表面，以分隔线组织行；01 的海盐行演示左滑“置顶／不显示”，并说明记录保留和新消息恢复。15 保留首轮主题示意，本轮不重画。
- **消息分组与引用**：02、14 的六条消息分四组，同组仅组首有头像，时间由分隔条表达；并行批次的三条回复各有引用条。03、16～23 的单线相邻回复不加引用，03 的输入自动归入筛选话题。
- **手势与点击**：04 保留完整长按菜单；21 引用跳转后高亮原消息并提供回到最新；伙伴资料主操作是发消息，编辑入口、来源跳转、删除、恢复、添加和模式切换清晰可发现。
- **状态与因果**：05、20 分别显示设定和记忆系统通知；06 默认人话总结，展开可切换原文差异；07 明确伙伴记录身份；18 先确认能力，19 可重试，22 录音手势说明可见。
- **角色与禁用项**：保留碗、脸和内容物；鲸鱼女孩仅头部；16 吃饭、19 中性加提示。屏幕无厂商商标图形、模型名、token、温度、会话抽屉、新对话按钮或分支切换器。
- **视觉核对**：每张按当前适用的必须体现和评审修正检查。02、14、15 未改；本轮七张覆盖与十张新增均已检查。

### 重生成记录与已知瑕疵

- **05 已重生成**：首版为 934×1685，画布偏宽；弃用首版，重新生成并采用 853×1844 的版本。用户请求、系统通知、“查看／撤销”和简短回复均齐全。
- **03 重生成历史**：第一轮曾修正滑动反馈与引用叠加的静态状态；第二轮按新规则进一步去掉输入区引用条，使用话题占位文字。当前成品以第二轮话题输入语义为准。
- **04 已重生成**：首版背景多补了一次猫名提问，已弃用；修正版以 02 为底稿，保留同一六条消息顺序和组首头像，将完整五项菜单置于选中回复上方。菜单避让使选中气泡略向下移，不影响消息顺序。
- **05 的通知换行**：“少用表情”被分到两行，仍可完整阅读；最终原生排版宜将这一短语保持在同一行。
- **画布尺寸记录**：第一轮 12、13 为 830×1896；12 已在第二轮覆盖为 853×1844，当前仅保留的 13 仍略长。本轮全部成品为 853×1844。生成图未拉伸或裁切，最终原生实现统一设备与字体规格。
- **13 的微缩预览**：选项标题、选择态、切换说明和按钮清楚；预览内的小字是缩略示意，不作为可直接复制的最终排版。
- **首轮 01、15 的海盐头像左缘裁切**原用于左滑示意，属于主动交互状态。01 已在第二轮重画为置顶／不显示；15 保留首轮裁切与旧动作，仅作为主题示意。

未发现关键交互控件缺失或关键中文文案错乱。上述细节已保留供设计评审；本轮未修改代码、未提交 Git。

### 完整稿实际生成提示词

第一轮基础视觉参考先通过 view_image 载入 [01-chats-tab-a.webp](01-chats-tab-a.webp) 与 [02-thread-parallel-a.webp](02-thread-parallel-a.webp)。七张覆盖图的提示词已更新为第二轮版本；其余记录沿用第一轮。新稿 01、02 通过检查后作为后续页面的补充参考；14、15 的本地底稿也再次通过 view_image 查看后进行主题转换。以下记录的是最终采用版本的实际提示词。

<details>
<summary>01-chats-tab.webp · 消息 Tab 修正（第二轮重画）</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 01-chats-tab:
Title “消息” and search “搜索伙伴或消息”. SIX flat full-width rows, separators, NO row cards. Rows:
Miffan Classic bowl / “先炒鸡蛋，再炒番茄。” /21:03 / pin “置顶” / unread3.
抹茶 green sprout bowl / “正在输入…” /21:02 / unread1 / tiny typing dots.
樱花 pink bowl / “团子这个名字很适合它。” /20:48.
月光 purple star-rice bowl / “今晚读到哪一页了？” /20:16.
海盐 blue bowl / “周末一起去散步吧。” /18:32 / unread2.
蓝色大肥鱼 head-only avatar / “今天也要好好吃饭呀。” /昨天.
Row海盐 LEFT-SWIPED, content modestly slid left, two trailing full-height RECTANGULAR exposed actions: “置顶” (blue pin) and “不显示” (neutral gray eye-slash). NEVER “删除” and NEVER trash icon in this screen. Other five rows normal and readable. A quiet footer explains “不显示会保留聊天记录，有新消息时重新出现”. Bottom tabs消息selected/伙伴/发现/我. Preserve list density and avatars. No independent cards.
```

</details>

<details>
<summary>02-thread-parallel.webp · 并行话题时间线</summary>

```text
Use case: ui-mockup.
Deliver ONE polished high-fidelity complete Android mobile app screen, portrait ratio 9:19.5 approx 1080x2340. Flat edge-to-edge screenshot without phone frame, perspective, watermark, presentation heading or surrounding margins. Status bar at 21:03 with signal/wifi/battery and bottom gesture bar.
Input references: image 1 = direction-a message list for neutral theme, typography and avatar identities ONLY; image 2 = direction-a thread for palette and bubble treatment ONLY. Correct the old UX where instructed, do not copy reference's individual row cards or repeated avatars.
Unified direction-a neutral theme: cool near-white #F6F9FC background, pale sky blue #D5F0FC user bubbles/selected states, pale gray-blue #E8EFF3 partner bubbles, readable charcoal text, quiet blue accents; system Chinese sans-serif, comfortable native 16sp body and 28sp page title, rounded line icons, restrained 20-24dp corners, very light shadows. Clean practical IM hierarchy, reasonable list density, ample but not excessive whitespace.
All interface copy Simplified Chinese, exact requested strings readable and correctly spelled; existing name Miffan and Arabic numerals allowed. Render quoted text verbatim, without fake illegible characters.
Miffan identity: small squat open rounded terracotta rice bowl, distinct elliptical rim, golden/cream rice mound above rim, two VERTICAL OVAL eyes and small mouth drawn ON BOWL. All alternate bowl colors retain BOWL, FACE and readable CONTENTS. No humanoid mascot, body, arms, hands or legs. Blue whale girl if requested is ONLY the blue-haired smiling HEAD with round juvenile face, white frilled headband, lateral whale fins and ONE side bow; no neck, body, arms or hands. Mascots are partner identity and meaningful welcoming/status expression, not background illustrations.
IM conventions (hard constraints): conversation lists are FLAT FULL-WIDTH ROWS sharing one page surface, thin horizontal separator lines aligned after avatar; NO independent rounded cards per conversation row, NO gaps between rows. Other feature cards only when specified. Consecutive messages from same sender form a GROUP: show avatar ONLY alongside first bubble of each group, next bubbles align under first WITHOUT any repeated avatar. Time only via divider or once per group, NEVER next to each bubble. Clicking row opens chat; long press opens ordinary IM menu; left swipe reply on thread; left swipe pin/delete on conversation list. Never show message-branch selector.
Avoid everywhere: model names, token numbers, temperature parameters, raw tools, AI dashboard, “新对话” button, conversation-list drawer/hamburger, technical conversation IDs, branch controls. AI provider ICONS permitted solely where screen 11 requests them; never model labels.
Keep all required actions legible, comfortably touchable, entirely on-screen, no clipping. Header back chevron allowed on detail pages. Only main Tab pages 01/08/10/11/15 have fixed bottom navigation “消息 / 伙伴 / 发现 / 我”, active page clear; chat/detail/onboarding screens do not have that nav.
Screen 02-thread-parallel:
Top appbar: back chevron, Classic bowl avatar and “Miffan” ONLY, no subtitle or extra status text. Center time divider “今天 21:03”.
EXACT chronological six bubbles in FOUR sender groups. This sequence is essential to show concurrent topics and interleaved responses:
GROUP 1 USER right: two closely spaced pale-blue bubbles “明天杭州会下雨吗” then “帮我想个猫的名字”. If user avatar is used, one tiny round “我” avatar only at first bubble, no second avatar. No individual timestamps.
GROUP 2 PARTNER left: ONE Classic bowl avatar at group head; one gray-blue bubble with top blue quote strip “↩ 明天杭州会下雨吗”, body “明天可能有雨，出门记得带伞。”.
GROUP 3 USER right: one pale-blue bubble “番茄炒蛋先放哪个”; one optional user avatar at group head.
GROUP 4 PARTNER left: TWO distinct but tightly grouped gray-blue bubbles. Only ONE Classic bowl avatar alongside FIRST reply. First reply has peach quote strip “↩ 番茄炒蛋先放哪个” and body “先炒鸡蛋，盛出来。再炒番茄，最后合在一起。”.
Second reply immediately under first, same left alignment, NO AVATAR beside this second reply. Lavender quote strip “↩ 帮我想个猫的名字”, body “叫「团子」怎么样？软乎乎的，很适合小猫。”.
Three questions all within this same minute. Quote strips INSIDE corresponding reply at TOP; blue/weather, peach/cooking, lavender/cat; text full no truncation. Only one time divider; no repeated time labels.
Bottom composer: round microphone button, rounded text field “发消息…”, round circled plus. No bottom tabs. All six bubbles fit without clipping. Clear 6-8dp inside-group spacing and larger 16-20dp between groups. Do not repeat bowl avatar next to the LAST cat reply. No topic tabs or section headers.
```

</details>

<details>
<summary>03-thread-topic-filter.webp · 只看话题修正（第二轮重画）</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 03-thread-topic-filter:
Header back/bowl/“Miffan”. Filter capsule exact “只看：猫的名字 ✕”, center divider “今天21:03”. Show ONLY cat topic: right“帮我想个猫的名字”; left bowl and“叫「团子」怎么样？软乎乎的，很适合小猫。”; right“再来两个名字”; left group with ONE bowl at first of TWO bubbles: “奶盖，适合白白软软的小猫。” and “芝麻，适合小黑猫。” second no avatar.
No citation strips in these single-topic adjacent replies. NO active reply preview or “回复 Miffan” bar anywhere. Composer consists microphone, wider text field with EXACT placeholder “在“猫的名字”里说点什么…” and plus. Placeholder may wrap into two lines inside field at readable16sp, do not truncate. No weather/cooking content, no exposed swipe action, no timestamps per bubble, no bottom tabs. Top filter X clear. Messages sent here implicitly stay in cat topic.
```

</details>

<details>
<summary>04-thread-long-press.webp · 长按消息菜单</summary>

```text
Use case: precise-object-edit.
Asset type: high-fidelity Android screenshot 04-thread-long-press.
Input image1 EDIT TARGET: final corrected 02-thread-parallel. Preserve the EXACT six-message timeline, all wording, order, grouping, avatar counts, original layout, header, time divider and composer.
Show the context menu after LONG-PRESSING the LAST cat-name partner reply. Keep this selected final bubble bright, with subtle blue selection outline, same lavender quote “↩ 帮我想个猫的名字”, exact body “叫「团子」怎么样？软乎乎的，很适合小猫。”. Dim the rest of the screen with a translucent gray overlay. Last cat reply is SECOND bubble in its partner group: it MUST NOT gain a new avatar. Bowl avatar for this group remains beside prior cooking reply ONLY.
Render a normal white softly rounded IM context menu anchored immediately ABOVE the selected last cat bubble. The menu may overlay the dimmed preceding bubbles, which is expected. This placement is critical so all actions fit. Five equal vertical icon-and-text actions EXACTLY “回复”, “复制”, “只看这个话题”, “重新生成”, “更多”. Crisp Chinese, all five clearly visible, 44-48dp tap height each, rounded line icons. Popup bright above dim backdrop. No hand, no fingers, no emoji reaction toolbar.
Never add or duplicate any timeline messages: exactly TWO user messages first, one weather reply, one cooking user question, final TWO partner replies cooking and cat. Keep grouped avatar policy. No new “帮我想个猫的名字” bubble in backdrop.
Keep phone portrait aspect exactly as reference853x1844. Header only partner bowl+Miffan with back arrow, one time divider “今天 21:03”, bottom microphone/“发消息…”/plus visible dimmed. No tabs, model names, tokens, parameters, new-chat button, drawer or branch UI. Change only selection, dimming and context menu; do not redesign or rewrite the timeline.
```

</details>

<details>
<summary>05-thread-self-config.webp · 一句话改设定</summary>

```text
Use case: ui-mockup.
Deliver ONE polished high-fidelity complete Android mobile app screen, portrait ratio 9:19.5 approx 1080x2340. Flat edge-to-edge screenshot without phone frame, perspective, watermark, presentation heading or surrounding margins. Status bar at 21:03 with signal/wifi/battery and bottom gesture bar.
Input references: image 1 = direction-a message list for neutral theme, typography and avatar identities ONLY; image 2 = direction-a thread for palette and bubble treatment ONLY. Correct the old UX where instructed, do not copy reference's individual row cards or repeated avatars.
Unified direction-a neutral theme: cool near-white #F6F9FC background, pale sky blue #D5F0FC user bubbles/selected states, pale gray-blue #E8EFF3 partner bubbles, readable charcoal text, quiet blue accents; system Chinese sans-serif, comfortable native 16sp body and 28sp page title, rounded line icons, restrained 20-24dp corners, very light shadows. Clean practical IM hierarchy, reasonable list density, ample but not excessive whitespace.
All interface copy Simplified Chinese, exact requested strings readable and correctly spelled; existing name Miffan and Arabic numerals allowed. Render quoted text verbatim, without fake illegible characters.
Miffan identity: small squat open rounded terracotta rice bowl, distinct elliptical rim, golden/cream rice mound above rim, two VERTICAL OVAL eyes and small mouth drawn ON BOWL. All alternate bowl colors retain BOWL, FACE and readable CONTENTS. No humanoid mascot, body, arms, hands or legs. Blue whale girl if requested is ONLY the blue-haired smiling HEAD with round juvenile face, white frilled headband, lateral whale fins and ONE side bow; no neck, body, arms or hands. Mascots are partner identity and meaningful welcoming/status expression, not background illustrations.
IM conventions (hard constraints): conversation lists are FLAT FULL-WIDTH ROWS sharing one page surface, thin horizontal separator lines aligned after avatar; NO independent rounded cards per conversation row, NO gaps between rows. Other feature cards only when specified. Consecutive messages from same sender form a GROUP: show avatar ONLY alongside first bubble of each group, next bubbles align under first WITHOUT any repeated avatar. Time only via divider or once per group, NEVER next to each bubble. Clicking row opens chat; long press opens ordinary IM menu; left swipe reply on thread; left swipe pin/delete on conversation list. Never show message-branch selector.
Avoid everywhere: model names, token numbers, temperature parameters, raw tools, AI dashboard, “新对话” button, conversation-list drawer/hamburger, technical conversation IDs, branch controls. AI provider ICONS permitted solely where screen 11 requests them; never model labels.
Keep all required actions legible, comfortably touchable, entirely on-screen, no clipping. Header back chevron allowed on detail pages. Only main Tab pages 01/08/10/11/15 have fixed bottom navigation “消息 / 伙伴 / 发现 / 我”, active page clear; chat/detail/onboarding screens do not have that nav.
Screen 05-thread-self-config:
Header back / bowl / “Miffan”; centered time separator “今天 21:03”. A short older left message group: bowl avatar only first bubble, two bubbles “今天过得怎么样？” then “想聊什么都可以。” with no second avatar. A right user bubble with exact sentence “以后回答简短一点，别用那么多表情”. Immediately beneath this user message a CENTERED GRAY SYSTEM NOTICE, not a partner bubble, readable over multiple lines with exact content “Miffan 调整了自己的设定：回答更简短、少用表情 · 查看 · 撤销”. “查看” and “撤销” appear as distinct small clickable blue text links within notice, with dot separators. Next a left partner group with one bowl avatar and ONE concise plain reply “好，以后简短说。” with NO emoji. Composer microphone/“发消息…”/plus at bottom. Clear causal sequence user request → system confirmation with review and undo → changed short reply. No parameters or settings dialog.
Additional reference image 3 is the updated grouped thread: maintain corrected grouping and clean native visual system.
CRITICAL canvas requirement: strict tall Android screen WIDTH 853 HEIGHT 1844 pixels (ratio 9:19.5), matching reference image 3 dimensions. Entire output must be this TALL silhouette, do not shorten canvas to fit sparse content. Preserve plenty of vertical blank space below the short conversation, keep composer and gesture bar at true bottom. This is a regenerate of screen 05, all user text and system notice exact.
```

</details>

<details>
<summary>06-settings-history.webp · 设定历史修正（第二轮重画）</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 06-settings-history:
Header back, “设定历史”, small bowl/Miffan identity. Vertical timeline THREE entries, each human-language SUMMARY before any original prompt text, source/date and tappable trigger quote.
Entry1 current “10月5日21:03” current badge; source“你在聊天中说的”; human summary“回答更简短，少用表情”; clickable trigger quote“以后回答简短一点，别用那么多表情” plus chevron.
Entry1 is expanded with a two-segment control “人话总结” / “原文差异”, select “原文差异”. Below show actual SYSTEM PROMPT TEXT diff, NOT short label diff: red minus row“− 你是 Miffan，回答应详细，可使用表情。”; green plus row“＋ 你是 Miffan，回答应简短，少用表情。”. These are complete prompt instructions, red removed and green added, symbols plus/minus both, Chinese readable. Keep human summary still visible above switch.
Entry2 “10月4日18:20”, source“你手动修改”, summary“称呼改为小陈”, trigger/source row“你在资料卡修改了称呼 ›”; outlined“恢复到此版本” button with helper“恢复会创建一条新记录”.
Entry3 “10月3日09:15”, source“恢复”, human summary“恢复为温和、轻快的回答风格”, tappable source summary“恢复了10月2日的设定 ›”.
All THREE records human summary, all sources clear; entry1 trigger chevron visibly returns to chat. One expanded entry only. No Git jargon, no model params, no bottom nav.
```

</details>

<details>
<summary>07-memory.webp · 记忆列表修正（第二轮重画）</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 07-memory:
Detail header back, “AI 对我的了解”, top-right “历史”. Exact helper“这些是伙伴从聊天中记下的，你可以随时删除”.
Exactly THREE FLAT LIST ROWS with inset horizontal separators, sharing one background, no individual rounded cards. Each row has bold memory title, smaller tappable source “由 Miffan 记下 · 10月3日” with chevron, separate trailing trash-outline+“删除” action. Titles: “喜欢吃辣”, “养了一只叫团子的橘猫”, “在杭州工作”. Use same small Classic bowl source-avatar or neutral memory icon, not three different partner identities; each explicitly Miffan. Source row tap opens corresponding chat; delete is distinct trailing action. Generous row height without giant tiles. No card outlines or separate rounded source panels. No bottom tabs. All three source strings exactly repeated.
```

</details>

<details>
<summary>08-partners-tab.webp · 伙伴 Tab</summary>

```text
Use case: ui-mockup.
Deliver ONE polished high-fidelity complete Android mobile app screen, portrait ratio 9:19.5 approx 1080x2340. Flat edge-to-edge screenshot without phone frame, perspective, watermark, presentation heading or surrounding margins. Status bar at 21:03 with signal/wifi/battery and bottom gesture bar.
Input references: image 1 = direction-a message list for neutral theme, typography and avatar identities ONLY; image 2 = direction-a thread for palette and bubble treatment ONLY. Correct the old UX where instructed, do not copy reference's individual row cards or repeated avatars.
Unified direction-a neutral theme: cool near-white #F6F9FC background, pale sky blue #D5F0FC user bubbles/selected states, pale gray-blue #E8EFF3 partner bubbles, readable charcoal text, quiet blue accents; system Chinese sans-serif, comfortable native 16sp body and 28sp page title, rounded line icons, restrained 20-24dp corners, very light shadows. Clean practical IM hierarchy, reasonable list density, ample but not excessive whitespace.
All interface copy Simplified Chinese, exact requested strings readable and correctly spelled; existing name Miffan and Arabic numerals allowed. Render quoted text verbatim, without fake illegible characters.
Miffan identity: small squat open rounded terracotta rice bowl, distinct elliptical rim, golden/cream rice mound above rim, two VERTICAL OVAL eyes and small mouth drawn ON BOWL. All alternate bowl colors retain BOWL, FACE and readable CONTENTS. No humanoid mascot, body, arms, hands or legs. Blue whale girl if requested is ONLY the blue-haired smiling HEAD with round juvenile face, white frilled headband, lateral whale fins and ONE side bow; no neck, body, arms or hands. Mascots are partner identity and meaningful welcoming/status expression, not background illustrations.
IM conventions (hard constraints): conversation lists are FLAT FULL-WIDTH ROWS sharing one page surface, thin horizontal separator lines aligned after avatar; NO independent rounded cards per conversation row, NO gaps between rows. Other feature cards only when specified. Consecutive messages from same sender form a GROUP: show avatar ONLY alongside first bubble of each group, next bubbles align under first WITHOUT any repeated avatar. Time only via divider or once per group, NEVER next to each bubble. Clicking row opens chat; long press opens ordinary IM menu; left swipe reply on thread; left swipe pin/delete on conversation list. Never show message-branch selector.
Avoid everywhere: model names, token numbers, temperature parameters, raw tools, AI dashboard, “新对话” button, conversation-list drawer/hamburger, technical conversation IDs, branch controls. AI provider ICONS permitted solely where screen 11 requests them; never model labels.
Keep all required actions legible, comfortably touchable, entirely on-screen, no clipping. Header back chevron allowed on detail pages. Only main Tab pages 01/08/10/11/15 have fixed bottom navigation “消息 / 伙伴 / 发现 / 我”, active page clear; chat/detail/onboarding screens do not have that nav.
Screen 08-partners-tab:
Main tab page title “伙伴”. Section “我的伙伴” with compact 2-by-3 grid of SIX partner cells, each avatar and name: terracotta bowl “Miffan”; matcha sprout bowl “抹茶”; sakura bowl “樱花”; purple star-rice bowl “月光”; blue bowl “海盐”; head-only whale girl “蓝色大肥鱼”. Bowl silhouette, face and contents all preserved at compact sizes. Quiet text action “添加伙伴” at section heading right allowed.
Second section title “推荐伙伴”. Four template cards in compact two-column grid, all four cards completely visible: “翻译官” / subtitle “轻松读懂另一种语言”; “写作搭子” / “一起把想法写清楚”; “陪聊” / “随时聊聊生活”; “学习教练” / “陪你拆解学习目标”. Each card has a small bowl avatar AND its own readable outlined button exact “添加”. No duplicates or missing cards. All four “添加” controls visible. Bottom nav exactly “消息 / 伙伴 / 发现 / 我”, “伙伴” selected. Practical native density fitting both sections without excessive blank space. No model labels.
Additional reference image 3 is the newly approved native screen 01. Match its aspect ratio, exact avatar identity and neutral style. Strict tall portrait 9:19.5.
```

</details>

<details>
<summary>09-partner-profile.webp · 伙伴资料卡修正（第二轮重画）</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 09-partner-profile:
Header back“伙伴资料”. Top centered Classic bowl avatar about112dp (not huge enough to crowd actions). Name row “Miffan” with pencil edit glyph and small text“编辑” directly next to name; beneath personality“温暖、好奇，陪你把日子聊明白。”; small helper next to edit “名字、头像、性格” shows edit scope.
PRIMARY wide rounded blue button exact “发消息” IMMEDIATELY UNDER avatar/name/personality block, clearly dominant.
Then tappable row“它记得关于你的12件事 ›”. Section“它能帮你”: switch row“查查网上的新消息” with subtitle“联网搜索”, ON; second“读你发来的文件” subtitle“读文件”, ON.
Below navigation rows“设定历史 ›”,“聊天背景 ›”.
At bottom WEAK second-level area separated by thin divider: gray“清空上下文” with small helper“让它从这里重新开始理解聊天”; red small text action“删除聊天记录” with helper“删除前会再次确认”. NOT a giant red button, no destructive modal open now. Editing entrance remains near name, primary发消息 above all settings. All elements fit in tall screen. No bottom tabs.
```

</details>

<details>
<summary>10-discover-tab.webp · 发现 Tab</summary>

```text
Use case: ui-mockup.
Deliver ONE polished high-fidelity complete Android mobile app screen, portrait ratio 9:19.5 approx 1080x2340. Flat edge-to-edge screenshot without phone frame, perspective, watermark, presentation heading or surrounding margins. Status bar at 21:03 with signal/wifi/battery and bottom gesture bar.
Input references: image 1 = direction-a message list for neutral theme, typography and avatar identities ONLY; image 2 = direction-a thread for palette and bubble treatment ONLY. Correct the old UX where instructed, do not copy reference's individual row cards or repeated avatars.
Unified direction-a neutral theme: cool near-white #F6F9FC background, pale sky blue #D5F0FC user bubbles/selected states, pale gray-blue #E8EFF3 partner bubbles, readable charcoal text, quiet blue accents; system Chinese sans-serif, comfortable native 16sp body and 28sp page title, rounded line icons, restrained 20-24dp corners, very light shadows. Clean practical IM hierarchy, reasonable list density, ample but not excessive whitespace.
All interface copy Simplified Chinese, exact requested strings readable and correctly spelled; existing name Miffan and Arabic numerals allowed. Render quoted text verbatim, without fake illegible characters.
Miffan identity: small squat open rounded terracotta rice bowl, distinct elliptical rim, golden/cream rice mound above rim, two VERTICAL OVAL eyes and small mouth drawn ON BOWL. All alternate bowl colors retain BOWL, FACE and readable CONTENTS. No humanoid mascot, body, arms, hands or legs. Blue whale girl if requested is ONLY the blue-haired smiling HEAD with round juvenile face, white frilled headband, lateral whale fins and ONE side bow; no neck, body, arms or hands. Mascots are partner identity and meaningful welcoming/status expression, not background illustrations.
IM conventions (hard constraints): conversation lists are FLAT FULL-WIDTH ROWS sharing one page surface, thin horizontal separator lines aligned after avatar; NO independent rounded cards per conversation row, NO gaps between rows. Other feature cards only when specified. Consecutive messages from same sender form a GROUP: show avatar ONLY alongside first bubble of each group, next bubbles align under first WITHOUT any repeated avatar. Time only via divider or once per group, NEVER next to each bubble. Clicking row opens chat; long press opens ordinary IM menu; left swipe reply on thread; left swipe pin/delete on conversation list. Never show message-branch selector.
Avoid everywhere: model names, token numbers, temperature parameters, raw tools, AI dashboard, “新对话” button, conversation-list drawer/hamburger, technical conversation IDs, branch controls. AI provider ICONS permitted solely where screen 11 requests them; never model labels.
Keep all required actions legible, comfortably touchable, entirely on-screen, no clipping. Header back chevron allowed on detail pages. Only main Tab pages 01/08/10/11/15 have fixed bottom navigation “消息 / 伙伴 / 发现 / 我”, active page clear; chat/detail/onboarding screens do not have that nav.
Screen 10-discover-tab:
Main tab title “发现”. EXACTLY FIVE large tappable entry cards, each rounded-line icon, bold title, short friendly summary and trailing chevron: “画图” / “把脑海里的画面画出来”; “翻译” / “读懂世界，也表达自己”; “收藏” / “留住喜欢的消息”; “文件与工作区” / “整理文件，一起完成事情”; “扩展” / “为伙伴添一点新本领”. These feature cards explicitly allowed; substantial accessible tap targets, no sixth invented feature. Neutral direction-a with small quiet icons, no giant illustrations. Bottom nav “消息 / 伙伴 / 发现 / 我”, “发现” selected. All five cards visible and gesture bar below nav.
Additional reference image 3 is the approved flat native main-tab screen 01; keep matching title scale, 9:19.5 portrait canvas and four-tab navigation design.
```

</details>

<details>
<summary>11-me-tab.webp · 我页修正（第二轮重画）</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 11-me-tab:
Main title“我”. Compact profile avatar circular“陈”, nickname“小陈”. Below compact flat grouped setting rows with separators and generic neutral rounded line icons:
“AI 对我的了解 ›”
“AI 服务” neutral LINK icon, small green dot“已连接”, chevron. ABSOLUTELY NO vendor logo, no knot, no G, no branded symbols.
“外观与角色 ›”
“备份与恢复 ›”
“通知 ›”
“语言” trailing“简体中文 ›”
“关于 ›”
Expanded section“高级⌄” and nested row“切换到专业模式 ›”, smaller helper“更多专业设置与工具”.
All seven setting entrances +Advanced+mode row fit ON SCREEN with readable16sp, use realistic55dp row heights and compact100dp profile block, avoid excessive whitespace. Bottom4tabs消息/伙伴/发现/我 selected. No manufacturer marks ANYWHERE.
```

</details>

<details>
<summary>12-onboarding.webp · 连接引导修正（第二轮重画）</summary>

```text
Use case: ui-mockup.
Output ONE complete polished native Android UI screenshot, strict tall portrait width853 height1844 approx9:19.5. Include status bar21:03 with signal/wifi/battery and bottom gesture bar. Edge-to-edge flat screen, no handset mockup, border, external caption, perspective or watermark.
Input image1 reference=approved02 grouped chat; image2 reference=old09 profile for typography, palette and BOWL identity only. Reference screens are STYLE references; new corrected content below takes precedence.
Neutral Material direction-a: near-white cool#F6F9FC background, pale sky-blue#D5F0FC user bubbles/selected state, pale gray-blue#E8EFF3 partner bubbles, charcoal legible type, quiet blue accents. Chinese SYSTEM SANS SERIF, practical 16sp body, rounded line icons, restrained round controls, light shadows. Keep all required Chinese text EXACT, full and correctly spelled; existing Miffan name allowed. Provider text names and “API Key” allowed ONLY on onboarding12, NEVER a provider logo.
Bowl species invariant: squat rounded open terracotta rice BOWL, visible elliptical rim and cream-gold rice above rim, TWO VERTICAL OVAL EYES and small rounded mouth ON BOWL. Alternate color bowls keep bowl/face/readable contents. No body, arms, hands, legs, humanoid or tear marks. Whale girl ONLY if specified is cheerful HEAD ONLY with blue hair, white frilled headband, side whale fins, ONE side bow; no neck/body/hands.
Latest IM rules: flat conversation and memory list rows with separators, not individual cards. Consecutive same-sender messages group with only ONE avatar at group head, no avatar repeats per bubble. Time only divider or once per group. PARALLEL reply batch: every reply gets its own TOP citation strip, even if next to its question; SINGLE-topic adjacent reply: NO citation. In topic filter, composer automatically routes to filtered topic and NEVER has reply preview. Conversation left-swipe actions are “置顶” and “不显示”; hidden chat history retained and row returns on new messages. Record deletion is ONLY profile action with confirmation. Profile primary action “发消息”.
Hard avoid EVERYWHERE: vendor trademarks/logos/brand symbols, model names, token statistics, temperature parameters, progress bars, raw tool function names/code, “新对话”, conversation drawer/hamburger, branch selectors, random extra controls. Use generic neutral glyphs, never OpenAI knot/Google G/other brands. Do not imitate old vendor-logo screen.
All required elements entirely on screen and legible, native IM hierarchy. Chat appbar only back chevron, compact bowl and partner name; no model subtitle. No bottom tabs on chat/detail/onboarding. Main pages01/11 alone here show exactly four bottom tabs “消息 / 伙伴 / 发现 / 我”.
SCREEN 12-onboarding:
Onboarding first step of3: three dots first selected and“1 / 3”. Friendly big Classic bowl greeting with smile, face on bowl and golden rice, no body/hands. Headline“连接 AI 服务”, concise sentence“只需连接一次，就能和伙伴一直聊下去。”.
DIRECT list of selectable provider options as TEXT names with IDENTICAL NEUTRAL link icons (no brand logo):
row1 “OpenAI” with badge“ 一键登录 ”, selected radio/circle check and subtle blue selected-row tint. This one supports account authorization and is first.
row2 “Google” subtitle“使用 API Key”, unselected radio.
row3 “其他服务” subtitle“使用 API Key”, unselected radio.
Pure text names allowed here, NO vendor graphics/trademarks, NO models. Radio select makes selection obvious; this is state AFTER choosing OpenAI so main bottom button exact “继续” is ENABLED blue. Small below-options note“选择一项后即可继续”; small bottom helper“下一步选择伙伴”. Do NOT have default action “连接并继续”; do NOT introduce a generic “选择你的AI服务” detour card hiding options. Full direct options and button fit with approachable spacing. No keyboard/API-value entry yet, no bottom tabs.
```

</details>

<details>
<summary>13-mode-choice.webp · 老用户模式选择</summary>

```text
Use case: ui-mockup.
Deliver ONE polished high-fidelity complete Android mobile app screen, portrait ratio 9:19.5 approx 1080x2340. Flat edge-to-edge screenshot without phone frame, perspective, watermark, presentation heading or surrounding margins. Status bar at 21:03 with signal/wifi/battery and bottom gesture bar.
Input references: image 1 = direction-a message list for neutral theme, typography and avatar identities ONLY; image 2 = direction-a thread for palette and bubble treatment ONLY. Correct the old UX where instructed, do not copy reference's individual row cards or repeated avatars.
Unified direction-a neutral theme: cool near-white #F6F9FC background, pale sky blue #D5F0FC user bubbles/selected states, pale gray-blue #E8EFF3 partner bubbles, readable charcoal text, quiet blue accents; system Chinese sans-serif, comfortable native 16sp body and 28sp page title, rounded line icons, restrained 20-24dp corners, very light shadows. Clean practical IM hierarchy, reasonable list density, ample but not excessive whitespace.
All interface copy Simplified Chinese, exact requested strings readable and correctly spelled; existing name Miffan and Arabic numerals allowed. Render quoted text verbatim, without fake illegible characters.
Miffan identity: small squat open rounded terracotta rice bowl, distinct elliptical rim, golden/cream rice mound above rim, two VERTICAL OVAL eyes and small mouth drawn ON BOWL. All alternate bowl colors retain BOWL, FACE and readable CONTENTS. No humanoid mascot, body, arms, hands or legs. Blue whale girl if requested is ONLY the blue-haired smiling HEAD with round juvenile face, white frilled headband, lateral whale fins and ONE side bow; no neck, body, arms or hands. Mascots are partner identity and meaningful welcoming/status expression, not background illustrations.
IM conventions (hard constraints): conversation lists are FLAT FULL-WIDTH ROWS sharing one page surface, thin horizontal separator lines aligned after avatar; NO independent rounded cards per conversation row, NO gaps between rows. Other feature cards only when specified. Consecutive messages from same sender form a GROUP: show avatar ONLY alongside first bubble of each group, next bubbles align under first WITHOUT any repeated avatar. Time only via divider or once per group, NEVER next to each bubble. Clicking row opens chat; long press opens ordinary IM menu; left swipe reply on thread; left swipe pin/delete on conversation list. Never show message-branch selector.
Avoid everywhere: model names, token numbers, temperature parameters, raw tools, AI dashboard, “新对话” button, conversation-list drawer/hamburger, technical conversation IDs, branch controls. AI provider ICONS permitted solely where screen 11 requests them; never model labels.
Keep all required actions legible, comfortably touchable, entirely on-screen, no clipping. Header back chevron allowed on detail pages. Only main Tab pages 01/08/10/11/15 have fixed bottom navigation “消息 / 伙伴 / 发现 / 我”, active page clear; chat/detail/onboarding screens do not have that nav.
Screen 13-mode-choice:
Show upgraded-user mode selection as a large rounded centered MODAL over subtly dimmed app background (no technical labels in backdrop). Title “欢迎回来”, subtitle “选一种你喜欢的聊天方式”.
Inside modal two equal side-by-side selectable choice cards with MINIATURE UI PREVIEWS. LEFT title “轻松聊天” with small “新” badge (together read “轻松聊天（新）”), selected blue outline and radio; preview is actual IM flat divided six-row partner list with small bowl avatars and bottom four-tab bar; friendly grouped speech bubbles also acceptable only inside preview. RIGHT title “专业模式”, unselected radio; preview is existing professional chat structure with back icon, heading “聊天”, assistant reply and collapsed panel text “工具步骤”, bottom composer; NO model names, token stats, temperature, branch selector, drawer or “新对话” anywhere even inside miniature preview.
Below options in modal exact note “随时可以在 我 → 高级 中切换”. Large modal primary button “继续”. Left selected choice clear; right independently tappable; previews clearly different information structures; no comparison slogans or feature pricing. Keep Chinese titles and note readable even previews small. Statusbar and gesture bar present outside modal. Two options left/right, not stacked.
Additional reference image 3 is the corrected flat IM list, use it for matching native neutral styling and for miniature IM preview if requested. Strict 9:19.5 portrait canvas (853x1844).
```

</details>

<details>
<summary>14-thread-dark.webp · 并行时间线深色版</summary>

```text
Use case: style-transfer.
Asset type: high-fidelity Android UI complete screenshot, 14-thread-dark.
Input image 1: EDIT TARGET, approved complete 02-thread-parallel with CORRECT sender grouping. Transform ONLY theme colors to native dark theme. Preserve phone aspect ratio, exact content, order, positions, grouping, avatar count, quote strips, all labels and input controls. No new or removed components. Same canvas silhouette, status bar and gesture bar.
Dark palette: navy-charcoal #141B22 background, muted deep blue #254E65 user bubbles, slate #26323D partner bubbles; warm near-white high-contrast readable text; quiet bright-blue accents; quote backgrounds deep blue (weather), dark muted terracotta (cooking), muted purple (cat), lighter text in each. Keep the bowl terracotta with golden rice and face clearly readable; preserve bowl contour, rice mound, two vertical oval eyes and small mouth. Positive face, no arms/hands/body.
Exact original content and order: title Miffan and bowl, time “今天 21:03”; user group TWO bubbles “明天杭州会下雨吗” and “帮我想个猫的名字” with user avatar ONLY beside first; partner weather reply with one bowl avatar and quote “↩ 明天杭州会下雨吗”, body “明天可能有雨，出门记得带伞。”; user “番茄炒蛋先放哪个” with new group-head user avatar; final partner group TWO bubbles with only ONE bowl avatar next to the first: cooking quote “↩ 番茄炒蛋先放哪个”, body “先炒鸡蛋，盛出来。再炒番茄，最后合在一起。”; cat quote “↩ 帮我想个猫的名字”, body “叫「团子」怎么样？软乎乎的，很适合小猫。”.
NO repeated avatar next to final cat reply, no repeated timestamps. Composer microphone, “发消息…”, plus. All original Chinese exact and legible, no rewriting. No model names, tokens, parameters, branch switch, drawer, “新对话”, watermarks. No gradients, neon or illumination effects. This is screen 02 converted to dark mode, not a redesign.
```

</details>

<details>
<summary>15-chats-whale.webp · 蓝色大肥鱼主题消息页</summary>

```text
Use case: style-transfer.
Asset type: high-fidelity Android UI complete screenshot, 15-chats-whale.
Input image 1: EDIT TARGET approved corrected 01-chats-tab, flat divided six-row IM list with a LEFT-SWIPED row. Change ONLY page theme to light blue whale theme. Keep same 9:19.5 tall mobile silhouette, exact content, row density, positions, search, flat row/divider geometry, statusbar, bottom navigation, gesture bar, left-swipe action state, pinned row, typing state and unread badges.
Blue whale theme: near-white cool blue #F2F8FF background, cool blue #2478BB accents, restrained pale blue active surfaces, dark navy readable text, no dramatic reskin. Head-only blue whale girl in sixth row remains ONLY a smiling juvenile HEAD, blue hair, white frilled headband, lateral whale fins, ONE side bow, NO neck, torso, arms or hands. Bowl avatars keep silhouette, face and visible rice above rim, no body.
Exact six rows and content preserved: “Miffan” / “先炒鸡蛋，再炒番茄。” / “21:03” / “置顶” / unread “3”; “抹茶” / “正在输入…” / “21:02” / unread “1”; “樱花” / “团子这个名字很适合它。” / “20:48”; “月光” / “今晚读到哪一页了？” / “20:16”; “海盐” / “周末一起去散步吧。” / “18:32” / unread “2” plus visible swipe buttons “置顶” and “删除”; “蓝色大肥鱼” / “今天也要好好吃饭呀。” / “昨天”.
Title “消息”, search “搜索伙伴或消息”, quiet footer “左滑可置顶或删除”; bottom tabs “消息 / 伙伴 / 发现 / 我”, “消息” selected.
No independent rounded cards per conversation row. No added whale illustration, no new rows, no card gaps, no model names/tokens/parameters, new chat button, drawer or branch controls. All text exact. Preserve cropped leading edge of swiped row as intentional interaction state. This is screen 01 blue whale THEME, not a redesign.
```

</details>

## 风格探索归档

以下六张 a/b/c 文件保留不覆盖。用户已评审：三个方向的 UX 均达标，配色归入用户自定义主题。下方方向说明与原始提示词作为第一轮探索记录。

## 三个方向

**a · 清爽 Material You**：冷白背景、浅天蓝用户气泡、灰蓝伙伴气泡和轻量选中态最接近现有应用，依靠留白与文字层级组织信息。适合以现有 Material Design 3 主题和组件为基础延续体验，作为日常长时间聊天的安静默认方向。

**b · 温暖手作纸感**：米白细纸纹、燕麦色伙伴气泡、浅鼠尾草绿用户气泡、赤陶色重点和略带陶瓷质感的碗头像形成温暖触感；仍保持系统无衬线字和克制图标。适合强调陪伴、生活聊天和 Miffan 食物角色的亲近感，纸纹只作为风格参考，不应影响正文阅读。

**c · 圆润高对比糖果感**：浅薰衣草背景、深墨紫文字、紫色强调、水绿色用户气泡与白色伙伴气泡构成更鲜明的层级；轮廓线和更圆的控件提高头像、未读和引用关系的辨识度。适合希望角色更活泼、界面更有记忆点的主题，同时将浓色限制在小面积交互重点。

## 成品与提示词要点

| 文件 | 方向 | 画面与提示词要点 |
| --- | --- | --- |
| [01-chats-tab-a.webp](01-chats-tab-a.webp) | a · 清爽 Material You | 冷白浅蓝、无明显描边的柔和列表；“消息”标题与搜索，六位伙伴，首行置顶、未读和“正在输入…”，四个底部 Tab。 |
| [02-thread-parallel-a.webp](02-thread-parallel-a.webp) | a · 清爽 Material You | 天蓝用户气泡、灰蓝伙伴气泡；六条消息交错出现，天气蓝／做饭桃色／猫名淡紫引用条；头像与名字、时间分隔、语音与加号输入栏。 |
| [01-chats-tab-b.webp](01-chats-tab-b.webp) | b · 温暖手作纸感 | 米白细纸纹、燕麦卡片、赤陶强调和克制陶瓷质感头像；与 a 使用相同六位伙伴、置顶、预览、时间、未读和导航。 |
| [02-thread-parallel-b.webp](02-thread-parallel-b.webp) | b · 温暖手作纸感 | 纸感背景、鼠尾草绿用户气泡、燕麦伙伴气泡；同一交错时间线，柔和三色引用条；不加入剪贴装饰。 |
| [01-chats-tab-c.webp](01-chats-tab-c.webp) | c · 圆润高对比糖果感 | 浅紫背景、深墨紫文字、紫色未读与选中态、浅色头像圆底和细轮廓；六位伙伴和四个 Tab 保持同一内容。 |
| [02-thread-parallel-c.webp](02-thread-parallel-c.webp) | c · 圆润高对比糖果感 | 水绿用户气泡、白色伙伴气泡、紫色细轮廓、三色引用条与强调竖线；同一交错内容，圆润输入栏。 |

## 共同约束与内容

- 画布为约 9:19.5 的 Android 竖屏，包含状态栏与手势条；单屏、平面截图，无机身、外部标题或水印。
- 简体中文界面，系统无衬线字体，圆角线性图标。保留 Miffan 专名。
- 碗头像保留开放碗轮廓、碗身两只竖椭圆眼和小嘴、碗沿上方可读的米饭／内容物。无身体、手、胳膊或腿。
- 六位伙伴为 Miffan、抹茶、樱花、月光、海盐、蓝色大肥鱼。鲸鱼女孩只保留蓝发、鲸鱼鳍、白色头饰与单侧蝴蝶结的头部，不复制参考图中的身体、手或饭碗。
- 不出现模型名、服务商标签、token、温度等参数、“新对话”按钮、会话列表抽屉、消息分支切换器或原始工具调用面板。
- 01：标题“消息”、搜索、六行头像／名字／预览／时间，Miffan 置顶，抹茶“正在输入…”，未读角标，以及“消息 / 伙伴 / 发现 / 我”导航。
- 02：顶部伙伴头像与名字；“今天 21:03”；三个问题在同一分钟内发送。顺序为：用户问天气 → 用户问猫名 → 伙伴回复天气 → 用户问做饭 → 伙伴回复做饭 → 伙伴回复猫名。三条回复顶部各有对应问题的引用条，蓝／桃／淡紫区分话题；底部有语音、输入框和加号。

已先通过 view_image 查看四张输入参考：

- [miffan-empty-chat.png](../../img/miffan-empty-chat.png)：现有留白、冷浅色和 Classic 碗形。
- [miffan-tool-call.png](../../img/miffan-tool-call.png)：气泡和文字层级；技术信息不进入本轮生成稿。
- [miffan-character-settings.png](../../img/miffan-character-settings.png)：碗、脸、内容物、各伙伴配色。
- [miffan-whale-girl.png](../../assets/branding/miffan-whale-girl.png)：仅参考蓝色头部特征，以设计说明的无身体／无手约束为准。

时间线生成额外使用同方向的 01 成品作为第五张风格参考，使两张图的主题、字号层级和头像保持一致。已逐张检查页面完整性、中文内容、角色识别和禁用元素。下方保留实际提示词，供后续复用；图片是风格参考，最终实现仍以现有主题 token、Material Design 3 和原生角色渲染为准。

## 实际生成提示词

<details>
<summary>01-chats-tab-a.webp · 清爽 Material You</summary>

```text
Use case: ui-mockup
Asset type: Miffan Android IM app high-fidelity style exploration, a SINGLE complete mobile screen.
Composition: portrait canvas approx 9:19.5 (e.g. 1080x2340), flat edge-to-edge screenshot, no handset frame, no perspective, no external margins, no caption or style label outside UI. Include Android status bar time 21:03, signal/Wi-Fi/battery icons and bottom gesture bar. Beautiful restrained IM, generous whitespace, large soft corners, light shadows, system sans-serif Chinese typography, rounded line icons. All interface copy Simplified Chinese; only the existing name Miffan may use Latin letters. Precise, legible, verbatim typography, no gibberish or extra labels.
Input images: images 1-2 reference current airy color and typography only; image 3 character identity reference; image 4 whale girl's BLUE HEAD FEATURES ONLY. These are reference images, not edit targets. NEVER copy the model labels or technical controls of screenshots.
Character invariants: Every Miffan avatar is a squat rounded open rice BOWL, recognizable elliptical rim, visible golden/cream rice mound above rim, two small vertical oval eyes and a small mouth ON THE BOWL. Classic terracotta orange bowl as primary; optional matcha green, sakura pink, moonlight purple and sea-salt blue bowls. No humanoid body, arms, hands, legs or animal appendages. Avatars communicate partner identity/state, not large decorative illustrations. Whale girl if included is ONLY a cheerful round HEAD, blue hair, white frilled headband, lateral whale fins and ONE side bow; no neck, torso, hands, arms, bowl or portrait rectangular tile. Image 4 body and hands must be discarded.
Avoid completely: model names, provider labels, token counts, temperatures/settings parameters, AI tool dashboards, raw tool calls, “新对话” button, conversation-list drawer or hamburger button, message branch selectors, floating compose/new-chat action, extra tabs, decorative mascots in background, full-body character illustrations, watermark. Do not add unrelated UI.
Direction A — clean Material You: closest to current native visual language. Near-white cool background #F6F9FC, sky blue #D5F0FC user bubbles and active tab/search accents, blue-grey #E8EFF3 partner bubbles, dark charcoal readable text, discreet blue unread circles. Smooth FLAT vector-like UI surfaces, no textures or outlines around rows, 24dp rounded bubbles, thin dividers, precise spacious grid. Bowl avatars simple flat ceramic silhouettes like reference 3. Calm, fresh, quietly polished.
Screen 01-chats-tab: “消息” large title at top, followed by a search field with magnifying glass and text “搜索伙伴或消息”.
Exactly six well-spaced partner rows with avatars, bold partner name, last-message preview, right-aligned time and discreet unread badges. Use these rows in this order:
1. classic terracotta bowl avatar; name “Miffan”; preview “先炒鸡蛋，再炒番茄。”; time “21:03”; tiny pin icon + “置顶”; unread badge “3”. First row subtly emphasized as pinned.
2. matcha bowl with rice and small sprout; name “抹茶”; preview “正在输入…” with a subtle three-dot state beside the bowl; time “21:02”; unread badge “1”.
3. sakura pink bowl with rice; name “樱花”; preview “团子这个名字很适合它。”; time “20:48”.
4. moonlight purple bowl with visible star-shaped rice; name “月光”; preview “今晚读到哪一页了？”; time “20:16”.
5. sea-salt blue bowl with rice; name “海盐”; preview “周末一起去散步吧。”; time “18:32”; unread badge “2”.
6. head-only whale girl avatar; name “蓝色大肥鱼”; preview “今天也要好好吃饭呀。”; time “昨天”.
Comfortable moderate IM list density and quiet blank space below list. Bottom fixed navigation exactly four equal icon + text tabs: “消息” selected, “伙伴”, “发现”, “我”. Clear selected state, small rounded active indicator. Keep gesture bar below bottom nav.
```

</details>

<details>
<summary>02-thread-parallel-a.webp · 清爽 Material You</summary>

```text
Use case: ui-mockup
Asset type: Miffan Android IM app high-fidelity style exploration, a SINGLE complete mobile screen.
Composition: portrait canvas approx 9:19.5 (e.g. 1080x2340), flat edge-to-edge screenshot, no handset frame, no perspective, no external margins, no caption or style label outside UI. Include Android status bar time 21:03, signal/Wi-Fi/battery icons and bottom gesture bar. Beautiful restrained IM, generous whitespace, large soft corners, light shadows, system sans-serif Chinese typography, rounded line icons. All interface copy Simplified Chinese; only the existing name Miffan may use Latin letters. Precise, legible, verbatim typography, no gibberish or extra labels.
Input images: images 1-2 reference current airy color and typography only; image 3 character identity reference; image 4 whale girl's BLUE HEAD FEATURES ONLY. These are reference images, not edit targets. NEVER copy the model labels or technical controls of screenshots.
Character invariants: Every Miffan avatar is a squat rounded open rice BOWL, recognizable elliptical rim, visible golden/cream rice mound above rim, two small vertical oval eyes and a small mouth ON THE BOWL. Classic terracotta orange bowl as primary; optional matcha green, sakura pink, moonlight purple and sea-salt blue bowls. No humanoid body, arms, hands, legs or animal appendages. Avatars communicate partner identity/state, not large decorative illustrations. Whale girl if included is ONLY a cheerful round HEAD, blue hair, white frilled headband, lateral whale fins and ONE side bow; no neck, torso, hands, arms, bowl or portrait rectangular tile. Image 4 body and hands must be discarded.
Avoid completely: model names, provider labels, token counts, temperatures/settings parameters, AI tool dashboards, raw tool calls, “新对话” button, conversation-list drawer or hamburger button, message branch selectors, floating compose/new-chat action, extra tabs, decorative mascots in background, full-body character illustrations, watermark. Do not add unrelated UI.
Direction A — clean Material You: closest to current native visual language. Near-white cool background #F6F9FC, sky blue #D5F0FC user bubbles and active tab/search accents, blue-grey #E8EFF3 partner bubbles, dark charcoal readable text, discreet blue unread circles. Smooth FLAT vector-like UI surfaces, no textures or outlines around rows, 24dp rounded bubbles, thin dividers, precise spacious grid. Bowl avatars simple flat ceramic silhouettes like reference 3. Calm, fresh, quietly polished.
Additional reference image 5 is the approved matching direction-A chats tab: preserve palette, typography, bowl identity, rounding and visual density; build the different thread screen described below.
Screen 02-thread-parallel: top app header has a plain back chevron, compact classic terracotta Miffan bowl avatar and the partner name “Miffan” ONLY as title; no subtitle, no model labels, no technical menu. Under header a centered light time separator “今天 21:03”.
Render EXACTLY six chat bubbles in this chronological order, every line legible:
1. Right user bubble “明天杭州会下雨吗” with small time “21:03”.
2. Right user bubble “帮我想个猫的名字” with small time “21:03”.
3. Left partner bubble with small classic Miffan bowl avatar, a BLUE tinted quote strip at its TOP containing “↩ 明天杭州会下雨吗”, then reply “明天可能有雨，出门记得带伞。”.
4. Right user bubble “番茄炒蛋先放哪个” with small time “21:03”.
5. Left partner bubble with small classic Miffan bowl avatar, a PEACH tinted quote strip at its TOP containing “↩ 番茄炒蛋先放哪个”, then reply “先炒鸡蛋，盛出来。再炒番茄，最后合在一起。”.
6. Left partner bubble with small classic Miffan bowl avatar, a LAVENDER tinted quote strip at its TOP containing “↩ 帮我想个猫的名字”, then reply “叫「团子」怎么样？软乎乎的，很适合小猫。”.
This ordering intentionally shows three unrelated questions asked within one minute and replies INTERLEAVING with user questions, completing out of question order. Keep three quote strips attached INSIDE their replies, topic colors distinct but quiet, quote text fully spelled without truncation. User bubbles right, partner bubbles left, no visible segment boundaries or topic panels, no branch switches. Bottom fixed composer with rounded voice button at left, text field placeholder “发消息…”, circled plus button at right, bottom gesture bar. No bottom tabs on thread. Fit all six bubbles without clipping, generous but efficient vertical spacing.
```

</details>

<details>
<summary>01-chats-tab-b.webp · 温暖手作纸感</summary>

```text
Use case: ui-mockup
Asset type: Miffan Android IM app high-fidelity style exploration, a SINGLE complete mobile screen.
Composition: portrait canvas approx 9:19.5 (e.g. 1080x2340), flat edge-to-edge screenshot, no handset frame, no perspective, no external margins, no caption or style label outside UI. Include Android status bar time 21:03, signal/Wi-Fi/battery icons and bottom gesture bar. Beautiful restrained IM, generous whitespace, large soft corners, light shadows, system sans-serif Chinese typography, rounded line icons. All interface copy Simplified Chinese; only the existing name Miffan may use Latin letters. Precise, legible, verbatim typography, no gibberish or extra labels.
Input images: images 1-2 reference current airy color and typography only; image 3 character identity reference; image 4 whale girl's BLUE HEAD FEATURES ONLY. These are reference images, not edit targets. NEVER copy the model labels or technical controls of screenshots.
Character invariants: Every Miffan avatar is a squat rounded open rice BOWL, recognizable elliptical rim, visible golden/cream rice mound above rim, two small vertical oval eyes and a small mouth ON THE BOWL. Classic terracotta orange bowl as primary; optional matcha green, sakura pink, moonlight purple and sea-salt blue bowls. No humanoid body, arms, hands, legs or animal appendages. Avatars communicate partner identity/state, not large decorative illustrations. Whale girl if included is ONLY a cheerful round HEAD, blue hair, white frilled headband, lateral whale fins and ONE side bow; no neck, torso, hands, arms, bowl or portrait rectangular tile. Image 4 body and hands must be discarded.
Avoid completely: model names, provider labels, token counts, temperatures/settings parameters, AI tool dashboards, raw tool calls, “新对话” button, conversation-list drawer or hamburger button, message branch selectors, floating compose/new-chat action, extra tabs, decorative mascots in background, full-body character illustrations, watermark. Do not add unrelated UI.
Direction B — warm handmade paper: visibly different from A. Ivory warm background #FAF5EB with exceptionally fine paper grain, oat cream cards/partner bubbles #EEE5D6, warm sage user bubbles #DDE6D3, terracotta #A9563E accents and unread badges, deep warm brown text. Subtle soft organic card corners, hand-crafted ceramic bowl shading with restrained grain, very fine warm pencil-like separators, tiny soft shadows. Typography remains crisp SYSTEM SANS SERIF, icons remain rounded LINE icons. Quiet and cozy, no torn paper, no scrapbook decorations, no ornamental flowers. Topic strips stay BLUE / PEACH / LAVENDER in subdued paper tints.
Screen 01-chats-tab: “消息” large title at top, followed by a search field with magnifying glass and text “搜索伙伴或消息”.
Exactly six well-spaced partner rows with avatars, bold partner name, last-message preview, right-aligned time and discreet unread badges. Use these rows in this order:
1. classic terracotta bowl avatar; name “Miffan”; preview “先炒鸡蛋，再炒番茄。”; time “21:03”; tiny pin icon + “置顶”; unread badge “3”. First row subtly emphasized as pinned.
2. matcha bowl with rice and small sprout; name “抹茶”; preview “正在输入…” with a subtle three-dot state beside the bowl; time “21:02”; unread badge “1”.
3. sakura pink bowl with rice; name “樱花”; preview “团子这个名字很适合它。”; time “20:48”.
4. moonlight purple bowl with visible star-shaped rice; name “月光”; preview “今晚读到哪一页了？”; time “20:16”.
5. sea-salt blue bowl with rice; name “海盐”; preview “周末一起去散步吧。”; time “18:32”; unread badge “2”.
6. head-only whale girl avatar; name “蓝色大肥鱼”; preview “今天也要好好吃饭呀。”; time “昨天”.
Comfortable moderate IM list density and quiet blank space below list. Bottom fixed navigation exactly four equal icon + text tabs: “消息” selected, “伙伴”, “发现”, “我”. Clear selected state, small rounded active indicator. Keep gesture bar below bottom nav.
```

</details>

<details>
<summary>02-thread-parallel-b.webp · 温暖手作纸感</summary>

```text
Use case: ui-mockup
Asset type: Miffan Android IM app high-fidelity style exploration, a SINGLE complete mobile screen.
Composition: portrait canvas approx 9:19.5 (e.g. 1080x2340), flat edge-to-edge screenshot, no handset frame, no perspective, no external margins, no caption or style label outside UI. Include Android status bar time 21:03, signal/Wi-Fi/battery icons and bottom gesture bar. Beautiful restrained IM, generous whitespace, large soft corners, light shadows, system sans-serif Chinese typography, rounded line icons. All interface copy Simplified Chinese; only the existing name Miffan may use Latin letters. Precise, legible, verbatim typography, no gibberish or extra labels.
Input images: images 1-2 reference current airy color and typography only; image 3 character identity reference; image 4 whale girl's BLUE HEAD FEATURES ONLY. These are reference images, not edit targets. NEVER copy the model labels or technical controls of screenshots.
Character invariants: Every Miffan avatar is a squat rounded open rice BOWL, recognizable elliptical rim, visible golden/cream rice mound above rim, two small vertical oval eyes and a small mouth ON THE BOWL. Classic terracotta orange bowl as primary; optional matcha green, sakura pink, moonlight purple and sea-salt blue bowls. No humanoid body, arms, hands, legs or animal appendages. Avatars communicate partner identity/state, not large decorative illustrations. Whale girl if included is ONLY a cheerful round HEAD, blue hair, white frilled headband, lateral whale fins and ONE side bow; no neck, torso, hands, arms, bowl or portrait rectangular tile. Image 4 body and hands must be discarded.
Avoid completely: model names, provider labels, token counts, temperatures/settings parameters, AI tool dashboards, raw tool calls, “新对话” button, conversation-list drawer or hamburger button, message branch selectors, floating compose/new-chat action, extra tabs, decorative mascots in background, full-body character illustrations, watermark. Do not add unrelated UI.
Direction B — warm handmade paper: visibly different from A. Ivory warm background #FAF5EB with exceptionally fine paper grain, oat cream cards/partner bubbles #EEE5D6, warm sage user bubbles #DDE6D3, terracotta #A9563E accents and unread badges, deep warm brown text. Subtle soft organic card corners, hand-crafted ceramic bowl shading with restrained grain, very fine warm pencil-like separators, tiny soft shadows. Typography remains crisp SYSTEM SANS SERIF, icons remain rounded LINE icons. Quiet and cozy, no torn paper, no scrapbook decorations, no ornamental flowers. Topic strips stay BLUE / PEACH / LAVENDER in subdued paper tints.
Additional reference image 5 is the matching direction-b chats tab. Preserve its exact palette, typography, avatar identity, material, rounding and visual density, but construct the separate thread screen below.
Screen 02-thread-parallel: top app header has a plain back chevron, compact classic terracotta Miffan bowl avatar and the partner name “Miffan” ONLY as title; no subtitle, no model labels, no technical menu. Under header a centered light time separator “今天 21:03”.
Render EXACTLY six chat bubbles in this chronological order, every line legible:
1. Right user bubble “明天杭州会下雨吗” with small time “21:03”.
2. Right user bubble “帮我想个猫的名字” with small time “21:03”.
3. Left partner bubble with small classic Miffan bowl avatar, a BLUE tinted quote strip at its TOP containing “↩ 明天杭州会下雨吗”, then reply “明天可能有雨，出门记得带伞。”.
4. Right user bubble “番茄炒蛋先放哪个” with small time “21:03”.
5. Left partner bubble with small classic Miffan bowl avatar, a PEACH tinted quote strip at its TOP containing “↩ 番茄炒蛋先放哪个”, then reply “先炒鸡蛋，盛出来。再炒番茄，最后合在一起。”.
6. Left partner bubble with small classic Miffan bowl avatar, a LAVENDER tinted quote strip at its TOP containing “↩ 帮我想个猫的名字”, then reply “叫「团子」怎么样？软乎乎的，很适合小猫。”.
This ordering intentionally shows three unrelated questions asked within one minute and replies INTERLEAVING with user questions, completing out of question order. Keep three quote strips attached INSIDE their replies, topic colors distinct but quiet, quote text fully spelled without truncation. User bubbles right, partner bubbles left, no visible segment boundaries or topic panels, no branch switches. Bottom fixed composer with rounded voice button at left, text field placeholder “发消息…”, circled plus button at right, bottom gesture bar. No bottom tabs on thread. Fit all six bubbles without clipping, generous but efficient vertical spacing.
```

</details>

<details>
<summary>01-chats-tab-c.webp · 圆润高对比糖果感</summary>

```text
Use case: ui-mockup
Asset type: Miffan Android IM app high-fidelity style exploration, a SINGLE complete mobile screen.
Composition: portrait canvas approx 9:19.5 (e.g. 1080x2340), flat edge-to-edge screenshot, no handset frame, no perspective, no external margins, no caption or style label outside UI. Include Android status bar time 21:03, signal/Wi-Fi/battery icons and bottom gesture bar. Beautiful restrained IM, generous whitespace, large soft corners, light shadows, system sans-serif Chinese typography, rounded line icons. All interface copy Simplified Chinese; only the existing name Miffan may use Latin letters. Precise, legible, verbatim typography, no gibberish or extra labels.
Input images: images 1-2 reference current airy color and typography only; image 3 character identity reference; image 4 whale girl's BLUE HEAD FEATURES ONLY. These are reference images, not edit targets. NEVER copy the model labels or technical controls of screenshots.
Character invariants: Every Miffan avatar is a squat rounded open rice BOWL, recognizable elliptical rim, visible golden/cream rice mound above rim, two small vertical oval eyes and a small mouth ON THE BOWL. Classic terracotta orange bowl as primary; optional matcha green, sakura pink, moonlight purple and sea-salt blue bowls. No humanoid body, arms, hands, legs or animal appendages. Avatars communicate partner identity/state, not large decorative illustrations. Whale girl if included is ONLY a cheerful round HEAD, blue hair, white frilled headband, lateral whale fins and ONE side bow; no neck, torso, hands, arms, bowl or portrait rectangular tile. Image 4 body and hands must be discarded.
Avoid completely: model names, provider labels, token counts, temperatures/settings parameters, AI tool dashboards, raw tool calls, “新对话” button, conversation-list drawer or hamburger button, message branch selectors, floating compose/new-chat action, extra tabs, decorative mascots in background, full-body character illustrations, watermark. Do not add unrelated UI.
Direction C — rounded high-contrast candy: visibly different from A and B. Smooth pale lavender-white background #F7F4FF; bold dark ink text #282344; saturated violet #6550C7 selected-tab indicator and unread badges; cool aqua user bubbles #BCF0EC; partner bubbles nearly white #FFFFFF. Oversized ROUND pill corners, thin crisp muted-violet contour around bubbles/search/row cards, playful bold name weights, pastel lilac/peach/aqua avatar disks, charming flat bowl avatars with rich clearly legible colors. Bold color accents occupy small areas, light shadow only, no glossy 3D or neon gradients. Clear readable contrast while warm and uncluttered. Quote strips BLUE / PEACH / LAVENDER with stronger colored left rules. No oversized character artwork.
Screen 01-chats-tab: “消息” large title at top, followed by a search field with magnifying glass and text “搜索伙伴或消息”.
Exactly six well-spaced partner rows with avatars, bold partner name, last-message preview, right-aligned time and discreet unread badges. Use these rows in this order:
1. classic terracotta bowl avatar; name “Miffan”; preview “先炒鸡蛋，再炒番茄。”; time “21:03”; tiny pin icon + “置顶”; unread badge “3”. First row subtly emphasized as pinned.
2. matcha bowl with rice and small sprout; name “抹茶”; preview “正在输入…” with a subtle three-dot state beside the bowl; time “21:02”; unread badge “1”.
3. sakura pink bowl with rice; name “樱花”; preview “团子这个名字很适合它。”; time “20:48”.
4. moonlight purple bowl with visible star-shaped rice; name “月光”; preview “今晚读到哪一页了？”; time “20:16”.
5. sea-salt blue bowl with rice; name “海盐”; preview “周末一起去散步吧。”; time “18:32”; unread badge “2”.
6. head-only whale girl avatar; name “蓝色大肥鱼”; preview “今天也要好好吃饭呀。”; time “昨天”.
Comfortable moderate IM list density and quiet blank space below list. Bottom fixed navigation exactly four equal icon + text tabs: “消息” selected, “伙伴”, “发现”, “我”. Clear selected state, small rounded active indicator. Keep gesture bar below bottom nav.
```

</details>

<details>
<summary>02-thread-parallel-c.webp · 圆润高对比糖果感</summary>

```text
Use case: ui-mockup
Asset type: Miffan Android IM app high-fidelity style exploration, a SINGLE complete mobile screen.
Composition: portrait canvas approx 9:19.5 (e.g. 1080x2340), flat edge-to-edge screenshot, no handset frame, no perspective, no external margins, no caption or style label outside UI. Include Android status bar time 21:03, signal/Wi-Fi/battery icons and bottom gesture bar. Beautiful restrained IM, generous whitespace, large soft corners, light shadows, system sans-serif Chinese typography, rounded line icons. All interface copy Simplified Chinese; only the existing name Miffan may use Latin letters. Precise, legible, verbatim typography, no gibberish or extra labels.
Input images: images 1-2 reference current airy color and typography only; image 3 character identity reference; image 4 whale girl's BLUE HEAD FEATURES ONLY. These are reference images, not edit targets. NEVER copy the model labels or technical controls of screenshots.
Character invariants: Every Miffan avatar is a squat rounded open rice BOWL, recognizable elliptical rim, visible golden/cream rice mound above rim, two small vertical oval eyes and a small mouth ON THE BOWL. Classic terracotta orange bowl as primary; optional matcha green, sakura pink, moonlight purple and sea-salt blue bowls. No humanoid body, arms, hands, legs or animal appendages. Avatars communicate partner identity/state, not large decorative illustrations. Whale girl if included is ONLY a cheerful round HEAD, blue hair, white frilled headband, lateral whale fins and ONE side bow; no neck, torso, hands, arms, bowl or portrait rectangular tile. Image 4 body and hands must be discarded.
Avoid completely: model names, provider labels, token counts, temperatures/settings parameters, AI tool dashboards, raw tool calls, “新对话” button, conversation-list drawer or hamburger button, message branch selectors, floating compose/new-chat action, extra tabs, decorative mascots in background, full-body character illustrations, watermark. Do not add unrelated UI.
Direction C — rounded high-contrast candy: visibly different from A and B. Smooth pale lavender-white background #F7F4FF; bold dark ink text #282344; saturated violet #6550C7 selected-tab indicator and unread badges; cool aqua user bubbles #BCF0EC; partner bubbles nearly white #FFFFFF. Oversized ROUND pill corners, thin crisp muted-violet contour around bubbles/search/row cards, playful bold name weights, pastel lilac/peach/aqua avatar disks, charming flat bowl avatars with rich clearly legible colors. Bold color accents occupy small areas, light shadow only, no glossy 3D or neon gradients. Clear readable contrast while warm and uncluttered. Quote strips BLUE / PEACH / LAVENDER with stronger colored left rules. No oversized character artwork.
Additional reference image 5 is the matching direction-c chats tab. Preserve its exact palette, typography, avatar identity, material, rounding and visual density, but construct the separate thread screen below.
Screen 02-thread-parallel: top app header has a plain back chevron, compact classic terracotta Miffan bowl avatar and the partner name “Miffan” ONLY as title; no subtitle, no model labels, no technical menu. Under header a centered light time separator “今天 21:03”.
Render EXACTLY six chat bubbles in this chronological order, every line legible:
1. Right user bubble “明天杭州会下雨吗” with small time “21:03”.
2. Right user bubble “帮我想个猫的名字” with small time “21:03”.
3. Left partner bubble with small classic Miffan bowl avatar, a BLUE tinted quote strip at its TOP containing “↩ 明天杭州会下雨吗”, then reply “明天可能有雨，出门记得带伞。”.
4. Right user bubble “番茄炒蛋先放哪个” with small time “21:03”.
5. Left partner bubble with small classic Miffan bowl avatar, a PEACH tinted quote strip at its TOP containing “↩ 番茄炒蛋先放哪个”, then reply “先炒鸡蛋，盛出来。再炒番茄，最后合在一起。”.
6. Left partner bubble with small classic Miffan bowl avatar, a LAVENDER tinted quote strip at its TOP containing “↩ 帮我想个猫的名字”, then reply “叫「团子」怎么样？软乎乎的，很适合小猫。”.
This ordering intentionally shows three unrelated questions asked within one minute and replies INTERLEAVING with user questions, completing out of question order. Keep three quote strips attached INSIDE their replies, topic colors distinct but quiet, quote text fully spelled without truncation. User bubbles right, partner bubbles left, no visible segment boundaries or topic panels, no branch switches. Bottom fixed composer with rounded voice button at left, text field placeholder “发消息…”, circled plus button at right, bottom gesture bar. No bottom tabs on thread. Fit all six bubbles without clipping, generous but efficient vertical spacing.
```

</details>


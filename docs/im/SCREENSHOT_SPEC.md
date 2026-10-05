# Miffan 4.0 宣发截图拍摄说明

本说明定义 4.0 宣发与 README 使用的截图：画面、演示数据与台词、拍摄环境。内容（伙伴、台词、卖点）已经确定，执行时不要改写台词；如确有必要，在 `docs/im/SCREENSHOT_NOTES.md` 说明原因。

## 产出

- 10 个画面 × 中文 / 英文 × 浅色 / 深色 = 40 张，另加 1 张蓝色大肥鱼主题（画面 1，中文浅色）。
- 1080 × 2400 真机渲染截图，无损转 WebP，命名 `docs/img/v4/{zh|en}/{nn}-{slug}-{light|dark}.webp`，大肥鱼图为 `docs/img/v4/zh/01-chats-whale.webp`。
- 不使用任何真实个人数据、真实 API Key 或真实服务商账号。

## 拍摄环境

- 模拟器 `emulator-5556`（`RikkaHub_API_35_16K`），只用这一台。
- 状态栏演示模式：`adb shell settings put global sysui_demo_allowed 1`，时钟固定 21:03，满格信号与电量，无通知。拍完关闭演示模式。
- 使用 release 构建拍摄（不显示调试包的“[开发模式]”标记）：在 `/Users/chenxiansheng/miffan` 主仓库执行 `./gradlew :app:assembleRelease`（本机已有签名配置，不要读取或输出其中的密码），安装 `app/build/outputs/apk/release/` 下的 arm64 包，包名为 `me.ayuilos.miffan.app`。只在本地安装，不分发。不得以修改像素的方式去除任何界面元素。
- 语言：`adb shell cmd locale set-app-locales me.ayuilos.miffan.app --locales zh-CN`（英文为 `en-US`）。
- 深浅色：`adb shell cmd uimode night yes|no`；应用内颜色模式保持“跟随系统”，动态取色关闭，主题用默认预设。
- 模型回复来自仅监听本机的临时 OpenAI 兼容 fixture（可复用 P2/P5 的 `/tmp/p*-fixture-server.py` 思路），按下文台词返回：普通回复、话题分类 JSON（快速模型）、`update_my_preferences` 与 `memory_tool` 工具调用。fixture 不提交进仓库。

## 演示数据

伙伴（中文名 / 英文名，头像为对应配色的 Miffan）：

| 伙伴 | 英文 | 头像 |
| --- | --- | --- |
| Miffan | Miffan | Classic · Rice |
| 抹茶 | Matcha | Matcha · Sprout |
| 樱花 | Sakura | Sakura · Rice |
| 月光 | Moonlight | Moonlight · Stargazer |
| 海盐 | Sea Salt | Sea Salt · Rice |
| 蓝色大肥鱼 | Blue Whale | 蓝色大肥鱼角色 |

“我”的昵称：小陈 / Alex。

各伙伴最近一条（用于画面 1）：

| 伙伴 | 中文 | 英文 | 状态 |
| --- | --- | --- | --- |
| Miffan | 先炒鸡蛋，盛出来，再炒番茄。 | Eggs first, set them aside, then the tomatoes. | 置顶，3 条未读 |
| 抹茶 | （正在输入） | (typing) | 1 条未读 |
| 樱花 | 团子这个名字很适合它。 | "Tuanzi" really suits your cat. | |
| 月光 | 今晚读到哪一页了？ | Which page are you on tonight? | |
| 海盐 | 周末一起去散步吧。 | Let's take a walk this weekend. | 2 条未读 |
| 蓝色大肥鱼 | 今天也要好好吃饭呀。 | Remember to eat well today. | 昨天 |

## 画面

| # | slug | 画面 | 中文台词 | 英文台词 |
| --- | --- | --- | --- | --- |
| 01 | chats | 消息 Tab，按上表 | — | — |
| 02 | thread | 与 Miffan 的时间线：一个“昨天”时间标签下的一轮，再一个“今天 21:03”标签下的一轮，看得出跨天连续 | 昨天：我：“周末想做点简单的菜” → Miffan：“试试番茄炒蛋？十分钟就好。” 今天：我：“上次说的菜我做成功了！” → Miffan：“太好了！下次可以试试加一点点糖。” | Yesterday: "Something easy to cook this weekend?" → "Try tomato and eggs, ten minutes." Today: "I made the dish you suggested!" → "Great! Next time add a pinch of sugar." |
| 03 | parallel | 连发 3 个问题，回复按到达顺序交错且各带引用（与设计稿 02 一致） | 我：“明天杭州会下雨吗”“帮我想个猫的名字”“番茄炒蛋先放哪个”；回复：“明天可能有雨，出门记得带伞。”“先炒鸡蛋，盛出来，再炒番茄，最后合在一起。”“叫「团子」怎么样？软乎乎的，很适合小猫。”（到达顺序：雨、番茄、猫） | "Will it rain in Hangzhou tomorrow?" "Name ideas for my cat?" "Tomato and eggs, which first?"; replies "Probably, take an umbrella." "Eggs first, set them aside, then the tomatoes." "How about Tuanzi? Soft and round, just like a kitten." |
| 04 | topic | 画面 03 之后，长按“团子”回复选“只看这个话题”，再追问一句 | 我：“再来两个名字” → “奶盖，适合白白软软的小猫。”“芝麻，适合小黑猫。” | "Two more names?" → "Milk Foam, for a fluffy white cat." "Sesame, for a little black cat." |
| 05 | self-config | 一句话改设定：系统提示“Miffan 调整了自己的设定：回答更简短、少用表情 · 查看 · 撤销” | 我：“以后回答简短一点，别用那么多表情” → “好，以后简短说。” | "Keep your answers short from now on, fewer emojis." → "Got it, short answers from now on." |
| 06 | history | Miffan 的设定历史，展开最新一条显示“原文差异” | 由画面 05 产生 | 同左 |
| 07 | memory | AI 对我的了解：3 条记忆，各显示记录者与日期 | 由聊天产生：“喜欢吃辣”（Miffan）、“养了一只叫团子的橘猫”（樱花）、“在杭州工作”（Miffan） | "Likes spicy food", "Has an orange cat named Tuanzi", "Works in Hangzhou" |
| 08 | partners | 伙伴 Tab：我的伙伴网格 + 推荐伙伴（未添加状态） | — | — |
| 09 | onboarding | IM 引导第 1 步，选中 OpenRouter（未登录） | — | — |
| 10 | mode | 老用户升级后的模式选择弹窗（用一个模拟升级的安装状态拍摄：先装 3.x 调试包产生数据，再覆盖安装） | — | — |

## 验收

1. 41 张图齐全，命名正确，内容与台词一致，中文与英文界面分别对应。
2. 图中没有模型名、token、错误提示、开发模式标记、真实个人信息。
3. `docs/im/SCREENSHOT_NOTES.md` 记录拍摄环境、fixture 行为、每张图的生成步骤，以及任何偏差。

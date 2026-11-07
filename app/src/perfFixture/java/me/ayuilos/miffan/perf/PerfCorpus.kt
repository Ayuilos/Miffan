package me.ayuilos.miffan.perf

import kotlin.random.Random

/**
 * Hand-written Chinese and English chat text for the performance fixture. Everything is picked with a
 * seeded [Random], so the same seed always yields the same conversation.
 */
internal object PerfCorpus {
    val userShortZh = listOf(
        "早", "在吗", "帮我看一下这个", "好的", "收到", "谢谢", "哈哈哈", "然后呢", "等一下", "我再想想",
        "明天下午三点有空吗", "这个周末去哪玩比较好", "晚饭吃什么", "帮我记一下", "还有别的办法吗", "不太对",
        "再说详细一点", "能换个说法吗", "我刚到家", "今天好累", "有点冷", "刚开完会", "这个价格合理吗",
        "发给我吧", "可以", "行", "嗯嗯", "你觉得呢", "那就这样", "换一个", "继续", "太长了，简短点",
        "算了，下次再说", "我去洗个澡", "有个想法", "你先别动，等我回来", "帮我查一下", "哪个更便宜",
    )
    val userShortEn = listOf(
        "morning", "got a sec?", "can you check this", "ok", "thanks!", "lol", "and then?", "hold on",
        "let me think", "what's on tomorrow", "any other way?", "that's not right", "more detail please",
        "just got home", "so tired today", "send it over", "sure", "sounds good", "go on", "shorter pls",
        "never mind, later", "brb", "I have an idea", "wait for me", "look this up for me",
    )
    val assistantShortZh = listOf(
        "早呀，今天看起来是个好天气。", "在的，你说。", "好的，我来看看。", "收到啦。", "不客气～",
        "哈哈，确实。", "我觉得可以先从最小的一步开始。", "明天下午三点你的日历是空的，要我帮你占上吗？",
        "这个价格在同类里偏高一点，不过包含了售后。", "好，我记下了。", "要不要先喝口水再继续？",
        "没问题，慢慢来，我在这儿。", "我换个说法：先做能马上看到结果的部分，再处理难的。",
        "我理解你的意思了，你更看重省时间而不是省钱，对吗？", "周末天气不错，可以去城市公园走走，附近有一家新开的咖啡馆。",
        "已经帮你记下来了，下周一早上我会提醒你。", "稍等，我查一下。", "这件事不急，今晚好好休息吧。",
        "好呀，那我们明天再接着聊。", "嗯，这样确实更稳妥一些。", "刚才那个方案有个小问题：时间会有冲突。",
    )
    val assistantShortEn = listOf(
        "Morning! Looks like a nice day.", "I'm here — go ahead.", "Sure, let me take a look.", "Got it.",
        "Anytime!", "Haha, fair enough.", "I'd start with the smallest step that shows a result.",
        "Your calendar is free tomorrow at 3 pm. Want me to block it?", "Noted — I'll remind you Monday morning.",
        "Take your time, I'm not going anywhere.", "That plan has one small conflict: the timing overlaps.",
        "Sounds good, let's pick it up tomorrow.",
    )

    private val subjects = listOf(
        "周报", "旅行计划", "健身安排", "读书笔记", "预算表", "搬家清单", "面试准备", "Kotlin 协程", "Compose 性能",
        "SQLite 索引", "家庭菜谱", "睡眠记录", "日语学习", "相机选购", "摄影构图", "咖啡冲煮", "城市骑行路线",
        "项目复盘", "技术分享提纲", "季度目标",
    )
    private val subjectsEn = listOf(
        "weekly report", "trip plan", "workout schedule", "reading notes", "budget sheet", "moving checklist",
        "interview prep", "Kotlin coroutines", "Compose performance", "SQLite indexes", "family recipes",
        "sleep log", "Japanese study", "camera shopping", "photo composition", "pour-over coffee", "cycling routes",
        "project retro", "tech talk outline", "quarterly goals",
    )

    fun topicTitle(index: Int): String = if (index % 3 == 2) subjectsEn[index % subjectsEn.size].replaceFirstChar { it.uppercase() }
    else subjects[index % subjects.size]

    fun topicSummary(index: Int): String = if (index % 3 == 2)
        "Talked through the ${subjectsEn[index % subjectsEn.size]}: agreed on next steps and a rough timeline; open question is the order of the first two tasks."
    else "聊了「${subjects[index % subjects.size]}」：确定了下一步的做法和大致时间，还剩第一、第二件事谁先做没有定。"

    fun userLine(random: Random): String = when (random.nextInt(10)) {
        in 0..5 -> userShortZh.random(random)
        in 6..7 -> userShortEn.random(random)
        8 -> "${subjects.random(random)}那个，${userShortZh.random(random)}"
        else -> "能不能帮我整理一下${subjects.random(random)}，要点列出来就行，别太长。我晚点要用。"
    }

    fun assistantLine(random: Random): String =
        if (random.nextInt(4) == 0) assistantShortEn.random(random) else assistantShortZh.random(random)

    fun reasoning(random: Random, subject: String): String = when (random.nextInt(4)) {
        0 -> "用户在问「$subject」。先弄清楚他真正要的是什么：是要一个现成的结论，还是想知道怎么做。\n\n从上下文看，他前面提到时间很紧，所以应该先给结论，再补充必要的理由，不要铺垫太多。\n\n可以分成三点：目标、做法、风险。每点一两句话。"
        1 -> "The user wants help with \"$subject\". Let me check what they already said earlier in this thread so I do not repeat it.\n\nThey mentioned a deadline, so lead with the answer, then give two short reasons. Avoid a long preamble.\n\nStructure: answer first, then a short list, then one follow-up question."
        2 -> "先回顾一下这个话题之前聊过的内容。\n\n- 之前定的方案是分两步走\n- 第一步已经做完\n- 现在卡在第二步的取舍上\n\n所以这次回答只需要聚焦第二步，给出两个选项和各自的代价，再给一个推荐。"
        else -> "Thinking about trade-offs for \"$subject\": option A is simpler but less flexible; option B costs more up front and pays off if the scope grows. Given what the user said about time, recommend A, mention B as the upgrade path."
    }

    fun markdown(random: Random, subject: String, n: Int): String = when (n % 8) {
        0 -> """
# $subject：整理好了

下面是我根据你前面说的内容整理的要点，你看看有没有漏的。

## 一、目标

1. 本周内完成**初稿**，周五前给到你过目
2. 把重复的部分合并，*只保留一份*
3. 预留 `30 分钟` 做最后检查

## 二、安排

| 时间 | 事项 | 预计耗时 |
| --- | --- | --- |
| 周一 | 收集材料 | 1 小时 |
| 周二 | 起草 | 2 小时 |
| 周三 | 修改 | 1.5 小时 |
| 周四 | 校对 | 45 分钟 |

## 三、需要你确认的

- [ ] 截止时间是不是周五下午
- [ ] 要不要同步给同事
- [x] 材料已经齐了

> 小提示：如果中途有变动，直接告诉我，我会把整个安排重新排一遍。
""".trimIndent()
        1 -> """
## 一个小例子

你问到「$subject」的写法，下面用 Kotlin 演示一个最小可运行的版本：

```kotlin
data class Task(val id: Int, val title: String, val done: Boolean = false)

fun summarize(tasks: List<Task>): String {
    val finished = tasks.count { it.done }
    val percent = if (tasks.isEmpty()) 0 else finished * 100 / tasks.size
    return "完成 ${'$'}finished/${'$'}{tasks.size}（${'$'}percent%）"
}

fun main() {
    val tasks = listOf(Task(1, "起草"), Task(2, "修改", true), Task(3, "校对"))
    println(summarize(tasks))
}
```

运行结果：

```
完成 1/3（33%）
```

几点说明：

- `count` 对空列表安全，所以只需要单独处理除零
- 如果任务很多，可以改成 `sequence` 避免中间集合
- 想持久化的话，下一步可以接 Room
""".trimIndent()
        2 -> """
### Quick answer

Short version: **yes, but only if the list is stable.** Here's why.

1. Sorting is $'$'O(n \log n)$'$', so for a few thousand rows it's effectively free.
2. The real cost is re-composition when *any* item changes.
3. Give every row a stable `key` and the cost mostly disappears.

The expected cost can be written as

$$
T(n) = \sum_{i=1}^{n} c_i \cdot p_i
$$

where $'$'c_i$'$' is the cost of row $'$'i$'$' and $'$'p_i$'$' the probability it changes.

```python
def expected_cost(costs, probs):
    return sum(c * p for c, p in zip(costs, probs))

print(expected_cost([3, 5, 2], [0.1, 0.5, 0.9]))  # 4.6
```

Want me to look at your actual list code?
""".replace("$'$'", "$").trimIndent()
        3 -> """
# $subject 的三个方案

## 方案 A：先简后繁

- **优点**：马上能用，改动小
- **缺点**：后面扩展要重做一部分
- 适合：时间紧、需求还会变

## 方案 B：一步到位

- **优点**：结构清晰，长期省事
- **缺点**：前期投入大，可能做了用不上的部分
- 适合：需求已经定了

## 方案 C：折中

1. 先按 A 做核心流程
2. 把可能变的部分隔离成独立模块
3. 需求稳定后再逐步替换

我的建议是 **C**。理由：

> 你说过这件事「先跑起来再说」，同时又担心以后返工，折中最符合你的两个顾虑。

要不要我把 C 的第一步拆成具体任务？
""".trimIndent()
        4 -> """
## 命令速查

```bash
# 查看最近 20 条提交
git log --oneline -20

# 只看某个文件的历史
git log --follow -- app/build.gradle.kts

# 暂存当前修改，切到别的分支
git switch -c fix/${'$'}{USER}-hotfix
```

```json
{
  "name": "$subject",
  "version": "1.0.${n % 10}",
  "tags": ["notes", "weekly"],
  "reminder": { "enabled": true, "time": "09:30" }
}
```

注意：

- 第一条在大仓库里很快
- 第二条加了 `--follow` 会稍慢，但能跟踪重命名
- 最后一块是配置示例，字段名按你的实际情况改
""".trimIndent()
        5 -> """
Here's a compact comparison for your **$subject**:

| Option | Cost | Setup time | Notes |
| :-- | --: | --: | :-- |
| Basic | ¥199 | 10 min | good enough for most days |
| Plus | ¥399 | 25 min | adds sync across devices |
| Pro | ¥799 | 1 h | only if you need the API |

A few things worth knowing:

- *Basic* is fine if you use it alone
- *Plus* pays for itself after ~3 months if you value sync
- *Pro* is overkill unless you automate things

And the break-even point for Plus vs. Basic is

$$
n = \frac{399 - 199}{p} \approx 3.2 \text{ months}, \quad p = 62
$$

Let me know which direction you're leaning and I'll draft the next steps.
""".trimIndent()
        6 -> """
## 读书笔记：$subject

**核心观点**

作者认为，*注意力*比时间更稀缺。具体有三层意思：

1. 先决定**不做什么**
2. 把大任务切成 25 分钟的小块
3. 每天留出一段**完全不被打扰**的时间

**我摘的几句**

> “效率不是做更多事，而是让对的事情发生。”
>
> “完成比完美重要，但完成的前提是开始。”

**和你现在情况的对应**

- 你现在同时开着 4 件事，第 1 层最适用
- 早上 9–11 点是你自己说的状态最好的时间，可以做第 3 层
- 周末那种「想起来才做」的事，适合第 2 层

```text
今日三件事
1. [ ] 最重要的一件（上午）
2. [ ] 必须完成的一件（下午）
3. [ ] 可以放弃的一件（晚上，随意）
```
""".trimIndent()
        else -> """
### 排查思路

先把问题缩小。按下面的顺序走，每一步都有明确的判断标准：

1. **能稳定复现吗？** 不能的话先加日志，别急着改
2. **是最近改动引入的吗？** 用 `git bisect` 二分最快
3. **只在某台设备上出现吗？** 看系统版本和厂商定制

```kotlin
// 先加一个最小的计时，确认慢在哪一段
inline fun <T> timed(label: String, block: () -> T): T {
    val start = System.nanoTime()
    return try { block() } finally {
        val ms = (System.nanoTime() - start) / 1_000_000.0
        android.util.Log.d("timing", "${'$'}label took ${'$'}ms ms")
    }
}
```

如果结果显示：

| 阶段 | 耗时 | 结论 |
| --- | --- | --- |
| 读数据库 | 12 ms | 正常 |
| 解析 JSON | 180 ms | **可疑** |
| 渲染 | 25 ms | 正常 |

那就重点看 JSON 解析：是不是在主线程？是不是每次都重新解析？

总耗时可以近似为 $'$'T = t_{db} + t_{parse} + t_{ui}$'$'，优化最大的那一项最划算。
""".replace("$'$'", "$").trimIndent()
    }

    // Short bodies for tool turns.
    val searchQueries = listOf(
        "今天北京天气", "Kotlin coroutines flow vs channel", "附近评分高的咖啡馆", "上海到杭州高铁时刻",
        "SQLite WAL mode explained", "Jetpack Compose lazy list key", "周末展览推荐", "best pour over ratio",
        "iPhone 和 Pixel 拍照对比", "怎么给 Room 加索引",
    )
    val searchTitles = listOf(
        "官方文档 - 概览", "知乎：怎么选择才不踩坑", "Medium: A practical guide", "Stack Overflow: accepted answer",
        "GitHub Issue #1423", "少数派：我的使用一年后的感受", "维基百科", "Android Developers Blog",
    )
    val calendarTitles = listOf("和小林吃饭", "牙医复诊", "周会", "交电费", "给妈妈打电话", "Design review", "读书会", "跑步 5km")
    val files = listOf("notes/plan.md", "notes/ideas.txt", "src/main.py", "scripts/backup.sh", "reports/weekly.md", "todo.md")
    val shellCommands = listOf("ls -la", "python3 src/main.py", "git status", "cat notes/plan.md | head -20", "du -sh .", "bash scripts/backup.sh")
    val computerActions = listOf("list_windows", "get_window_state", "click", "type_text", "press_key", "screenshot", "launch_app")

    val groupLines = listOf(
        "我这边先把表格拉好了", "收到，我补数据", "有个地方我不确定，大家看看", "我觉得第二个方案更稳", "同意",
        "可以，我来写总结", "稍等我查下资料", "刚才那个链接打不开？", "换个思路试试", "OK，那就按这个来",
        "提醒一下：下午三点要交", "I'll draft the intro first", "Looks good to me", "Can someone double-check the numbers?",
        "我去整理一下会议记录", "这里有个小问题", "👍", "好的，辛苦了", "明天同一时间再对一下进度", "搞定了，已经发到共享文件夹",
    )

    val favoriteNotes = listOf(
        "搬家清单", "常用命令", "菜谱：番茄牛腩", "周报模板", "面试问题清单", "读书摘录", "旅行行程草稿", "咖啡冲煮参数",
    )
}

package me.ayuilos.miffan.perf

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.ai.tools.GENERATE_IMAGE_TOOL
import me.ayuilos.miffan.data.ai.tools.WORKSPACE_SHELL_TOOL_NAME
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.ayuilos.miffan.data.model.MessageRef
import me.ayuilos.miffan.data.model.TopicStart
import me.rerere.ai.core.MessageRole
import me.rerere.ai.core.TokenUsage
import me.rerere.ai.ui.ModelSnapshot
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Instant
import kotlin.uuid.Uuid

internal const val DAY_MS = 24L * 60 * 60 * 1000

/** A name-based id: the same name always gives the same id, so every run of the fixture writes the same rows. */
internal fun fixtureId(name: String): Uuid =
    Uuid.parse(UUID.nameUUIDFromBytes("miffan-perf:$name".toByteArray()).toString())

internal fun localTime(millis: Long): LocalDateTime =
    Instant.fromEpochMilliseconds(millis).toLocalDateTime(TimeZone.currentSystemDefault())

/** What a revision (a "notice" in the thread) needs to know about the turn that caused it. */
internal class PreferenceTurn(
    val assistantId: Uuid,
    val conversationId: Uuid,
    val triggerMessageId: Uuid,
    val toolCallId: String,
    val at: Long,
    val summary: String,
)

internal class BuiltThread(
    val conversations: List<Conversation>,
    val preferenceTurns: List<PreferenceTurn>,
)

/** A message that the favorites page can point at. */
internal class NodeRef(val conversation: Conversation, val node: MessageNode)

internal class ThreadSpec(
    val key: String,
    val assistantId: Uuid,
    val modelId: Uuid,
    val messageCount: Int,
    val segmentCount: Int,
    val days: Int,
    /** Segment numbers whose first assistant turn draws an image. */
    val imageSegments: Set<Int> = emptySet(),
    /** Segment numbers that contain a "partner changed its own preferences" turn. */
    val preferenceSegments: Set<Int> = emptySet(),
    val endAt: Long,
    val seed: Int,
)

/**
 * Builds the segments of one partner's IM thread: a mix of chat lines, long Markdown replies, reasoning,
 * tool records, drawings and quoted replies, spread over [ThreadSpec.days] days.
 */
internal fun buildThread(spec: ThreadSpec, images: List<String>): BuiltThread {
    val random = Random(spec.seed)
    val topicOffset = spec.seed and Int.MAX_VALUE
    val sizes = segmentSizes(spec, random)
    val gapMs = spec.days * DAY_MS / spec.segmentCount
    val conversations = ArrayList<Conversation>(spec.segmentCount)
    val turns = ArrayList<PreferenceTurn>()
    var markdownCounter = 0
    for (segment in 0 until spec.segmentCount) {
        val segmentId = fixtureId("${spec.key}/seg$segment")
        val startAt = spec.endAt - (spec.segmentCount - segment) * gapMs + random.nextLong(gapMs / 4)
        var clock = startAt
        val messages = ArrayList<UIMessage>(sizes[segment])
        val replyTargets = HashMap<Int, MessageRef>()
        val replyTo = HashMap<Int, MessageRef>()
        var assistantTurn = 0
        val ids = List(sizes[segment]) { fixtureId("${spec.key}/seg$segment/msg$it") }
        for (index in 0 until sizes[segment]) {
            clock += 15_000L + random.nextLong(150_000L)
            val fromUser = index % 2 == 0
            val id = ids[index]
            if (fromUser) {
                val text = if (random.nextInt(100) < 12) longUserText(random) else PerfCorpus.userLine(random)
                messages += UIMessage(
                    id = id, role = MessageRole.USER, parts = listOf(UIMessagePart.Text(text)),
                    createdAt = localTime(clock),
                )
                // Quote an earlier assistant reply of the same segment now and then.
                if (index >= 6 && random.nextInt(100) < 8) {
                    val target = (index - 1 - 2 * random.nextInt(minOf(3, index / 2))).coerceAtLeast(1)
                    replyTo[index] = MessageRef(segmentId, ids[if (target % 2 == 1) target else target - 1])
                }
            } else {
                val subject = PerfCorpus.topicTitle(segment)
                val toolCallId = "call_${spec.key}_${segment}_$index"
                val forcePreference = assistantTurn == 3 && segment in spec.preferenceSegments
                val forceImage = assistantTurn == 2 && segment in spec.imageSegments
                val parts: List<UIMessagePart>
                when {
                    forcePreference -> {
                        val note = preferenceNotes[(segment / 2) % preferenceNotes.size]
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = toolCallId, toolName = "update_my_preferences",
                                input = buildJsonObject { put("preference", note); put("reason", "你刚才提到的习惯") }.toString(),
                                output = listOf(UIMessagePart.Text("""{"success":true}""")),
                            ),
                            UIMessagePart.Text("好，我记住了：$note"),
                        )
                        turns += PreferenceTurn(
                            spec.assistantId, segmentId, ids[index - 1], toolCallId, clock, "记住了：$note",
                        )
                    }
                    forceImage && images.isNotEmpty() -> {
                        val image = images[(segment / 2) % images.size]
                        parts = listOf(
                            UIMessagePart.Tool(
                                toolCallId = toolCallId, toolName = GENERATE_IMAGE_TOOL,
                                input = buildJsonObject { put("prompt", "一只在窗边晒太阳的猫，水彩风格") }.toString(),
                                output = listOf(
                                    UIMessagePart.Text("""{"success":true,"count":1}"""),
                                    UIMessagePart.Image(image),
                                ),
                            ),
                            UIMessagePart.Text("画好啦，你看看喜欢吗？"),
                        )
                    }
                    else -> parts = assistantParts(random, subject, markdownCounter, toolCallId, clock, images).also {
                        if (it.any { part -> part is UIMessagePart.Text && part.text.length > 400 }) markdownCounter++
                    }
                }
                val answered = clock + 2_000L + random.nextLong(9_000L)
                messages += UIMessage(
                    id = id, role = MessageRole.ASSISTANT, parts = parts,
                    createdAt = localTime(clock), finishedAt = localTime(answered),
                    modelId = spec.modelId, modelSnapshot = ModelSnapshot("fake-llm", "Fake LLM"),
                    usage = TokenUsage(promptTokens = 400 + index * 12, completionTokens = 60 + random.nextInt(600),
                        totalTokens = 460 + index * 12),
                )
                clock = answered
                assistantTurn++
                replyTargets[index] = MessageRef(segmentId, id)
            }
        }
        val nodes = messages.mapIndexed { index, message ->
            MessageNode(
                id = fixtureId("${spec.key}/seg$segment/node$index"),
                message = message,
                parentId = if (index == 0) null else fixtureId("${spec.key}/seg$segment/node${index - 1}"),
                selectedChildId = if (index == messages.lastIndex) null else fixtureId("${spec.key}/seg$segment/node${index + 1}"),
                replyTo = replyTo[index],
            )
        }
        val last = segment == spec.segmentCount - 1
        val endOfSegment = messages.last().let { (it.finishedAt ?: it.createdAt) }
        conversations += Conversation(
            id = segmentId,
            assistantId = spec.assistantId,
            title = PerfCorpus.topicTitle(segment + topicOffset),
            messageNodes = nodes,
            selectedRootId = nodes.first().id,
            createAt = java.time.Instant.ofEpochMilli(startAt),
            updateAt = java.time.Instant.ofEpochMilli(clock),
            threadSummary = if (last) "" else PerfCorpus.topicSummary(segment + topicOffset),
            threadClosedAt = if (last) 0 else clock + 30 * 60_000L,
            topicStart = when {
                segment == 0 -> null
                segment % 11 == 0 -> TopicStart.WELCOME
                segment % 7 == 0 -> TopicStart.OWNER
                segment % 5 == 0 -> TopicStart.IDLE
                else -> TopicStart.AUTO
            },
        )
        check(endOfSegment >= messages.first().createdAt)
    }
    return BuiltThread(conversations, turns)
}

private val preferenceNotes = listOf(
    "回答尽量简短，先给结论", "早上不要发提醒，九点以后再说", "叫我「小陈」就好", "代码示例优先用 Kotlin",
    "价格都用人民币", "周末不聊工作", "长文用列表而不是大段文字", "晚上十一点后只回复紧急的事",
)

/** Message counts per segment: uneven, never below 6, summing exactly to [ThreadSpec.messageCount]. */
private fun segmentSizes(spec: ThreadSpec, random: Random): IntArray {
    val weights = DoubleArray(spec.segmentCount) { 0.35 + random.nextDouble() * 1.3 }
    val total = weights.sum()
    val sizes = IntArray(spec.segmentCount) { maxOf(6, (weights[it] / total * spec.messageCount).toInt()) }
    var diff = spec.messageCount - sizes.sum()
    var cursor = 0
    while (diff != 0) {
        val i = cursor++ % spec.segmentCount
        if (diff > 0) { sizes[i]++; diff-- } else if (sizes[i] > 6) { sizes[i]--; diff++ }
    }
    return sizes
}

private fun longUserText(random: Random): String = listOf(
    "帮我把下面这几点整理成一份简短的说明，语气随意一点：1. 周三之前要交材料 2. 材料包括表格和说明 3. 表格已经做好了，说明还没写。",
    "I need a short plan for the weekend: Saturday morning errands, afternoon free, Sunday a long walk if the weather holds. Keep it to five lines.",
    "我最近睡得不太好，晚上总是醒，白天又很困。你能不能帮我想想，从哪些方面入手比较有效？不想吃药，想先从作息和环境调起。",
    "这周的支出我大概记了一下：房租 3200，吃饭 860，交通 210，其他 540。你帮我算一下占比，再看看哪里可以省。",
).random(random)

private fun assistantParts(
    random: Random, subject: String, markdownCounter: Int, toolCallId: String, at: Long, images: List<String>,
): List<UIMessagePart> {
    val pick = random.nextInt(100)
    val reasoningAt = Instant.fromEpochMilliseconds(at)
    fun reasoning() = UIMessagePart.Reasoning(
        reasoning = PerfCorpus.reasoning(random, subject), createdAt = reasoningAt,
        finishedAt = Instant.fromEpochMilliseconds(at + 3_000L + random.nextLong(6_000L)),
    )
    return when {
        pick < 45 -> listOf(UIMessagePart.Text(PerfCorpus.assistantLine(random)))
        pick < 65 -> listOf(UIMessagePart.Text(PerfCorpus.markdown(random, subject, markdownCounter)))
        pick < 77 -> listOf(
            reasoning(),
            UIMessagePart.Text(
                if (random.nextBoolean()) PerfCorpus.markdown(random, subject, markdownCounter + 3)
                else PerfCorpus.assistantLine(random),
            ),
        )
        pick < 91 -> toolParts(random, subject, toolCallId, at, images) + UIMessagePart.Text(
            "好了，弄完了。你可以看一下上面的记录，有哪里不对我再改。",
        )
        else -> listOf(UIMessagePart.Text(PerfCorpus.assistantLine(random)))
    }
}

private fun call(id: String, name: String, input: JsonObject, output: JsonObject?, vararg extra: UIMessagePart) =
    UIMessagePart.Tool(
        toolCallId = id, toolName = name, input = input.toString(),
        output = if (output == null) emptyList() else listOf<UIMessagePart>(UIMessagePart.Text(output.toString())) + extra,
    )

private fun toolParts(random: Random, subject: String, id: String, at: Long, images: List<String>): List<UIMessagePart> =
    when (random.nextInt(5)) {
        0 -> {
            val query = PerfCorpus.searchQueries.random(random)
            listOf(
                call(id, "search_web", buildJsonObject { put("query", query) }, buildJsonObject {
                    put("items", buildJsonArray {
                        PerfCorpus.searchTitles.shuffled(random).take(5).forEachIndexed { i, title ->
                            add(buildJsonObject {
                                put("title", title)
                                put("url", "https://example.com/result/$i/${query.hashCode().toUInt()}")
                                put("text", "$title。关于“$query”的要点：先看总体结论，再对比不同来源的细节；多数来源认为在大部分情况下差别不大，" +
                                    "但在特定场景（数据量大、网络差、设备旧）下差别明显，建议按自己的实际情况取舍。（摘要 $i）")
                            })
                        }
                    })
                }),
            )
        }
        1 -> {
            val day = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
            listOf(
                call(id, "calendar_query", buildJsonObject {
                    put("start", day.toLocalDate().toString()); put("end", day.toLocalDate().plusDays(7).toString())
                }, buildJsonObject {
                    put("events", buildJsonArray {
                        PerfCorpus.calendarTitles.shuffled(random).take(4).forEachIndexed { i, title ->
                            add(buildJsonObject {
                                put("id", 100 + i); put("title", title)
                                put("start", day.plusDays(i + 1L).withHour(10 + i).withMinute(0).toString())
                                put("end", day.plusDays(i + 1L).withHour(11 + i).withMinute(0).toString())
                            })
                        }
                    })
                }),
            )
        }
        2 -> {
            val title = PerfCorpus.calendarTitles.random(random)
            val start = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).plusDays(2)
                .withHour(15).withMinute(0).withSecond(0).withNano(0)
            listOf(
                call(id, "calendar_create", buildJsonObject {
                    put("title", title); put("start", start.toLocalDateTime().toString()); put("location", "")
                }, buildJsonObject {
                    put("success", true); put("event_id", at % 100_000); put("title", title)
                    put("start", start.toString()); put("end", start.plusHours(1).toString()); put("all_day", false)
                }),
            )
        }
        3 -> {
            val file = PerfCorpus.files.random(random)
            listOf(
                call("${id}_w", "workspace_write_file", buildJsonObject {
                    put("path", file); put("text", "# $subject\n\n- 待办一\n- 待办二\n- 待办三\n")
                }, buildJsonObject { put("ok", true); put("path", file); put("bytes", 42) }),
                call("${id}_s", WORKSPACE_SHELL_TOOL_NAME, buildJsonObject {
                    put("command", PerfCorpus.shellCommands.random(random))
                }, buildJsonObject {
                    put("exit_code", 0); put("stdout", "ok\n"); put("stderr", "")
                }),
            )
        }
        else -> {
            val shot = images.firstOrNull()
            listOf(
                call("${id}_a", "computer_list_windows", buildJsonObject { }, buildJsonObject { put("windows", JsonArray(emptyList())) }),
                call("${id}_b", "computer_click", buildJsonObject { put("x", 320); put("y", 480) },
                    buildJsonObject { put("status", "ok") }),
                call("${id}_c", "computer_type_text", buildJsonObject { put("text", subject) },
                    buildJsonObject { put("status", "ok") }, *listOfNotNull(shot?.let { UIMessagePart.Image(it) }).toTypedArray()),
            )
        }
    }

package me.ayuilos.miffan.perf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.Log
import androidx.core.content.edit
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.db.entity.PartnerChatMessageEntity
import me.ayuilos.miffan.data.db.fts.MessageFtsManager
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.ChatGroup
import me.ayuilos.miffan.data.model.ChatOrganization
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.data.model.InterfaceModeState
import me.ayuilos.miffan.data.model.MessageRef
import me.ayuilos.miffan.data.model.MiffanAppearance
import me.ayuilos.miffan.data.model.MiffanKind
import me.ayuilos.miffan.data.model.MiffanMotionProfile
import me.ayuilos.miffan.data.model.MiffanPalette
import me.ayuilos.miffan.data.model.NodeFavoriteTarget
import me.ayuilos.miffan.data.model.OWNER_PARTICIPANT_ID
import me.ayuilos.miffan.data.model.PartnerChat
import me.ayuilos.miffan.data.model.PartnerChatKind
import me.ayuilos.miffan.data.model.PartnerPair
import me.ayuilos.miffan.data.model.WhaleThemeDiscovery
import me.ayuilos.miffan.data.partner.PartnerChatStore
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.data.repository.FavoriteRepository
import me.ayuilos.miffan.data.revision.AssistantRevisionRecorder
import me.ayuilos.miffan.data.revision.RevisionAuthor
import me.ayuilos.miffan.data.revision.RevisionOrigin
import me.ayuilos.miffan.data.revision.RevisionRepository
import me.ayuilos.miffan.data.revision.RevisionSubject
import me.ayuilos.miffan.data.revision.RoomRevisionStore
import me.ayuilos.miffan.data.datastore.DEFAULT_ASSISTANT_ID
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.random.Random
import kotlin.uuid.Uuid

private const val TAG = "PerfFixture"

/** Where the fake model of `scripts/perf/fake_llm.py` listens (on the emulator and, via `adb reverse`, on a phone). */
internal const val FAKE_LLM_BASE_URL = "http://127.0.0.1:18799/v1"

internal data class FixtureSummary(
    val partners: Int,
    val segments: Int,
    val messages: Int,
    val partnerChatMessages: Int,
    val favorites: Int,
    val revisions: Int,
    val images: Int,
    val millis: Long,
) {
    override fun toString() =
        "partners=$partners segments=$segments messages=$messages partnerChatMessages=$partnerChatMessages " +
            "favorites=$favorites revisions=$revisions images=$images in ${millis}ms"
}

/**
 * Replaces the app's data with a deterministic, realistic data set for performance measurements: six
 * partners, a 2000-message main thread across 60 segments over 90 days, smaller threads, a 500-message
 * group chat, a 100-message partner-to-partner chat, favorites and thread notices.
 *
 * Everything is written through the app's own repositories, DAOs and settings store, so the data has
 * exactly the shape the app produces. Running it again wipes the database first, so it replaces rather
 * than adds.
 */
internal class PerfFixture(private val context: Context) : KoinComponent {
    suspend fun run(anchorMillis: Long): FixtureSummary = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()
        val settingsStore = get<SettingsStore>()
        settingsStore.settingsFlow.first { !it.init }
        val specs = partnerSpecs()
        val assistants = specs.map { it.assistant() }
        val model = fakeModel()
        val provider = ProviderSetting.OpenAI(
            id = fixtureId("provider"), name = "Fake LLM", apiKey = "perf-key", baseUrl = FAKE_LLM_BASE_URL,
            models = listOf(model),
        )

        // Settings first: changing assistants makes the app record revisions, and the wipe below removes those.
        settingsStore.update { settings ->
            settings.copy(
                dynamicColor = false,
                interfaceMode = InterfaceModeState(InterfaceMode.IM, choicePending = false),
                onboardingDone = true,
                remoteWorkspaceIntroSeen = true,
                whaleThemeDiscovery = WhaleThemeDiscovery(settingsSeen = true),
                displaySetting = settings.displaySetting.copy(
                    userNickname = "测试用户",
                    updateCheckDisabledUntilEpochMillis = UPDATE_CHECK_PAUSED_UNTIL,
                    updateShowWhatsNew = false,
                ),
                providers = settings.providers.filterNot { it.id == provider.id } + provider,
                chatModelId = model.id,
                fastModelId = model.id,
                enableSuggestion = false,
                assistants = assistants,
                assistantId = assistants.first().id,
                chatOrganization = ChatOrganization(
                    pinned = listOf(assistants[0].id, assistants[2].id),
                    groups = listOf(
                        ChatGroup(fixtureId("group/work"), "工作", listOf(assistants[1].id, assistants[3].id)),
                        ChatGroup(fixtureId("group/life"), "生活", listOf(assistants[4].id)),
                    ),
                    legacyPinsMigrated = true,
                ),
                partnerContactGrants = listOf(PartnerPair.of(assistants[1].id, assistants[2].id)),
            )
        }

        wipe()
        val images = writeImages()

        val conversations = get<ConversationRepository>()
        val builtThreads = specs.map { spec ->
            buildThread(spec.thread(anchorMillis, model.id), if (spec.key == "main") images else images.take(2))
        }
        var segments = 0
        var messages = 0
        builtThreads.forEach { built ->
            built.conversations.forEach { conversation ->
                conversations.insertConversation(conversation)
                segments++
                messages += conversation.messageNodes.size
            }
        }

        val groupMessages = writeGroupChat(assistants, anchorMillis) + writeDirectChat(assistants, anchorMillis)
        val favorites = writeFavorites(builtThreads.map { it.conversations })
        val revisions = writeRevisions(assistants, builtThreads.first().preferenceTurns)
        writeReadState(specs, assistants, builtThreads.map { it.conversations }, anchorMillis)

        FixtureSummary(
            partners = assistants.size, segments = segments, messages = messages, partnerChatMessages = groupMessages,
            favorites = favorites, revisions = revisions, images = images.size,
            millis = System.currentTimeMillis() - started,
        ).also { Log.i(TAG, "Fixture written: $it") }
    }

    // --- partners -------------------------------------------------------------------------------------

    private class PartnerSpec(
        val key: String, val id: Uuid, val name: String, val avatar: Avatar, val prompt: String,
        val messages: Int, val segments: Int, val days: Int, val unread: Int,
    ) {
        fun assistant() = Assistant(
            id = id, name = name, avatar = avatar, useAssistantAvatar = true, systemPrompt = prompt,
            enableMemory = false, enableWebSearch = false,
        )

        fun thread(anchor: Long, modelId: Uuid) = ThreadSpec(
            key = key, assistantId = id, modelId = modelId, messageCount = messages, segmentCount = segments, days = days,
            imageSegments = if (key == "main") setOf(5, 15, 25, 35, 45, 55) else emptySet(),
            preferenceSegments = if (key == "main") (0 until 60 step 2).toSet() else emptySet(),
            endAt = anchor, seed = key.hashCode(),
        )
    }

    private fun partnerSpecs(): List<PartnerSpec> {
        fun miffan(palette: MiffanPalette, kind: MiffanKind, motion: MiffanMotionProfile) =
            Avatar.Miffan(MiffanAppearance(palette, kind), motion)
        return listOf(
            PartnerSpec("main", DEFAULT_ASSISTANT_ID, "小米", miffan(MiffanPalette.CLASSIC, MiffanKind.RICE, MiffanMotionProfile.CURIOUS),
                "你是小米，用户的日常伙伴。说话自然、简短，必要时再展开。", 2000, 60, 90, unread = 0),
            PartnerSpec("sprout", fixtureId("partner/sprout"), "阿青", miffan(MiffanPalette.MATCHA, MiffanKind.SPROUT, MiffanMotionProfile.CALM),
                "你是阿青，擅长整理计划和待办。", 300, 10, 60, unread = 3),
            PartnerSpec("dumpling", fixtureId("partner/dumpling"), "糯米", miffan(MiffanPalette.SAKURA, MiffanKind.DUMPLING, MiffanMotionProfile.LIVELY),
                "你是糯米，陪用户聊生活和美食。", 200, 7, 45, unread = 0),
            PartnerSpec("star", fixtureId("partner/star"), "Nova", miffan(MiffanPalette.MOONLIGHT, MiffanKind.STARGAZER, MiffanMotionProfile.CURIOUS),
                "You are Nova, a calm assistant for reading and research.", 120, 5, 40, unread = 12),
            PartnerSpec("salt", fixtureId("partner/salt"), "海盐", Avatar.WhaleGirl(MiffanMotionProfile.LIVELY),
                "你是海盐，负责写作和翻译。", 60, 3, 30, unread = 1),
            PartnerSpec("jade", fixtureId("partner/jade"), "墨玉", Avatar.Emoji("🦊"),
                "你是墨玉，偶尔出现，简短回答。", 20, 1, 20, unread = 20),
        )
    }

    private fun fakeModel() = Model(modelId = "fake-llm", displayName = "Fake LLM", id = fixtureId("model"))

    // --- wiping ---------------------------------------------------------------------------------------

    private suspend fun wipe() {
        withContext(Dispatchers.IO) { get<AppDatabase>().clearAllTables() }
        get<MessageFtsManager>().deleteAll()
        context.getSharedPreferences("im_thread_list", Context.MODE_PRIVATE).edit(commit = true) { clear() }
        // First-run hints the benchmarks must not wait on.
        context.getSharedPreferences("im_ui_hints", Context.MODE_PRIVATE).edit(commit = true) {
            putBoolean("chats_long_press_seen", true)
        }
        imagesDir().deleteRecursively()
    }

    private fun imagesDir() = File(context.filesDir, "images/perf_fixture")

    // --- images ---------------------------------------------------------------------------------------

    /** Six small drawings, written as plain PNG files the way generated images are stored. */
    private suspend fun writeImages(): List<String> = withContext(Dispatchers.IO) {
        val dir = imagesDir().apply { mkdirs() }
        val palette = listOf(
            0xFFFFB74D.toInt() to 0xFFE57373.toInt(), 0xFF81C784.toInt() to 0xFF4DB6AC.toInt(),
            0xFF64B5F6.toInt() to 0xFF9575CD.toInt(), 0xFFF06292.toInt() to 0xFFFFD54F.toInt(),
            0xFF90A4AE.toInt() to 0xFF5C6BC0.toInt(), 0xFFA1887F.toInt() to 0xFFFFCC80.toInt(),
        )
        palette.mapIndexed { i, (from, to) ->
            val size = 384
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), from, to, Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, size.toFloat(), size.toFloat(), paint)
            paint.shader = null
            val random = Random(i)
            repeat(14) {
                paint.color = (0x66FFFFFF + (random.nextInt(0x40) shl 16)).toInt()
                canvas.drawCircle(random.nextFloat() * size, random.nextFloat() * size, 20f + random.nextFloat() * 70f, paint)
            }
            val file = File(dir, "drawing_$i.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
            file.toUri().toString()
        }
    }

    // --- partner chats --------------------------------------------------------------------------------

    private suspend fun writeGroupChat(assistants: List<Assistant>, anchor: Long): Int {
        val store = get<PartnerChatStore>()
        val db = get<AppDatabase>()
        val members = listOf(assistants[1], assistants[2], assistants[3], assistants[4])
        val chat = PartnerChat(
            id = fixtureId("chat/group"), kind = PartnerChatKind.GROUP, memberIds = members.map { it.id },
            title = "周末出游小分队", createdAt = Instant.ofEpochMilli(anchor - 31 * DAY_MS), hostId = members.first().id,
        )
        store.saveGroup(chat)
        val random = Random(77)
        val senders = members.map { it.id } + OWNER_PARTICIPANT_ID
        val total = 500
        val ids = List(total) { fixtureId("chat/group/m$it") }
        val rows = (0 until total).map { i ->
            val sender = if (random.nextInt(100) < 18) OWNER_PARTICIPANT_ID else senders.random(random)
            val text = when (random.nextInt(10)) {
                0 -> PerfCorpus.markdown(random, "出游安排", i).lines().take(8).joinToString("\n")
                else -> PerfCorpus.groupLines.random(random)
            }
            PartnerChatMessageEntity(
                id = ids[i].toString(), chatId = chat.id.toString(), senderId = sender.toString(), text = text,
                createdAt = anchor - 30 * DAY_MS + i * (30 * DAY_MS / total) + random.nextLong(60_000L),
                replyTo = if (i > 10 && random.nextInt(100) < 10) ids[i - 1 - random.nextInt(8)].toString() else null,
                mentions = if (sender != OWNER_PARTICIPANT_ID && random.nextInt(100) < 12) {
                    JsonInstant.encodeToString(listOf(OWNER_PARTICIPANT_ID))
                } else "[]",
            )
        }
        val dao = db.partnerChatDao()
        rows.forEach { dao.insert(it) }
        return total
    }

    private suspend fun writeDirectChat(assistants: List<Assistant>, anchor: Long): Int {
        val store = get<PartnerChatStore>()
        val a = assistants[1]
        val b = assistants[2]
        val chat = store.direct(a.id, b.id)
        val random = Random(78)
        val total = 100
        val dao = get<AppDatabase>().partnerChatDao()
        (0 until total).forEach { i ->
            val sender = if (i % 2 == 0) a else b
            dao.insert(
                PartnerChatMessageEntity(
                    id = fixtureId("chat/direct/m$i").toString(), chatId = chat.id.toString(), senderId = sender.id.toString(),
                    text = if (i % 9 == 0) PerfCorpus.markdown(random, "两个人的计划", i).lines().take(6).joinToString("\n")
                    else PerfCorpus.assistantLine(random),
                    createdAt = anchor - 10 * DAY_MS + i * (10 * DAY_MS / total),
                    exchangeId = fixtureId("chat/direct/x${i / 4}").toString(),
                ),
            )
        }
        return total
    }

    // --- favorites, revisions, read state -------------------------------------------------------------

    private suspend fun writeFavorites(threads: List<List<me.ayuilos.miffan.data.model.Conversation>>): Int {
        val repository = get<FavoriteRepository>()
        val random = Random(99)
        // 40 from the main thread, 10 from the other partners; assistant replies with some substance.
        val candidates = threads.flatMapIndexed { threadIndex, conversations ->
            conversations.flatMap { conversation ->
                conversation.messageNodes
                    .filter { it.message.role == me.rerere.ai.core.MessageRole.ASSISTANT && it.message.toText().length > 60 }
                    .map { NodeRef(conversation, it) to threadIndex }
            }
        }
        val chosen = candidates.filter { it.second == 0 }.shuffled(random).take(40).map { it.first } +
            candidates.filter { it.second != 0 }.shuffled(random).take(10).map { it.first }
        chosen.forEach { ref ->
            repository.addNodeFavorite(NodeFavoriteTarget(ref.conversation.id, ref.conversation.title, ref.node.id, ref.node))
        }
        return chosen.size
    }

    private suspend fun writeRevisions(assistants: List<Assistant>, turns: List<PreferenceTurn>): Int {
        val db = get<AppDatabase>()
        var now = 0L
        val repository = RevisionRepository(RoomRevisionStore(db, db.revisionDao())) { Instant.ofEpochMilli(now) }
        val main = assistants.first()
        var current = main
        var count = 0
        // Thirty "the partner changed its own settings" notices, each caused by a real message of the thread.
        turns.take(30).forEachIndexed { i, turn ->
            val next = current.copy(learnedPreferences = (current.learnedPreferences + "\n" + turn.summary).trim())
            now = turn.at + 1_000L
            val origin = RevisionOrigin(
                author = RevisionAuthor.AGENT,
                trigger = MessageRef(turn.conversationId, turn.triggerMessageId),
                toolCallId = turn.toolCallId,
                summary = turn.summary,
            )
            val recorded = withContext(origin) {
                repository.record(
                    RevisionSubject.ASSISTANT, main.id.toString(),
                    before = AssistantRevisionRecorder.snapshot(current),
                    after = AssistantRevisionRecorder.snapshot(next),
                )
            }
            if (recorded != null) count++
            current = next
        }
        return count
    }

    /** Some partners have unread replies: the read time sits just before their last [PartnerSpec.unread] replies. */
    private fun writeReadState(
        specs: List<PartnerSpec>, assistants: List<Assistant>,
        threads: List<List<me.ayuilos.miffan.data.model.Conversation>>, anchor: Long,
    ) {
        val prefs = context.getSharedPreferences("im_thread_list", Context.MODE_PRIVATE)
        prefs.edit(commit = true) {
            specs.forEachIndexed { i, spec ->
                val replyTimes = threads[i].flatMap { it.messageNodes }
                    .filter { it.message.role == me.rerere.ai.core.MessageRole.ASSISTANT }
                    .map { (it.message.finishedAt ?: it.message.createdAt) }
                    .sortedBy { it }
                    .map { it.toEpochMillis() }
                val readAt = if (spec.unread == 0) anchor
                else replyTimes.getOrNull(replyTimes.size - spec.unread - 1)?.plus(1) ?: 0L
                putLong("read:${assistants[i].id}", readAt)
            }
            // Group chat: its last seven partner messages are unread; the two-partner chat never is.
            putLong("read:${fixtureId("chat/group")}", anchor - 30 * DAY_MS + 493 * (30 * DAY_MS / 500))
        }
    }

    private fun kotlinx.datetime.LocalDateTime.toEpochMillis(): Long =
        toInstantMillis(this)

    private fun toInstantMillis(value: kotlinx.datetime.LocalDateTime): Long =
        value.toJavaLocalDateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun kotlinx.datetime.LocalDateTime.toJavaLocalDateTime(): java.time.LocalDateTime =
        java.time.LocalDateTime.of(year, monthNumber, dayOfMonth, hour, minute, second, nanosecond)

    companion object {
        /** Far in the future: the fixture app never checks for or announces updates. */
        const val UPDATE_CHECK_PAUSED_UNTIL = 4_102_444_800_000L

        /** Midnight at the start of today in the device's time zone. */
        fun defaultAnchor(): Long = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}

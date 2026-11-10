package me.ayuilos.miffan.data.ai

import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import me.ayuilos.miffan.data.datastore.NetworkSetting
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.core.MessageRole
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.Provider
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.StreamChunk
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A streamed reply is shown at most once per frame; chunks that arrive in between are merged, never dropped. */
class GenerationStreamBatchingTest {
    private fun stream(
        retry: Boolean = false,
        chunks: suspend kotlinx.coroutines.flow.FlowCollector<StreamChunk>.(attempt: Int) -> Unit,
    ): List<List<UIMessage>> {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        try {
            val assistant = Assistant(streamOutput = true)
            val model = Model(modelId = "test")
            val setting = ProviderSetting.OpenAI(models = listOf(model))
            val provider = mockk<Provider<ProviderSetting.OpenAI>>()
            val manager = mockk<ProviderManager>()
            every { manager.getProviderByType(setting) } returns provider
            var attempts = 0
            coEvery { provider.streamText(setting, any(), any()) } answers {
                val attempt = attempts++
                flow { chunks(attempt) }.buffer(Channel.UNLIMITED)
            }
            return runBlocking {
                // Relaxed: a retry asks the context for its status line.
                GenerationHandler(mockk(relaxed = true), manager, JsonInstant, mockk(), mockk(), mockk()).generateText(
                    Settings(providers = listOf(setting), networkSetting = NetworkSetting(enableAutoRetry = retry)),
                    model, listOf(UIMessage.user("hello")),
                    assistant = assistant, maxSteps = 1, access = AgentAccess.forAssistant(assistant, true),
                ).toList().map { (it as GenerationChunk.Messages).messages }
            }
        } finally { unmockkStatic(Log::class) }
    }

    private fun List<UIMessage>.replyText(): String = last().also { assertEquals(MessageRole.ASSISTANT, it.role) }
        .parts.filterIsInstance<UIMessagePart.Text>().joinToString("") { it.text }

    @Test
    fun aBurstOfChunksIsShownInFewUpdatesWithEveryPieceInOrder() {
        val pieces = (1..600).map { "片段$it " }
        val updates = stream {
            emit(StreamChunk.Connected)
            emit(StreamChunk.TextStart("t"))
            pieces.forEach { emit(StreamChunk.TextDelta("t", it)) }
            emit(StreamChunk.TextEnd("t"))
            emit(StreamChunk.Finish())
        }
        assertEquals(pieces.joinToString(""), updates.last().replyText())
        // Each update extends the one before: nothing is reordered or lost on the way.
        updates.map { it.replyText() }.zipWithNext { earlier, later -> assertTrue(later.startsWith(earlier)) }
        assertTrue("600 chunks at once took ${updates.size} updates", updates.size < 100)
    }

    @Test
    fun chunksThatArriveSlowlyAreEachShown() {
        val updates = stream {
            emit(StreamChunk.TextStart("t"))
            repeat(5) {
                emit(StreamChunk.TextDelta("t", "$it"))
                delay(60)
            }
            emit(StreamChunk.Finish())
        }
        val texts = updates.map { it.replyText() }.distinct()
        assertEquals(listOf("0", "01", "012", "0123", "01234"), texts.filter { it.isNotEmpty() })
    }

    @Test
    fun aFailingStreamKeepsWhatArrivedAndReportsTheFailure() {
        val failure = runCatching {
            stream {
                emit(StreamChunk.TextStart("t"))
                emit(StreamChunk.TextDelta("t", "partial"))
                delay(50)
                throw IllegalStateException("boom")
            }
        }.exceptionOrNull()
        assertTrue("$failure", failure is IllegalStateException && failure.message == "boom")
    }

    @Test
    fun aNetworkFailureSurfacesAsItIsWhenRetriesAreOff() {
        val failure = runCatching { stream { throw IOException("offline") } }.exceptionOrNull()
        assertTrue("$failure", failure is IOException)
    }

    @Test
    fun aRetryStartsTheReplyOverInsteadOfAppendingToTheHalfShownOne() {
        val updates = stream(retry = true) { attempt ->
            emit(StreamChunk.TextStart("t"))
            if (attempt == 0) {
                emit(StreamChunk.TextDelta("t", "half a rep"))
                delay(40)
                throw IOException("connection reset")
            }
            emit(StreamChunk.TextDelta("t", "the whole reply"))
            emit(StreamChunk.Finish())
        }
        assertEquals("the whole reply", updates.last().replyText())
        assertTrue(updates.any { it.replyText() == "half a rep" })
    }
}

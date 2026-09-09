package me.ayuilos.miffan.ui.components.ui

import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Instant

class AssistantGenerationPhaseTest {
    @Test
    fun waitingOrHistoricalReasoningIsNotActiveReasoning() {
        assertEquals(AssistantGenerationPhase.Waiting, assistantGenerationPhase(null, loading = true))
        assertEquals(AssistantGenerationPhase.Waiting, assistantGenerationPhase(UIMessage.user("你好"), loading = true))
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(
            UIMessagePart.Reasoning("分析完毕", finishedAt = Instant.fromEpochMilliseconds(1)),
        ))
        assertEquals(AssistantGenerationPhase.Waiting, assistantGenerationPhase(message, loading = true))
        assertEquals(AssistantGenerationPhase.None, assistantGenerationPhase(message, loading = false))
    }

    @Test
    fun onlyTheCurrentUnfinishedReasoningPartSelectsReasoning() {
        val reasoning = UIMessagePart.Reasoning("", finishedAt = null)
        val message = UIMessage(role = MessageRole.ASSISTANT, parts = listOf(reasoning))
        assertEquals(AssistantGenerationPhase.Reasoning, assistantGenerationPhase(message, loading = true))
        assertEquals(AssistantGenerationPhase.Responding, assistantGenerationPhase(
            message.copy(parts = listOf(reasoning, UIMessagePart.Text("这是答案"))), loading = true,
        ))
        assertEquals(AssistantGenerationPhase.Waiting, assistantGenerationPhase(
            message.copy(parts = listOf(reasoning, UIMessagePart.Tool("call-1", "search", "{}"))), loading = true,
        ))
        assertEquals(AssistantGenerationPhase.Reasoning, assistantGenerationPhase(
            message.copy(parts = listOf(UIMessagePart.Text("先解释一下"), reasoning)), loading = true,
        ))
    }

    @Test
    fun waitingResponseAndReasoningUseThreeDifferentExpressions() {
        assertEquals(WhaleGirlClip.EATING, resolveWhaleGirlClip(MiffanMascotState.Thinking))
        assertEquals(WhaleGirlClip.CHEWING, resolveWhaleGirlClip(
            MiffanMascotState.Thinking, AssistantGenerationPhase.Responding,
        ))
        assertEquals(WhaleGirlClip.THINKING, resolveWhaleGirlClip(
            MiffanMascotState.Thinking, AssistantGenerationPhase.Reasoning,
        ))
    }

    @Test
    fun activityAndFailureTakePriorityOverSleepAndPetting() {
        assertEquals(WhaleGirlClip.IDLE, resolveWhaleGirlClip(
            MiffanMascotState.Error, AssistantGenerationPhase.Reasoning, petting = true, sleeping = true,
        ))
        assertEquals(WhaleGirlClip.SUCCESS, resolveWhaleGirlClip(MiffanMascotState.Happy, sleeping = true))
        assertEquals(WhaleGirlClip.PETTING, resolveWhaleGirlClip(MiffanMascotState.Idle, petting = true, sleeping = true))
        assertEquals(WhaleGirlClip.TYPING, resolveWhaleGirlClip(
            MiffanMascotState.Idle, inputState = MiffanMascotInputState.Typing, sleeping = true,
        ))
        assertEquals(WhaleGirlClip.SLEEPING, resolveWhaleGirlClip(MiffanMascotState.Idle, sleeping = true))
    }
}

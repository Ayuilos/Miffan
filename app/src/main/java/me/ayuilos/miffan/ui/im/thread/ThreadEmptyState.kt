package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.thread.PARTNER_ADDED_EVENT
import me.ayuilos.miffan.data.thread.TimelineItem
import me.ayuilos.miffan.ui.im.ImChoiceQuiz
import me.ayuilos.miffan.ui.im.QuizAnswer
import me.rerere.ai.core.MessageRole
import me.ayuilos.miffan.ui.components.ui.AssistantGenerationPhase
import me.ayuilos.miffan.ui.context.LocalSettings
import kotlin.uuid.Uuid

@Composable
internal fun ThreadEmptyState(assistant: Assistant?, onSuggestion: (String) -> Unit) {
    val name = threadAssistantName(assistant)
    val settings = LocalSettings.current
    val suggestions = settings.quickMessages.filter { it.id in assistant?.quickMessageIds.orEmpty() && it.content.isNotBlank() }.take(3)
    // System prompts address the model ("You are…"), so the partner greets the user instead.
    val introduction = stringResource(R.string.im_thread_intro, name)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ThreadWelcomeMascot(assistant, AssistantGenerationPhase.None, Modifier.size(168.dp))
        Spacer(Modifier.height(24.dp))
        // The page is not inside a Surface, so the content color must be set here.
        Text(introduction, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground)
        if (suggestions.isNotEmpty()) {
            Spacer(Modifier.height(24.dp))
            ImChoiceQuiz(stringResource(R.string.im_quiz_title), suggestions.map { it.title.ifBlank { it.content } }, answer = null,
                onPick = { onSuggestion(suggestions[it].content) }, modifier = Modifier.widthIn(max = threadBubbleMaxWidth()).fillMaxWidth())
        }
    }
}

/** A partner's first words, and what the owner said after them. */
internal data class ThreadStarter(val greetingId: Uuid, val reply: String?)

/**
 * The hello a newly added partner sent on its own, which the starter quiz hangs under: the first reply
 * after the "partner added" event. [ThreadStarter.reply] is the owner's first message since, if any.
 */
internal fun threadStarter(timeline: List<TimelineItem>): ThreadStarter? {
    val event = timeline.indexOfFirst { it is TimelineItem.Event && it.origin.kind == PARTNER_ADDED_EVENT }
    if (event < 0) return null
    val after = timeline.subList(event + 1, timeline.size).filterIsInstance<TimelineItem.Message>()
    val greeting = after.firstOrNull { it.message.role == MessageRole.ASSISTANT }?.takeIf { !it.streaming } ?: return null
    val reply = after.firstOrNull { it.message.role == MessageRole.USER && it.node.origin == null }
    return ThreadStarter(greeting.message.id, reply?.message?.toText()?.trim())
}

/** What the owner can open with: the partner's quick messages, or three easy openers; label to text sent. */
@Composable
private fun threadStarterOptions(assistant: Assistant?): List<Pair<String, String>> {
    val settings = LocalSettings.current
    val own = settings.quickMessages.filter { it.id in assistant?.quickMessageIds.orEmpty() && it.content.isNotBlank() }.take(3)
    return own.map { it.title.ifBlank { it.content } to it.content }.ifEmpty {
        listOf(R.string.im_thread_starter_intro, R.string.im_thread_starter_help, R.string.im_thread_starter_chat)
            .map { stringResource(it).let { text -> text to text } }
    }
}

/**
 * Ways to answer a new partner's hello, as a quiz under it. Once the owner said anything, it shows
 * whether they picked one of them or wrote their own, and the options can only be looked at.
 */
@Composable
internal fun ThreadStarterQuiz(assistant: Assistant?, starter: ThreadStarter, enabled: Boolean, onPick: (String) -> Unit) {
    val options = threadStarterOptions(assistant)
    val answer = starter.reply?.let { reply ->
        options.indexOfFirst { it.second.trim() == reply }.takeIf { it >= 0 }?.let { QuizAnswer.Picked(it) } ?: QuizAnswer.Replied
    }
    ImChoiceQuiz(stringResource(R.string.im_quiz_title), options.map { it.first }, answer, onPick = { onPick(options[it].second) },
        modifier = Modifier.padding(top = 4.dp).widthIn(max = threadBubbleMaxWidth()).fillMaxWidth(), enabled = enabled)
}

@Composable
internal fun threadAssistantName(assistant: Assistant?): String =
    assistant?.name?.takeIf(String::isNotBlank) ?: stringResource(R.string.assistant_page_default_assistant)

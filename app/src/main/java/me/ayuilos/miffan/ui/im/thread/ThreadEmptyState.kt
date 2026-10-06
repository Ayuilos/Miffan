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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.ui.AssistantGenerationPhase
import me.ayuilos.miffan.ui.context.LocalSettings
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp02

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
            suggestions.forEach { suggestion ->
                FilledTonalButton(onClick = { onSuggestion(suggestion.content) }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text(suggestion.title.ifBlank { suggestion.content }, Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Start)
                    Spacer(Modifier.width(12.dp))
                    Icon(HugeIcons.ArrowUp02, stringResource(R.string.im_thread_send), Modifier.size(20.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.im_thread_suggestions), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun threadAssistantName(assistant: Assistant?): String =
    assistant?.name?.takeIf(String::isNotBlank) ?: stringResource(R.string.assistant_page_default_assistant)

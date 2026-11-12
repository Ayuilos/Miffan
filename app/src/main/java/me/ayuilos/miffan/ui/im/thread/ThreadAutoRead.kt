package me.ayuilos.miffan.ui.im.thread

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import me.ayuilos.miffan.service.ChatService
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.context.LocalTTSState
import me.ayuilos.miffan.utils.forReadingAloud
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val REPLY_SHOWN_TIMEOUT_MS = 3_000L

/**
 * Reads each finished reply aloud while this chat is in front, when the owner turned that on. [replyText] finds a
 * reply of this chat by id; replies in other chats are left alone.
 */
@Composable
internal fun ThreadAutoRead(replyText: (Uuid) -> String?) {
    val chatService = koinInject<ChatService>()
    val tts = LocalTTSState.current
    val display by rememberUpdatedState(LocalSettings.current.displaySetting)
    val findText by rememberUpdatedState(replyText)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(chatService, tts, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            chatService.assistantReplyCompleted.collect { done ->
                if (!display.autoPlayTTSAfterGeneration) return@collect
                // The finished reply reaches the timeline a moment after the service reports it.
                val text = withTimeoutOrNull(REPLY_SHOWN_TIMEOUT_MS) {
                    snapshotFlow { findText(done.messageId) }.filterNotNull().first()
                } ?: return@collect
                val spoken = text.forReadingAloud(display.ttsOnlyReadQuoted, display.ttsOnlyReadOutsideBrackets)
                if (spoken.isNotBlank()) tts.speak(spoken)
            }
        }
    }
}

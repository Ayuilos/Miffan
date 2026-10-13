package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.repository.ConversationRepository
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.utils.base64Decode
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * Shows a conversation-addressed destination (notification, favorite, share, fresh chat) in the IM
 * shell: the owning partner's thread above the home tabs, focused on the requested message.
 */
@Composable
fun ImChatRedirect(key: Screen.Chat, conversations: ConversationRepository = koinInject()) {
    val navigator = LocalNavController.current
    val settings = LocalSettings.current
    LaunchedEffect(key) {
        val conversationId = runCatching { Uuid.parse(key.id) }.getOrNull()
        val assistantId = conversationId?.let { conversations.getAssistantIdOf(it) } ?: settings.assistantId
        val focus = key.messageId ?: key.nodeId?.let { nodeId ->
            conversationId?.let { conversations.getConversationById(it) }
                ?.messageNodes?.firstOrNull { it.id.toString() == nodeId }?.message?.id?.toString()
        }
        // Screen.Chat carries its draft Base64-encoded (see ChatPage); the thread takes plain text.
        val text = key.text?.let { runCatching { it.base64Decode() }.getOrNull() }?.takeIf { it.isNotEmpty() }
        val thread = Screen.Thread(assistantId.toString(), focusMessageId = focus, text = text, files = key.files)
        // Replace this entry only: switching shells may have reset the stack during the lookup.
        navigator.replace(key, thread, root = Screen.Home)
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}

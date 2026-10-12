package me.ayuilos.miffan.ui.pages.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.db.AppDatabase
import me.ayuilos.miffan.data.db.dao.ConversationDAO
import me.ayuilos.miffan.data.db.dao.PartnerChatDAO
import me.ayuilos.miffan.ui.context.LocalNavController
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** Resolve before creating ChatVM: a perspective must never acquire a professional editor. */
internal suspend fun professionalChatDestination(
    key: Screen.Chat,
    conversations: ConversationDAO,
    chats: PartnerChatDAO,
): Screen {
    val chatId = conversations.getChatIdOf(key.id) ?: return key
    if (chats.getChat(chatId) != null) return Screen.PartnerChat(chatId)
    // A deleted target follows the existing missing-conversation behavior (a fresh draft).
    // Use a new id so a surviving orphan perspective cannot be loaded or overwritten.
    return Screen.Chat(Uuid.random().toString())
}

/** Startup restoration is selection, not an explicit request to open a partner chat. */
internal suspend fun professionalStartupDestination(
    key: Screen.Chat,
    conversations: ConversationDAO,
): Screen.Chat {
    if (conversations.getChatIdOf(key.id) == null) return key
    val assistantId = conversations.getAssistantIdOf(key.id)
    val ordinary = assistantId?.let { conversations.getRecentConversationsOfAssistant(it, 1).firstOrNull() }
    return Screen.Chat(ordinary?.id ?: Uuid.random().toString())
}

@Composable
internal fun ProfessionalChatRoute(
    key: Screen.Chat,
    conversations: ConversationDAO = koinInject(),
    chats: PartnerChatDAO = koinInject<AppDatabase>().partnerChatDao(),
    content: @Composable () -> Unit,
) {
    val navigator = LocalNavController.current
    val destination by produceState<Screen?>(initialValue = null, key, conversations, chats) {
        value = professionalChatDestination(key, conversations, chats)
    }
    if (destination == key) {
        content()
    } else {
        LaunchedEffect(destination) {
            destination?.let { navigator.replace(key, it, root = Screen.Chat(Uuid.random().toString()), preserveExistingRoot = true) }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
    }
}

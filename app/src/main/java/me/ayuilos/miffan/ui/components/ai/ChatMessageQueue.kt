package me.ayuilos.miffan.ui.components.ai

import me.ayuilos.miffan.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.service.MessageQueueState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import kotlin.uuid.Uuid

@Composable
internal fun ChatMessageQueue(
    state: MessageQueueState,
    loading: Boolean,
    onRemove: (Uuid) -> Unit,
    onSendImmediately: (Uuid) -> Unit,
    onResume: () -> Unit,
) {
    if (state.messages.isEmpty()) return

    Surface(
        modifier = Modifier.fillMaxWidth().testTag("chat_message_queue"),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        val attachmentMessage = stringResource(R.string.chat_queue_attachment_message)
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(
                        text = stringResource(R.string.chat_queue_title, state.messages.size),
                        style = MaterialTheme.typography.labelLarge,
                    )
                    Text(
                        text = when {
                            state.paused -> stringResource(R.string.chat_queue_paused)
                            loading -> stringResource(R.string.chat_queue_after_reply)
                            else -> stringResource(R.string.chat_queue_waiting)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state.paused) {
                    TextButton(onClick = onResume, enabled = !loading) { Text(stringResource(R.string.chat_queue_resume)) }
                }
            }
            LazyColumn(modifier = Modifier.heightIn(max = 144.dp)) {
                items(state.messages, key = { it.id.toString() }) { message ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        val preview = message.content.filterIsInstance<UIMessagePart.Text>()
                            .joinToString("\n") { it.text }.trim().ifEmpty { attachmentMessage }
                        Text(
                            text = preview,
                            modifier = Modifier.weight(1f),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = { onSendImmediately(message.id) }) { Text(stringResource(R.string.chat_queue_send_now)) }
                        IconButton(
                            onClick = { onRemove(message.id) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.chat_queue_remove),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

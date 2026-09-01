package me.ayuilos.miffan.ui.pages.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.MessageNode
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.GitFork
import kotlin.uuid.Uuid

@Composable
internal fun MessagePathOverview(
    conversation: Conversation,
    onDismiss: () -> Unit,
    onSelectPath: (Uuid) -> Unit,
) {
    val pathLeaves = remember(conversation.messageNodes) { conversation.getMessagePathLeaves() }
    val currentPathLeafId = conversation.currentMessageNodes.lastOrNull()?.id

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = HugeIcons.GitFork,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
                Column {
                    Text(
                        text = stringResource(R.string.chat_page_message_paths),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = stringResource(R.string.chat_page_path_count, pathLeaves.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(
                    items = pathLeaves,
                    key = { _, leaf -> leaf.id },
                ) { index, leaf ->
                    val path = remember(conversation.messageNodes, leaf.id) {
                        conversation.getPathToNode(leaf.id)
                    }
                    MessagePathItem(
                        index = index,
                        path = path,
                        isCurrent = leaf.id == currentPathLeafId,
                        onClick = {
                            onSelectPath(leaf.id)
                            onDismiss()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MessagePathItem(
    index: Int,
    path: List<MessageNode>,
    isCurrent: Boolean,
    onClick: () -> Unit,
) {
    val emptySummary = stringResource(R.string.chat_page_path_non_text_message)
    val preview = remember(path, emptySummary) {
        path.takeLast(3).joinToString("  →  ") { node ->
            node.message.toText()
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifBlank { emptySummary }
                .take(80)
        }
    }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.chat_page_path_label, index + 1),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (isCurrent) {
                    Text(
                        text = stringResource(R.string.chat_page_current_path),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(
                text = preview,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.chat_page_path_message_count, path.size),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

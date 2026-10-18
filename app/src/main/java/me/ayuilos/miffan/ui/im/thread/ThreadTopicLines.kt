package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.TopicStart
import me.ayuilos.miffan.data.thread.TimelineItem
import me.ayuilos.miffan.data.thread.UndoableMerge
import me.rerere.ai.core.MessageRole

/** A centered line between two rules, with inline text actions after its label. */
@Composable
internal fun ThreadDividerLine(text: String, actions: List<Pair<String, () -> Unit>> = emptyList()) {
    val rule = MaterialTheme.colorScheme.outlineVariant
    val link = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold))
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = rule)
        Text(
            buildAnnotatedString {
                append(text)
                actions.forEach { (label, onClick) ->
                    append(" · ")
                    withLink(LinkAnnotation.Clickable(label, link) { onClick() }) { append(label) }
                }
            },
            Modifier.padding(horizontal = 12.dp).widthIn(max = 280.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        HorizontalDivider(Modifier.weight(1f), color = rule)
    }
}

/** Where a topic started on its own; the owner can put it back with an earlier one. */
@Composable
internal fun ThreadTopicBoundaryLine(boundary: TimelineItem.TopicBoundary, onMerge: () -> Unit) {
    if (boundary.start == TopicStart.OWNER) {
        ThreadDividerLine(stringResource(R.string.im_thread_topic_changed))
    } else {
        ThreadDividerLine(stringResource(R.string.im_thread_topic_started),
            if (boundary.mergeable) listOf(stringResource(R.string.im_thread_topic_continue) to onMerge) else emptyList())
    }
}

/** Shown where the moved messages begin until the owner sends again or leaves. */
@Composable
internal fun ThreadMergedLine(merge: UndoableMerge, onUndo: () -> Unit) {
    val text = merge.targetTitle?.let { stringResource(R.string.im_thread_topic_merged, it) }
        ?: stringResource(R.string.im_thread_topic_merged_untitled)
    ThreadDividerLine(text, listOf(stringResource(R.string.im_thread_undo) to onUndo))
}

/** Asks which earlier topic a wrongly split one belongs to. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadMergeSheet(targets: List<ThreadTopic>, partnerName: String, onPick: (ThreadTopic) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.im_thread_merge_title), Modifier.padding(horizontal = 12.dp),
                style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.im_thread_merge_subtitle, partnerName),
                Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 10.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            targets.forEach { topic ->
                val speaker = when (topic.speaker) {
                    MessageRole.USER -> stringResource(R.string.im_thread_merge_you)
                    MessageRole.ASSISTANT -> partnerName
                    else -> null
                }
                val preview = topic.preview.takeIf { it.isNotBlank() }
                    ?.let { text -> speaker?.let { stringResource(R.string.im_thread_merge_speaker, it, text) } ?: text }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(topic) }
                    .padding(horizontal = 12.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.padding(top = 7.dp).size(10.dp).background(threadTopicColor(topic.topicIndex), CircleShape))
                    Column(Modifier.weight(1f)) {
                        Text(topic.title ?: preview.orEmpty(), style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (topic.title != null && preview != null) Text(preview, Modifier.padding(top = 2.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(threadLastChatTime(topic.updateAt), Modifier.padding(top = 3.dp),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
                Text(stringResource(R.string.im_thread_merge_keep))
            }
        }
    }
}

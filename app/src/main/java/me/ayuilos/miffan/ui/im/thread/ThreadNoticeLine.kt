package me.ayuilos.miffan.ui.im.thread

import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Alert02
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.thread.ThreadNotice
import me.ayuilos.miffan.data.thread.ThreadNoticeKind

@Composable
internal fun ThreadNoticeLine(notice: ThreadNotice, name: String, onView: () -> Unit, onUndo: () -> Unit) {
    val summary = stringResource(
        when (notice.kind) {
            ThreadNoticeKind.SETTINGS -> R.string.im_thread_notice_settings
            ThreadNoticeKind.MEMORY -> R.string.im_thread_notice_memory
            ThreadNoticeKind.MEMORY_FORGOTTEN -> R.string.im_thread_notice_memory_forgotten
        },
        name, notice.summary,
    )
    val view = stringResource(R.string.im_thread_view)
    val undo = stringResource(R.string.im_thread_undo)
    val linkStyle = TextLinkStyles(style = SpanStyle(color = MaterialTheme.colorScheme.primary))
    Text(
        buildAnnotatedString {
            append(summary)
            append(" · ")
            withLink(LinkAnnotation.Clickable("view", linkStyle) { onView() }) { append(view) }
            if (notice.undoable) {
                append(" · ")
                withLink(LinkAnnotation.Clickable("undo", linkStyle) { onUndo() }) { append(undo) }
            }
        },
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/** What started a turn the owner did not start, such as another partner's late reply. */
@Composable
internal fun ThreadEventLine(summary: String) {
    Text(
        summary,
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

/**
 * A scheduled task's results arriving in the chat: one line per task with its outcome, opening the
 * task. Times say when it ran and when it reached the phone, so late results never look new.
 */
@Composable
internal fun ThreadTaskRunLine(summary: String, failed: Boolean, onOpen: (() -> Unit)?) {
    androidx.compose.material3.Surface(onClick = { onOpen?.invoke() }, enabled = onOpen != null,
        color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
        androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)) {
            androidx.compose.material3.Icon(
                if (failed) me.rerere.hugeicons.HugeIcons.Alert02 else me.rerere.hugeicons.HugeIcons.CheckmarkCircle02, null,
                Modifier.size(18.dp), tint = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(summary, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (onOpen != null) androidx.compose.material3.Icon(me.rerere.hugeicons.HugeIcons.ArrowRight01, null, Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

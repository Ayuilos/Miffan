package me.ayuilos.miffan.ui.im.thread

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
        if (notice.kind == ThreadNoticeKind.MEMORY) R.string.im_thread_notice_memory else R.string.im_thread_notice_settings,
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

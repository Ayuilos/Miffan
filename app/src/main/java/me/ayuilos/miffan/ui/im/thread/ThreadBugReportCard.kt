package me.ayuilos.miffan.ui.im.thread

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.bugreport.buildBugReportIssueUrl
import me.ayuilos.miffan.data.bugreport.deviceDiagnostics
import me.ayuilos.miffan.data.bugreport.parseBugReportDraft
import me.ayuilos.miffan.data.bugreport.toMarkdown
import me.ayuilos.miffan.data.bugreport.withEnvironment
import me.ayuilos.miffan.data.model.isImMode
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.utils.openUrl
import me.rerere.ai.ui.UIMessagePart

internal const val APP_DIAGNOSTICS = "app_diagnostics"
internal const val DRAFT_BUG_REPORT = "draft_bug_report"

/**
 * The report exactly as it will leave the app: redacted, with this device filled in. Opening GitHub and
 * approving happen together, so the owner reviews and submits there with their own account.
 */
@Composable
internal fun ThreadBugReportCard(tool: UIMessagePart.Tool, onApproval: (String, Boolean) -> Unit) {
    val context = LocalContext.current
    val imMode = LocalSettings.current.isImMode
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    // A malformed draft still reaches the tool on approval, which tells the partner what to fix.
    val draft = remember(tool.input, imMode) {
        runCatching { parseBugReportDraft(tool.inputAsJson()).withEnvironment(deviceDiagnostics(context, imMode)).sanitized() }.getOrNull()
    }
    var answered by remember(tool.toolCallId) { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.im_bug_report_title), style = MaterialTheme.typography.titleMedium)
            if (draft == null) {
                Text(stringResource(R.string.im_p5_permission_info), color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(draft.title, fontWeight = FontWeight.Medium)
                ChangeLongText(stringResource(R.string.im_bug_report_description), draft.description)
                ChangeLongText(stringResource(R.string.im_bug_report_actual), draft.actual)
                ChangeLongText(stringResource(R.string.im_bug_report_expected), draft.expected)
                draft.logs?.takeIf { it.isNotBlank() }?.let { ChangeLongText(stringResource(R.string.im_bug_report_logs), it) }
                val environment = listOf(draft.provider, draft.model, draft.app_version, draft.android_version, draft.device_model)
                    .mapNotNull { it?.takeIf(String::isNotBlank) }
                ChangeField(stringResource(R.string.im_bug_report_environment)) {
                    Text(environment.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(stringResource(R.string.im_bug_report_public), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            RelayApprovalNotice(tool)
            Button(modifier = Modifier.fillMaxWidth(), enabled = !answered, onClick = {
                answered = true
                draft?.let { runCatching { buildBugReportIssueUrl(it) }.getOrNull() }?.let(context::openUrl)
                onApproval(tool.toolCallId, true)
            }) { Text(stringResource(R.string.im_bug_report_submit)) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(modifier = Modifier.weight(1f), enabled = draft != null, onClick = {
                    val text = draft?.let { runCatching { it.toMarkdown() }.getOrNull() } ?: return@OutlinedButton
                    scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", text))) }
                }) { Text(stringResource(R.string.im_bug_report_copy)) }
                OutlinedButton(modifier = Modifier.weight(1f), enabled = !answered, onClick = {
                    answered = true
                    onApproval(tool.toolCallId, false)
                }) { Text(stringResource(R.string.im_bug_report_skip)) }
            }
        }
    }
}

/** The settled draft, with a way back to GitHub in case the owner closed the page before submitting. */
@Composable
internal fun ThreadBugReportRecord(tool: UIMessagePart.Tool) {
    val context = LocalContext.current
    val url = remember(tool.output) {
        tool.output.filterIsInstance<UIMessagePart.Text>().firstNotNullOfOrNull { part ->
            runCatching { (Json.parseToJsonElement(part.text) as? JsonObject)?.get("issue_url") as? JsonPrimitive }
                .getOrNull()?.contentOrNull
        }
    }
    Column {
        ThreadPermissionRecord(tool)
        url?.let {
            TextButton(onClick = { context.openUrl(it) }) { Text(stringResource(R.string.im_bug_report_reopen)) }
        }
    }
}

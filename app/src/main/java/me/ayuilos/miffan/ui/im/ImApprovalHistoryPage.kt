package me.ayuilos.miffan.ui.im

import android.text.format.DateUtils
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.audit.AuditEvent
import me.ayuilos.miffan.data.audit.AuditKind
import me.ayuilos.miffan.ui.components.message.approvalDecisionText
import me.ayuilos.miffan.ui.components.message.auditTime
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.im.computer.rememberPartnerComputer
import me.ayuilos.miffan.data.audit.AuditRepository
import me.rerere.ai.ui.ToolDecision
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.ComputerTerminal01
import me.rerere.hugeicons.stroke.ComputerVideo
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.Wrench01
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private const val HISTORY_LIMIT = 500

/**
 * Every permission decision for this partner, newest first and grouped by day: what it asked to do,
 * how it was settled, and screen takeovers on its computer. Rows open the conversation they came from.
 */
@Composable
fun ImApprovalHistoryPage(assistantId: Uuid) {
    val nav = LocalNavController.current
    val audit: AuditRepository = koinInject()
    val computer by rememberPartnerComputer(assistantId)
    val hostIds = remember(computer?.hostId) { setOfNotNull(computer?.hostId) }
    val events by remember(audit, assistantId, hostIds) { audit.observe(assistantId, hostIds, HISTORY_LIMIT) }
        .collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    val zone = remember { ZoneId.systemDefault() }
    val days = remember(events) {
        events.orEmpty().groupBy { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() }.toList()
    }
    Scaffold(topBar = {
        ImPageBar(stringResource(R.string.im_audit_title)) {
            if (!events.isNullOrEmpty()) IconButton(onClick = { confirmClear = true }) {
                Icon(HugeIcons.Delete01, stringResource(R.string.im_audit_clear))
            }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("intro") {
                Text(stringResource(R.string.im_audit_intro), Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (events?.isEmpty() == true) item("empty") { ImEmpty(stringResource(R.string.im_audit_empty)) }
            days.forEach { (day, dayEvents) ->
                item("day-$day") { AuditDayHeader(day) }
                item("events-$day") {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
                        Column {
                            dayEvents.forEachIndexed { index, event ->
                                if (index > 0) HorizontalDivider(Modifier.padding(start = 56.dp, end = 16.dp))
                                AuditRow(event, onOpen = openSource(event)?.let { target -> { nav.navigate(target) } })
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.im_audit_clear_title)) },
        text = { Text(stringResource(R.string.im_audit_clear_help)) },
        confirmButton = {
            TextButton(onClick = { confirmClear = false; scope.launch { audit.clear(assistantId) } }) {
                Text(stringResource(R.string.im_audit_clear), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.common_cancel)) } })
}

/** The message a decision belongs to; screen events and deleted partners have none. */
private fun openSource(event: AuditEvent): Screen? {
    val assistantId = event.assistantId ?: return null
    return Screen.Thread(assistantId, focusMessageId = event.messageId)
}

@Composable
private fun AuditDayHeader(day: LocalDate) {
    val context = LocalContext.current
    val millis = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val label = if (DateUtils.isToday(millis)) stringResource(R.string.im_audit_today)
    else DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY)
    Text(label, Modifier.padding(start = 4.dp, top = 12.dp), style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AuditRow(event: AuditEvent, onOpen: (() -> Unit)?) {
    val context = LocalContext.current
    val (icon, title, outcome) = when (event.kind) {
        AuditKind.SCREEN_TAKEN_OVER, AuditKind.SCREEN_HANDED_BACK -> Triple(
            HugeIcons.ComputerVideo,
            event.hostName?.let { stringResource(R.string.im_audit_screen_of, it) } ?: stringResource(R.string.im_computer_computer),
            stringResource(if (event.kind == AuditKind.SCREEN_TAKEN_OVER) R.string.audit_screen_taken else R.string.audit_screen_back,
                auditTime(context, event.at)),
        )
        else -> Triple(
            auditIcon(event.toolName),
            event.summary,
            event.decision?.let { approvalDecisionText(it, event.via, event.at) } ?: auditTime(context, event.at),
        )
    }
    // The host is part of the action: the same command means something else on another machine.
    val host = event.hostName?.takeIf { event.kind == AuditKind.APPROVAL && !event.summary.contains(it) }
    val declined = event.decision == ToolDecision.DECLINED
    val content = @Composable {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.Top) {
            Icon(icon, null, Modifier.padding(top = 2.dp).size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(outcome, host).joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                    color = if (declined) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (onOpen != null) Surface(onClick = onOpen, color = Color.Transparent) { content() } else content()
}

private fun auditIcon(toolName: String?): ImageVector = when {
    toolName == null -> HugeIcons.Wrench01
    toolName.startsWith("computer_") -> HugeIcons.Computer
    toolName.startsWith("workspace_") -> HugeIcons.ComputerTerminal01
    else -> HugeIcons.Wrench01
}

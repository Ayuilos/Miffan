package me.ayuilos.miffan.ui.im.thread

import android.content.ContentUris
import android.content.Intent
import android.provider.CalendarContract
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.local.parseCalendarTime
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.im.partnerName
import me.rerere.ai.ui.UIMessagePart
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle

internal const val CALENDAR_CREATE = "calendar_create"

/** The event a calendar_create call asks for, read the way the tool will read it. */
private data class CalendarRequest(val title: String, val start: ZonedDateTime, val end: ZonedDateTime, val allDay: Boolean,
    val location: String?, val notes: String?)

private fun calendarRequest(tool: UIMessagePart.Tool): CalendarRequest? = runCatching {
    val zone = ZoneId.systemDefault()
    val allDay = toolArg(tool, "all_day")?.toBooleanStrictOrNull() ?: false
    val start = parseCalendarTime(requireNotNull(toolArg(tool, "start")), zone)
    val end = toolArg(tool, "end")?.let { parseCalendarTime(it, zone) }
        ?: if (allDay) start.toLocalDate().plusDays(1).atStartOfDay(zone) else start.plusHours(1)
    CalendarRequest(toolArg(tool, "title").orEmpty(), start, end, allDay, toolArg(tool, "location"), toolArg(tool, "description"))
}.getOrNull()

/** The event a finished calendar_create call added, or null when it failed. */
internal data class AddedCalendarEvent(val id: Long, val title: String, val start: ZonedDateTime, val end: ZonedDateTime, val allDay: Boolean)

internal fun UIMessagePart.Tool.addedCalendarEvent(): AddedCalendarEvent? {
    if (toolName != CALENDAR_CREATE || !isExecuted) return null
    val text = output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: return null
    return runCatching {
        val result = Json.parseToJsonElement(text) as JsonObject
        if ((result["success"] as? JsonPrimitive)?.booleanOrNull != true) return null
        val zone = ZoneId.systemDefault()
        fun str(key: String) = (result[key] as? JsonPrimitive)?.contentOrNull
        // The tool reports ZonedDateTime.toString(), which names the zone ("…+08:00[Asia/Shanghai]").
        fun time(key: String) = str(key)!!.let { runCatching { ZonedDateTime.parse(it) }.getOrNull() ?: parseCalendarTime(it, zone) }
        AddedCalendarEvent(
            id = (result["event_id"] as JsonPrimitive).longOrNull ?: return null,
            title = str("title").orEmpty(),
            start = time("start"),
            end = time("end"),
            allDay = (result["all_day"] as? JsonPrimitive)?.booleanOrNull == true,
        )
    }.getOrNull()
}

@Composable
private fun timeRange(start: ZonedDateTime, end: ZonedDateTime, allDay: Boolean): String {
    if (allDay) return stringResource(R.string.im_calendar_all_day)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(LocalConfiguration.current.locales[0])
    return "${start.format(time)} – ${end.format(time)}"
}

@Composable
private fun CalendarDateTile(date: LocalDate, size: Dp = 56.dp) {
    val locale = LocalConfiguration.current.locales[0]
    val month = date.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMM"), locale))
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium, modifier = Modifier.width(size)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(color = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary) {
                Text(month, Modifier.fillMaxWidth().padding(vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Text(date.dayOfMonth.toString(), style = if (size >= 56.dp) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold)
            if (size >= 56.dp) Text(date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale), Modifier.padding(bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The event the partner wants to add, as a calendar entry. The owner can move it before agreeing;
 * the corrected time is what the partner's call then carries.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ThreadCalendarCard(tool: UIMessagePart.Tool, assistant: Assistant?, onApproval: (String, Boolean) -> Unit) {
    val request = remember(tool.input) { calendarRequest(tool) }
    val who = assistant?.let { partnerName(it) } ?: stringResource(R.string.im_approval_partner)
    if (request == null) {
        ThreadGenericApprovalCard(tool, assistant, onApproval)
        return
    }
    val actions = LocalToolCardActions.current
    var start by remember(tool.toolCallId) { mutableStateOf(request.start) }
    val duration = remember(request) { java.time.Duration.between(request.start, request.end) }
    val end = start.plus(duration)
    val edited = start != request.start
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }

    ApprovalCardFrame(tool, stringResource(R.string.im_calendar_title, who), onApproval,
        allowLabel = stringResource(R.string.im_calendar_add),
        onAllow = { always ->
            when {
                edited && actions != null -> {
                    if (always) actions.grantStanding(CALENDAR_CREATE)
                    actions.approveWithInput(tool.toolCallId, calendarInput(tool, start, end, request.allDay))
                }
                always && actions != null -> actions.alwaysAllow(tool.toolCallId)
                else -> onApproval(tool.toolCallId, true)
            }
        }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            CalendarDateTile(start.toLocalDate())
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(request.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(timeRange(start, end, request.allDay), style = MaterialTheme.typography.bodyMedium)
            }
            // Correcting the time needs to send new arguments, which only the owner's own chats can.
            if (actions != null) TextButton(onClick = { pickDate = true }) { Text(stringResource(R.string.im_calendar_change_time)) }
        }
        ApprovalFields(listOfNotNull(
            request.location?.let { stringResource(R.string.im_calendar_location) to @Composable { FieldText(it) } },
            request.notes?.let { stringResource(R.string.im_calendar_notes) to @Composable { FieldText(it, maxLines = 2) } },
        ))
        if (edited) Text(stringResource(R.string.im_calendar_edited), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
    }

    if (pickDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = start.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        start = start.with(date)
                    }
                    pickDate = false
                    if (!request.allDay) pickTime = true
                }) { Text(stringResource(R.string.im_calendar_next)) }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text(stringResource(R.string.common_cancel)) } }) {
            DatePicker(state)
        }
    }
    if (pickTime) {
        val state = rememberTimePickerState(start.hour, start.minute,
            android.text.format.DateFormat.is24HourFormat(LocalContext.current))
        AlertDialog(onDismissRequest = { pickTime = false },
            confirmButton = {
                TextButton(onClick = {
                    start = start.with(LocalTime.of(state.hour, state.minute))
                    pickTime = false
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = { TextButton(onClick = { pickTime = false }) { Text(stringResource(R.string.common_cancel)) } },
            text = { TimePicker(state) })
    }
}

/** The call's own arguments with the corrected time, in the local formats the tool accepts. */
private fun calendarInput(tool: UIMessagePart.Tool, start: ZonedDateTime, end: ZonedDateTime, allDay: Boolean): JsonObject {
    val original = tool.inputAsJson() as? JsonObject ?: JsonObject(emptyMap())
    fun format(time: ZonedDateTime) = if (allDay) time.toLocalDate().toString()
        else LocalDateTime.of(time.toLocalDate(), time.toLocalTime().withNano(0)).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    return JsonObject(original + mapOf("start" to JsonPrimitive(format(start)), "end" to JsonPrimitive(format(end))))
}

/** An event the partner added, as it now sits in the calendar; tapping "open" shows it there. */
@Composable
internal fun ThreadCalendarEvent(event: AddedCalendarEvent) {
    val context = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CalendarDateTile(event.start.toLocalDate(), size = 44.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(event.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.im_calendar_added, timeRange(event.start, event.end, event.allDay)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW,
                        ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.id)))
                }
            }) { Text(stringResource(R.string.im_calendar_open)) }
        }
    }
}

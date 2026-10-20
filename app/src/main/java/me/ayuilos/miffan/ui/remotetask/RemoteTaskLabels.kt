package me.ayuilos.miffan.ui.remotetask

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.remotetask.AgentToolPresets
import me.ayuilos.miffan.data.remotetask.TaskNotify
import me.ayuilos.miffan.data.remotetask.TaskSchedule
import me.ayuilos.miffan.data.remotetask.nextRun
import me.ayuilos.miffan.data.remotetask.toDayOfWeek

/** "Every day 09:00", "Every Mon, Wed 18:30", "Every 30 minutes". */
@Composable
fun scheduleLabel(schedule: TaskSchedule): String = when (schedule) {
    is TaskSchedule.Daily -> stringResource(R.string.remote_task_daily, clock(schedule.hour, schedule.minute))
    is TaskSchedule.Weekly -> {
        val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        val separator = stringResource(R.string.im_computers_name_separator)
        val days = schedule.days.sortedBy { if (it == 0) 7 else it }
            .joinToString(separator) { it.toDayOfWeek().getDisplayName(TextStyle.SHORT, locale) }
        stringResource(R.string.remote_task_weekly, days, clock(schedule.hour, schedule.minute))
    }
    is TaskSchedule.Interval -> when {
        schedule.minutes < 60 -> stringResource(R.string.remote_task_interval_minutes, schedule.minutes)
        schedule.minutes == 60 -> stringResource(R.string.remote_task_interval_hour)
        else -> stringResource(R.string.remote_task_interval_hours, schedule.minutes / 60)
    }
}

/** The schedule line the helper stores, e.g. `daily 09:00`; shown raw if it cannot be read. */
@Composable
fun scheduleLabel(helperLine: String): String =
    TaskSchedule.parseHelper(helperLine)?.let { scheduleLabel(it) } ?: helperLine

/**
 * Says whose clock the times are on. When the computer's zone differs from the phone's, names the
 * next run in phone time too, so "09:00" is never misread.
 */
@Composable
fun zoneNote(schedule: TaskSchedule, computerZone: ZoneId?): String {
    val phone = ZoneId.systemDefault()
    val zone = computerZone ?: return stringResource(R.string.remote_task_zone_computer)
    val now = ZonedDateTime.now(zone)
    if (zone.rules.getOffset(now.toInstant()) == phone.rules.getOffset(now.toInstant())) {
        return stringResource(R.string.remote_task_zone_same)
    }
    val next = schedule.nextRun(now) ?: return stringResource(R.string.remote_task_zone_named, zone.id)
    val local = next.withZoneSameInstant(phone)
    return stringResource(R.string.remote_task_zone_converted, zone.id, dayAndTime(local))
}

@Composable
fun permissionLabel(name: String?): String? = when (name) {
    null -> null
    AgentToolPresets.READ_ONLY -> stringResource(R.string.remote_task_permission_read_only)
    AgentToolPresets.WORKSPACE_WRITE -> stringResource(R.string.remote_task_permission_write)
    else -> name
}

@Composable
fun notifyLabel(notify: TaskNotify, partner: String?): String {
    val who = partner ?: stringResource(R.string.im_approval_partner)
    return when (notify) {
        TaskNotify.RECORD -> stringResource(R.string.remote_task_notify_record)
        TaskNotify.FAILURE -> stringResource(R.string.remote_task_notify_failure, who)
        TaskNotify.ALWAYS -> stringResource(R.string.remote_task_notify_always, who)
    }
}

/** ok, failed, timeout and skipped_overlap in words. */
@Composable
fun runStatusLabel(status: String): String = when (status) {
    "ok" -> stringResource(R.string.remote_task_status_ok)
    "timeout" -> stringResource(R.string.remote_task_status_timeout)
    "skipped_overlap" -> stringResource(R.string.remote_task_status_skipped)
    else -> stringResource(R.string.remote_task_status_failed)
}

fun isRunFailure(status: String): Boolean = status != "ok" && status != "skipped_overlap"

/** "Today 09:00", "Yesterday 03:00", or a short date with the time. */
@Composable
fun dayAndTime(time: ZonedDateTime): String {
    val today = LocalDate.now(time.zone)
    val clock = clock(time.hour, time.minute)
    return when (time.toLocalDate()) {
        today -> stringResource(R.string.remote_task_today_at, clock)
        today.minusDays(1) -> stringResource(R.string.remote_task_yesterday_at, clock)
        else -> time.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)) + " " + clock
    }
}

@Composable
fun dayAndTime(epochMillis: Long): String = dayAndTime(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

fun clock(hour: Int, minute: Int): String = "%02d:%02d".format(hour, minute)

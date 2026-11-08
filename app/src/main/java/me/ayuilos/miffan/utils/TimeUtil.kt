package me.ayuilos.miffan.utils

import android.text.format.DateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

fun Instant.toLocalDate(): String {
    val zoneId = ZoneId.systemDefault()
    val localDateTime = this.atZone(zoneId).toLocalDateTime()

    return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(localDateTime)
}

fun Instant.toLocalDateTime(): String {
    val zoneId = ZoneId.systemDefault()
    val localDateTime = this.atZone(zoneId).toLocalDateTime()

    return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(localDateTime)
}

fun Instant.toLocalTime(): String {
    val zoneId = ZoneId.systemDefault()
    val localDateTime = this.atZone(zoneId).toLocalDateTime()

    return DateTimeFormatter.ofLocalizedTime(FormatStyle.MEDIUM)
        .withLocale(Locale.getDefault())
        .format(localDateTime)
}

fun LocalDateTime.toLocalString(): String {
    val locale = Locale.getDefault()
    val formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale)
    return formatter.format(this)
}

/**
 * 消息时间显示：当天只显示时间（如 14:30），非当天显示「月日 + 时间」（如 5月20日 14:30）。
 */
fun LocalDateTime.toMessageTimeString(): String {
    val locale = Locale.getDefault()
    return if (this.toLocalDate() == LocalDate.now()) {
        DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(this)
    } else {
        this.toLocalString()
    }
}

fun LocalDate.toLocalString(includeYear: Boolean): String {
    val locale = Locale.getDefault()
    val formatter = if (includeYear) {
        cachedFormatter(locale, "date-medium") { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(it) }
    } else {
        // The locale's own month-day pattern: 10月31日, Oct 31, 31 окт., 10월 31일.
        cachedFormatter(locale, "MMMd") {
            DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(it, "MMMd"), it)
        }
    }
    return formatter.format(this)
}

/** The locale's short time of day (14:30, 2:30 PM), for list rows that format many times per frame. */
fun shortTimeFormatter(locale: Locale = Locale.getDefault()): DateTimeFormatter =
    cachedFormatter(locale, "time-short") { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(it) }

private val formatters = ConcurrentHashMap<Pair<Locale, String>, DateTimeFormatter>()

private fun cachedFormatter(locale: Locale, key: String, build: (Locale) -> DateTimeFormatter): DateTimeFormatter =
    formatters.getOrPut(locale to key) { build(locale) }

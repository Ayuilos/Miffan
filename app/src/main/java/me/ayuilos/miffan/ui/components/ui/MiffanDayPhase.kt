package me.ayuilos.miffan.ui.components.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import me.ayuilos.miffan.BuildConfig
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

enum class MiffanDayPhase {
    Morning,
    Noon,
    Night,
}

object MiffanDayPhaseDebugOverride {
    private val mutablePhase = MutableStateFlow<MiffanDayPhase?>(null)
    val phase = mutablePhase.asStateFlow()

    fun set(value: MiffanDayPhase?) {
        mutablePhase.value = value
    }
}

@Composable
fun rememberMiffanDayPhase(): MiffanDayPhase {
    val actualPhase by produceState(initialValue = miffanDayPhaseAt(LocalTime.now())) {
        while (true) {
            val now = ZonedDateTime.now()
            value = miffanDayPhaseAt(now.toLocalTime())
            delay(millisUntilNextPhase(now))
        }
    }
    val debugOverride by MiffanDayPhaseDebugOverride.phase.collectAsState()
    return if (BuildConfig.DEBUG) debugOverride ?: actualPhase else actualPhase
}

internal fun miffanDayPhaseAt(time: LocalTime): MiffanDayPhase = when (time.hour) {
    in 5..10 -> MiffanDayPhase.Morning
    in 11..17 -> MiffanDayPhase.Noon
    else -> MiffanDayPhase.Night
}

private fun millisUntilNextPhase(now: ZonedDateTime): Long {
    val next = when (miffanDayPhaseAt(now.toLocalTime())) {
        MiffanDayPhase.Morning -> now.withHour(11).withMinute(0).withSecond(0).withNano(0)
        MiffanDayPhase.Noon -> now.withHour(18).withMinute(0).withSecond(0).withNano(0)
        MiffanDayPhase.Night -> {
            if (now.hour < 5) {
                now.withHour(5).withMinute(0).withSecond(0).withNano(0)
            } else {
                now.plusDays(1).withHour(5).withMinute(0).withSecond(0).withNano(0)
            }
        }
    }
    return Duration.between(now, next).toMillis().coerceAtLeast(1_000L)
}

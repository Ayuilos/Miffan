package me.ayuilos.miffan.ui.components.ui

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** Posed beats with eased holds, sampled from the existing foreground clock. */
internal data class WhaleActing(
    val foodReach: Float = 0f,
    val mouthOpen: Float = 0f,
    val riceAmount: Float = 0f,
    val cheekLeft: Float = 0f,
    val cheekRight: Float = 0f,
    val swallow: Float = 0f,
    val breath: Float = 0f,
    val tilt: Float = 0f,
    val lift: Float = 0f,
    val alert: Float = 0f,
)

private fun beat(t: Float, vararg keys: Pair<Float, Float>): Float {
    val right = keys.indexOfFirst { it.first >= t }
    if (right <= 0) return keys[if (right == 0) 0 else keys.lastIndex].second
    val (a, start) = keys[right - 1]
    val (b, end) = keys[right]
    val u = ((t - a) / (b - a)).coerceIn(0f, 1f)
    return start + (end - start) * u * u * (3 - 2 * u)
}

// Treat sub-microsecond float preview rounding as the exact loop boundary.
private fun cyclePhase(seconds: Double, period: Double): Float {
    val t = (seconds % period / period).toFloat()
    return if (t < .000001f || t > .999999f) 0f else t
}

internal fun whaleActing(clip: WhaleGirlClip, seconds: Double): WhaleActing = when (clip) {
    WhaleGirlClip.EATING -> {
        val t = cyclePhase(seconds, 3.6)
        val reach = beat(t, 0f to 0f, .1f to 0f, .34f to 1f, .45f to 1f, .66f to 0f, 1f to 0f)
        WhaleActing(
            foodReach = reach,
            mouthOpen = beat(t, 0f to 0f, .12f to 0f, .28f to 1f, .38f to 1f,
                .47f to 0f, .57f to .12f, .64f to 0f, 1f to 0f),
            riceAmount = beat(t, 0f to 1f, .36f to 1f, .44f to 0f, .86f to 0f, .96f to 1f, 1f to 1f),
            swallow = beat(t, 0f to 0f, .65f to 0f, .73f to 1f, .84f to 0f, 1f to 0f),
            lift = reach * 5f - beat(t, 0f to 0f, .45f to 0f, .52f to 3f, .65f to 0f, 1f to 0f),
            tilt = -reach * 2.2f,
        )
    }
    WhaleGirlClip.CHEWING -> {
        val t = cyclePhase(seconds, 3.2)
        val volume = beat(t, 0f to .8f, .12f to 1f, .62f to .9f, .76f to .15f, .86f to .15f, 1f to .8f)
        val side = sin(t * PI * 6).toFloat()
        WhaleActing(
            cheekLeft = volume * (20f + side * 11f),
            cheekRight = volume * (20f - side * 11f),
            swallow = beat(t, 0f to 0f, .68f to 0f, .78f to 1f, .9f to 0f, 1f to 0f),
            lift = volume * (1f - cos(t * PI * 12).toFloat()) * 2.8f,
        )
    }
    WhaleGirlClip.THINKING -> {
        val t = cyclePhase(seconds, 2.0)
        WhaleActing(tilt = sin(t * PI * 2).toFloat() * 5.5f,
            lift = (1f - cos(t * PI * 4).toFloat()) * 3f)
    }
    WhaleGirlClip.SLEEPING -> {
        val t = cyclePhase(seconds, 5.6)
        val breath = beat(t, 0f to 0f, .42f to 1f, .54f to 1f, .94f to 0f, 1f to 0f)
        WhaleActing(breath = breath, lift = breath * 4f,
            tilt = beat(t, 0f to 0f, .44f to -1.2f, .7f to 2.2f, 1f to 0f))
    }
    WhaleGirlClip.SURPRISE -> {
        val t = seconds.toFloat().coerceAtMost(1.5f)
        val spring = (sin(t * 15) * exp(-t * 4)).toFloat()
        WhaleActing(alert = 1f + spring * .16f, lift = 5f + spring * 8f, tilt = spring * -2f)
    }
    else -> WhaleActing()
}

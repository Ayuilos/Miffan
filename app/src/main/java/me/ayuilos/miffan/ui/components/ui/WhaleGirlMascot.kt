package me.ayuilos.miffan.ui.components.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import me.ayuilos.miffan.R

/** Semantic adapter for the native two-color character. */
@Composable
fun WhaleGirlMascot(
    state: MiffanMascotState,
    modifier: Modifier = Modifier,
    reducedMotion: Boolean = false,
    presentation: MiffanPresentation = MiffanPresentation.Scene,
    interactive: Boolean = false,
    attentionTarget: Offset? = null,
    attentionId: Int = 0,
    inputState: MiffanMascotInputState = MiffanMascotInputState.Inactive,
    submitId: Int = 0,
    dayPhase: MiffanDayPhase = MiffanDayPhase.Noon,
    generationPhase: AssistantGenerationPhase = AssistantGenerationPhase.None,
) {
    val systemReduced = rememberMiffanReducedMotion()
    val motionReduced = reducedMotion || systemReduced
    var pokeId by remember { mutableIntStateOf(0) }
    var petting by remember { mutableStateOf(false) }
    var sleeping by remember { mutableStateOf(false) }
    var hasBeenActive by remember { mutableStateOf(false) }

    LaunchedEffect(attentionId) {
        if (attentionId != 0 && attentionTarget != null) pokeId++
    }
    LaunchedEffect(pokeId) {
        if (pokeId == 0) return@LaunchedEffect
        petting = state != MiffanMascotState.Thinking && state != MiffanMascotState.Happy &&
            state != MiffanMascotState.Error
    }
    LaunchedEffect(state) {
        if (state == MiffanMascotState.Thinking || state == MiffanMascotState.Happy ||
            state == MiffanMascotState.Error) petting = false
    }
    LaunchedEffect(state, inputState, pokeId, attentionId, submitId, presentation, dayPhase) {
        sleeping = false
        if (state != MiffanMascotState.Idle || inputState != MiffanMascotInputState.Inactive ||
            pokeId != 0 || attentionId != 0 || submitId != 0) hasBeenActive = true
        if (presentation != MiffanPresentation.Scene || state != MiffanMascotState.Idle ||
            inputState != MiffanMascotInputState.Inactive) return@LaunchedEffect
        // Night may start asleep. Any activity wakes her and starts a fresh inactivity interval.
        if (dayPhase != MiffanDayPhase.Night || hasBeenActive) delay(WHALE_SLEEP_AFTER_MILLIS)
        sleeping = true
    }

    val clip = resolveWhaleGirlClip(state, generationPhase, inputState, petting, sleeping)
    val playing = state != MiffanMascotState.Error &&
        (presentation == MiffanPresentation.Scene || state != MiffanMascotState.Idle || petting ||
            inputState != MiffanMascotInputState.Inactive)
    val description = if (state == MiffanMascotState.Error) "蓝色大肥鱼，遇到了问题" else when (clip) {
        WhaleGirlClip.IDLE -> "蓝色大肥鱼"
        WhaleGirlClip.PETTING -> "蓝色大肥鱼，正在被摸摸"
        WhaleGirlClip.SUCCESS -> "蓝色大肥鱼，开心"
        WhaleGirlClip.SURPRISE -> "蓝色大肥鱼，有可用更新"
        WhaleGirlClip.EATING -> "蓝色大肥鱼，正在等回复"
        WhaleGirlClip.CHEWING -> "蓝色大肥鱼，正在回复"
        WhaleGirlClip.THINKING -> "蓝色大肥鱼，正在推理"
        WhaleGirlClip.SLEEPING -> "蓝色大肥鱼，睡着了"
    }
    val touch = if (interactive) Modifier.pointerInput(Unit) {
        detectTapGestures { pokeId++ }
    } else Modifier
    val colors = if (MaterialTheme.colorScheme.background.luminance() < .5f)
        WhaleLinePalette.Night else WhaleLinePalette.Day
    Box(modifier.then(touch).semantics {
        contentDescription = description
        if (interactive) {
            role = Role.Button
            onClick(label = "摸摸蓝色大肥鱼") { pokeId++; true }
        }
    }) {
        WhaleGirlAnimatedPortrait(
            clip = clip,
            playing = playing,
            posterResourceId = whaleGirlPoster(clip),
            reducedMotion = motionReduced,
            replayId = if (clip == WhaleGirlClip.PETTING) pokeId else 0,
            onPlaybackFinished = { if (clip == WhaleGirlClip.PETTING) petting = false },
            modifier = Modifier.align(Alignment.Center).aspectRatio(1f).fillMaxSize(),
        )
        if (state == MiffanMascotState.Error) {
            Canvas(Modifier.matchParentSize()) {
                val unit = size.minDimension / 128f
                withTransform({
                    translate((size.width - 128f * unit) / 2f, (size.height - 128f * unit) / 2f)
                    scale(unit, unit, Offset.Zero)
                }) {
                    val center = Offset(108f, 106f)
                    drawCircle(colors.paper, 13f, center)
                    drawCircle(colors.ink, 13f, center, style = androidx.compose.ui.graphics.drawscope.Stroke(1.3f))
                    drawLine(colors.ink, center + Offset(0f, -6f), center + Offset(0f, 1f), 2.8f, StrokeCap.Round)
                    drawCircle(colors.ink, 1.6f, center + Offset(0f, 6f))
                }
            }
        }
    }
}

/** The shared loading state is not evidence that a provider is emitting reasoning. */
internal fun resolveWhaleGirlClip(
    state: MiffanMascotState,
    generationPhase: AssistantGenerationPhase = AssistantGenerationPhase.None,
    inputState: MiffanMascotInputState = MiffanMascotInputState.Inactive,
    petting: Boolean = false,
    sleeping: Boolean = false,
): WhaleGirlClip = when {
    state == MiffanMascotState.Error -> WhaleGirlClip.IDLE
    state == MiffanMascotState.Thinking -> when (generationPhase) {
        AssistantGenerationPhase.Reasoning -> WhaleGirlClip.THINKING
        AssistantGenerationPhase.Responding -> WhaleGirlClip.CHEWING
        else -> WhaleGirlClip.EATING
    }
    state == MiffanMascotState.Happy -> WhaleGirlClip.SUCCESS
    petting -> WhaleGirlClip.PETTING
    inputState != MiffanMascotInputState.Inactive -> WhaleGirlClip.IDLE
    state == MiffanMascotState.UpdateAvailable -> WhaleGirlClip.SURPRISE
    sleeping -> WhaleGirlClip.SLEEPING
    else -> WhaleGirlClip.IDLE
}

internal fun whaleGirlPoster(clip: WhaleGirlClip): Int = when (clip) {
    WhaleGirlClip.IDLE -> R.drawable.whale_girl_idle_poster
    WhaleGirlClip.PETTING -> R.drawable.whale_girl_petting_poster
    WhaleGirlClip.SUCCESS -> R.drawable.whale_girl_success_poster
    WhaleGirlClip.SURPRISE -> R.drawable.whale_girl_surprise_poster
    WhaleGirlClip.EATING -> R.drawable.whale_girl_eating_poster
    WhaleGirlClip.CHEWING -> R.drawable.whale_girl_chewing_poster
    WhaleGirlClip.THINKING -> R.drawable.whale_girl_thinking_poster
    WhaleGirlClip.SLEEPING -> R.drawable.whale_girl_sleeping_poster
}

private const val WHALE_SLEEP_AFTER_MILLIS = 60_000L

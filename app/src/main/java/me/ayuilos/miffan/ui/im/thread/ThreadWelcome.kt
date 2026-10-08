package me.ayuilos.miffan.ui.im.thread

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.isCharacterAvatar
import me.ayuilos.miffan.data.thread.TimelineItem
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.components.ui.AssistantCharacterMascot
import me.ayuilos.miffan.ui.components.ui.AssistantGenerationPhase
import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import me.ayuilos.miffan.ui.components.ui.MiffanPresentation
import me.ayuilos.miffan.ui.components.ui.rememberMiffanDayPhase
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Clock02
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Coming back to a partner after a pause opens on the partner itself: the earlier messages wait
 * behind a button, and a divider tells how long ago the last chat was.
 */
internal object ThreadWelcome {
    /** A thread left alone at least this long opens on the partner instead of its old messages. */
    val IDLE: Duration = Duration.ofHours(1)

    /** Up to this age the last chat reads as relative time ("2 小时前"); older, as a date. */
    val RELATIVE_LIMIT: Duration = Duration.ofDays(3)

    /** Time of the newest message when the thread has been idle long enough to welcome the user back. */
    fun cutoff(timeline: List<TimelineItem>, now: Instant): Instant? {
        val last = timeline.lastOrNull { it is TimelineItem.Message } as TimelineItem.Message? ?: return null
        return last.at.takeIf { Duration.between(it, now) >= IDLE }
    }

    /** Number of leading timeline items that happened by [cutoff]; everything after is this visit's. */
    fun historySize(timeline: List<TimelineItem>, cutoff: Instant): Int {
        val first = timeline.indexOfFirst { item -> item.time()?.isAfter(cutoff) ?: true }
        return if (first < 0) timeline.size else first
    }

    fun isRelative(at: Instant, now: Instant): Boolean = Duration.between(at, now) < RELATIVE_LIMIT

    private fun TimelineItem.time(): Instant? = when (this) {
        is TimelineItem.DateSeparator -> at
        is TimelineItem.Message -> at
        is TimelineItem.Notice -> notice.at
        is TimelineItem.Typing -> null
    }
}

/**
 * The partner greeting a returning user. Alone on screen ([fill]) it centers the partner, which
 * can be poked; once history or new messages surround it, it shrinks to a marker between them.
 */
@Composable
internal fun ThreadWelcomeHero(
    assistant: Assistant?,
    lastChatAt: Instant,
    phase: AssistantGenerationPhase,
    historyHidden: Boolean,
    fill: Boolean,
    onShowHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        ThreadLastChatDivider(lastChatAt)
        Column(
            (if (fill) Modifier.weight(1f) else Modifier).fillMaxWidth().padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // The space stays while the partner is away in the status row, so the page does not jump.
            Box(Modifier.size(168.dp)) {
                ThreadPartnerHome(Modifier.fillMaxSize()) { modifier -> ThreadWelcomeMascot(assistant, phase, modifier) }
            }
            AnimatedVisibility(historyHidden, exit = fadeOut() + shrinkVertically()) {
                FilledTonalButton(onClick = onShowHistory, modifier = Modifier.padding(top = 20.dp)) {
                    Icon(HugeIcons.Clock02, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.im_thread_show_history))
                }
            }
        }
    }
}

/** The partner at full size; both characters react to a poke, other avatars stay still. */
@Composable
internal fun ThreadWelcomeMascot(assistant: Assistant?, phase: AssistantGenerationPhase, modifier: Modifier) {
    val avatar = assistant?.avatar ?: Avatar.Miffan()
    if (avatar.isCharacterAvatar()) {
        AssistantCharacterMascot(
            avatar = avatar,
            state = if (phase != AssistantGenerationPhase.None) MiffanMascotState.Thinking else MiffanMascotState.Idle,
            presentation = MiffanPresentation.Scene,
            interactive = true,
            dayPhase = rememberMiffanDayPhase(),
            generationPhase = phase,
            modifier = modifier,
        )
    } else {
        AssistantAvatar(name = threadAssistantName(assistant), value = avatar, modifier = modifier,
            loading = phase != AssistantGenerationPhase.None, generationPhase = phase)
    }
}

@Composable
private fun ThreadLastChatDivider(at: Instant) {
    val color = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = color)
        Text(stringResource(R.string.im_thread_last_chat, threadLastChatTime(at)), Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = color)
    }
}

@Composable
private fun threadLastChatTime(at: Instant): String {
    val now = Instant.now()
    if (ThreadWelcome.isRelative(at, now)) {
        return android.text.format.DateUtils.getRelativeTimeSpanString(at.toEpochMilli(), now.toEpochMilli(),
            android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
    }
    val locale = LocalConfiguration.current.locales[0]
    val date = at.atZone(ZoneId.systemDefault())
    val skeleton = if (date.year == now.atZone(ZoneId.systemDefault()).year) "MMMd" else "yMMMd"
    return date.format(DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale))
}

/**
 * While the thread welcomes the user back, the big partner and the avatar of the live status row are
 * one character: when the partner starts working it flies from the welcome down to the status row,
 * and returns when the work is done. [handOff] is true while the status row owns the character.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
internal class ThreadPartnerTransition(val scope: SharedTransitionScope, val handOff: Boolean)

internal val LocalThreadPartnerTransition = staticCompositionLocalOf<ThreadPartnerTransition?> { null }

private const val PARTNER_ELEMENT = "thread-partner"

/** The welcome's place for the partner; empty while the status row holds it. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ThreadPartnerHome(modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    val transition = LocalThreadPartnerTransition.current ?: return content(modifier)
    AnimatedVisibility(!transition.handOff, modifier, enter = fadeIn(), exit = fadeOut()) {
        with(transition.scope) {
            content(Modifier.sharedElement(rememberSharedContentState(PARTNER_ELEMENT), this@AnimatedVisibility).fillMaxSize())
        }
    }
}

/** The status row's avatar; during a welcome it is the same character that left the welcome. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ThreadPartnerHandOff(modifier: Modifier, content: @Composable (Modifier) -> Unit) {
    val transition = LocalThreadPartnerTransition.current?.takeIf { it.handOff } ?: return content(modifier)
    val visible = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        with(transition.scope) {
            content(Modifier.sharedElement(rememberSharedContentState(PARTNER_ELEMENT), this@AnimatedVisibility).fillMaxSize())
        }
    }
}

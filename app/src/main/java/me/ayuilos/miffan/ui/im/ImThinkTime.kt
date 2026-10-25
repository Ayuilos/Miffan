package me.ayuilos.miffan.ui.im

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.data.model.Assistant
import me.rerere.ai.core.ReasoningLevel
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiBrain02
import me.rerere.hugeicons.stroke.Flash
import me.rerere.hugeicons.stroke.Idea01
import me.rerere.hugeicons.stroke.Sparkles
import me.rerere.hugeicons.stroke.Timer01

/**
 * How long a partner thinks before replying: easy mode's face of [ReasoningLevel], four tiers an owner
 * understands instead of seven effort levels. Professional mode keeps the exact level; one without a
 * tier of its own (LOW, MEDIUM) reads as [AUTO] and is only overwritten when the owner picks here.
 */
internal enum class ThinkTime(
    @StringRes val title: Int,
    @StringRes val description: Int,
    @StringRes val wait: Int?,
    /** Levels this tier stores, best first; the first the model supports wins. */
    private val levels: List<ReasoningLevel>,
) {
    QUICK(R.string.im_think_quick, R.string.im_think_quick_desc, R.string.im_think_wait_quick,
        listOf(ReasoningLevel.OFF, ReasoningLevel.LOW, ReasoningLevel.MEDIUM)),
    AUTO(R.string.im_think_auto, R.string.im_think_auto_desc, null, listOf(ReasoningLevel.AUTO)),
    // Not MEDIUM: it is many providers' default, so it would feel no different from AUTO.
    MORE(R.string.im_think_more, R.string.im_think_more_desc, R.string.im_think_wait_more,
        listOf(ReasoningLevel.HIGH, ReasoningLevel.XHIGH, ReasoningLevel.MEDIUM)),
    DEEP(R.string.im_think_deep, R.string.im_think_deep_desc, R.string.im_think_wait_deep,
        listOf(ReasoningLevel.MAX, ReasoningLevel.XHIGH, ReasoningLevel.HIGH));

    /** The level this tier stores for [model], or null when the model supports none of them. */
    fun levelFor(model: Model): ReasoningLevel? = model.supportedReasoningLevels().let { supported -> levels.firstOrNull { it in supported } }

    /** On a model that always thinks, the quick tier promises "as fast as it can", not an instant reply. */
    @StringRes
    fun titleFor(model: Model): Int = if (this == QUICK && !model.canSkipThinking()) R.string.im_think_quick_forced else title

    @StringRes
    fun descriptionFor(model: Model): Int = if (this == QUICK && !model.canSkipThinking()) R.string.im_think_quick_forced_desc else description
}

// Outside the enum so the tiers stay plain data (unit tests never build the vectors).
private val ThinkTime.icon: ImageVector
    get() = when (this) {
        ThinkTime.QUICK -> HugeIcons.Flash
        ThinkTime.AUTO -> HugeIcons.Sparkles
        ThinkTime.MORE -> HugeIcons.Idea01
        ThinkTime.DEEP -> HugeIcons.AiBrain02
    }

/** Models that always think have no instant tier; theirs only keeps the thinking short. */
internal fun Model.canSkipThinking(): Boolean = ReasoningLevel.OFF in supportedReasoningLevels()

/** Tiers worth offering for [model]: each stores a level no earlier tier already does. */
internal fun thinkTimesFor(model: Model): List<ThinkTime> = ThinkTime.entries
    .mapNotNull { tier -> tier.levelFor(model)?.let { tier to it } }
    .distinctBy { it.second }
    .map { it.first }

/** The tier a stored [level] shows as on [model]. */
internal fun thinkTimeOf(level: ReasoningLevel, model: Model): ThinkTime {
    val resolved = model.resolveReasoningLevel(level)
    val tier = when (resolved) {
        ReasoningLevel.OFF -> ThinkTime.QUICK
        ReasoningLevel.AUTO -> ThinkTime.AUTO
        ReasoningLevel.LOW, ReasoningLevel.MEDIUM -> if (ThinkTime.QUICK.levelFor(model) == resolved) ThinkTime.QUICK else ThinkTime.AUTO
        ReasoningLevel.HIGH -> ThinkTime.MORE
        ReasoningLevel.XHIGH, ReasoningLevel.MAX -> ThinkTime.DEEP
    }
    val offered = thinkTimesFor(model)
    return tier.takeIf { it in offered } ?: offered.firstOrNull { it.levelFor(model) == resolved } ?: ThinkTime.AUTO
}

/** The partner's model when it can think at all; null hides every think-time control. */
internal fun Settings.thinkingModelOf(partner: Assistant): Model? =
    findModelById(partner.chatModelId ?: chatModelId)?.takeIf { ModelAbility.REASONING in it.abilities }

/** The profile row under the model row; tapping it opens the tiers. */
@Composable
internal fun PartnerThinkTimeRow(partner: Assistant, model: Model, enabled: Boolean, onSelect: (ReasoningLevel) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val current = thinkTimeOf(partner.reasoningLevel, model)
    ImListRow(HugeIcons.Timer01, stringResource(R.string.im_think_title), { open = true },
        stringResource(R.string.im_think_current, stringResource(current.titleFor(model)), stringResource(current.descriptionFor(model))),
        enabled = enabled)
    if (open) ThinkTimeSheet(partner, model, current, onDismiss = { open = false }) { tier ->
        open = false
        if (tier != current) tier.levelFor(model)?.let(onSelect)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThinkTimeSheet(partner: Assistant, model: Model, current: ThinkTime, onDismiss: () -> Unit, onPick: (ThinkTime) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.im_think_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.im_think_sheet_hint, partnerName(partner)), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val recommended = stringResource(R.string.im_think_recommended)
            val options = thinkTimesFor(model).map { tier ->
                val description = stringResource(tier.descriptionFor(model))
                ImOption(
                    key = tier,
                    title = stringResource(tier.titleFor(model)),
                    supporting = tier.wait?.let { stringResource(R.string.im_think_option_supporting, description, stringResource(it)) } ?: description,
                    tag = recommended.takeIf { tier == ThinkTime.AUTO },
                    leading = { Icon(tier.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                )
            }
            ImOptionList(options, selected = setOf(current), onSelect = onPick)
            if (!model.canSkipThinking()) Text(stringResource(R.string.im_think_forced_note), Modifier.padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

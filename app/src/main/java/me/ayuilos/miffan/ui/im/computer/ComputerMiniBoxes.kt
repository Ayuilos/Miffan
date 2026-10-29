package me.ayuilos.miffan.ui.im.computer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.ComputerSnapshots
import me.ayuilos.miffan.data.ai.computer.PartnerSpaces
import me.ayuilos.miffan.data.ai.computer.SpacePower
import me.ayuilos.miffan.data.ai.computer.SpaceTier
import me.ayuilos.miffan.data.ai.computer.SpaceUsage
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import org.koin.compose.koinInject
import java.util.concurrent.ConcurrentHashMap

/**
 * The memory bar under a computer's mini boxes: [usedMb] (dark) is what they use now, the rest of [limitMb]
 * (light) is what they may still grow into, both against the computer's [totalMb]. Unknown parts are null.
 */
internal data class MiniBoxMemory(val limitMb: Int, val usedMb: Int?, val totalMb: Int?) {
    /** (in use, rest of the limits) as shares of the total, or null without a total to measure against. */
    fun shares(): Pair<Float, Float>? {
        val total = totalMb?.takeIf { it > 0 } ?: return null
        val used = (usedMb ?: 0).coerceIn(0, limitMb).coerceAtMost(total)
        val rest = (limitMb - used).coerceAtMost(total - used).coerceAtLeast(0)
        return used.toFloat() / total to rest.toFloat() / total
    }
}

/**
 * Memory the mini boxes use now: what an awake one reports plus nothing for a sleeping one. A box whose state or
 * usage is not known adds nothing, and null means none is known.
 */
internal fun miniBoxesUsedMb(boxes: List<MiniBoxReading?>): Int? {
    val known = boxes.mapNotNull { box ->
        when (box?.power) {
            SpacePower.AWAKE -> box.usage?.memoryMb
            SpacePower.ASLEEP -> 0
            else -> null
        }
    }
    return if (known.isEmpty()) null else known.sum()
}

/** What the detail page knows of one mini box: its power and, only while awake, its usage. */
internal data class MiniBoxReading(val power: SpacePower?, val usage: SpaceUsage?)

internal class MiniBoxLive(val readings: Map<String, MiniBoxReading>, val hostMemoryMb: Int?)

/** The computers' memory from their last probe in this run of the app, so the bar does not start blank. */
private val hostMemories = ConcurrentHashMap<String, Int>()

/**
 * Reads a computer's mini boxes: cached answers first, then once on open and again when the page comes back.
 * Only the host's read-only listing is asked about a box's power; a box's usage is asked only once it is
 * known to be awake, because anything run inside a sleeping box starts it.
 */
@Composable
internal fun rememberMiniBoxLive(hostId: String, workspaceIds: List<String>, spaces: PartnerSpaces): State<MiniBoxLive> {
    var round by remember { mutableIntStateOf(0) }
    var seen by remember { mutableStateOf(false) }
    LifecycleResumeEffect(hostId) {
        if (seen) round++
        seen = true
        onPauseOrDispose { }
    }
    return produceState(
        MiniBoxLive(workspaceIds.associateWith { MiniBoxReading(spaces.lastPower(it), spaces.lastUsage(it)) }, hostMemories[hostId]),
        hostId, workspaceIds, round,
    ) {
        if (workspaceIds.isEmpty()) return@produceState
        coroutineScope {
            launch {
                val memory = attempt { spaces.probe(hostId) }?.host?.memoryMb ?: return@launch
                hostMemories[hostId] = memory
                value = MiniBoxLive(value.readings, memory)
            }
            launch {
                var unreachable = false
                workspaceIds.forEach { id ->
                    val power = if (unreachable) SpacePower.UNREACHABLE else attempt { spaces.power(id) } ?: SpacePower.UNREACHABLE
                    unreachable = power == SpacePower.UNREACHABLE
                    val usage = if (power == SpacePower.AWAKE) attempt { spaces.usage(id) } ?: spaces.lastUsage(id) else null
                    value = MiniBoxLive(value.readings + (id to MiniBoxReading(power, usage)), value.hostMemoryMb)
                }
            }
        }
    }
}

private suspend fun <T> attempt(block: suspend () -> T): T? = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}

/** One mini box: its last screen with the partner's avatar on it, whether it sleeps, its size and what it uses. */
@Composable
internal fun MiniBoxRow(partner: Assistant, workspace: WorkspaceEntity, reading: MiniBoxReading?, onClick: () -> Unit) {
    val snapshots: ComputerSnapshots = koinInject()
    val snapshot by remember(snapshots, workspace.id) { snapshots.latest(workspace.id) }
        .collectAsStateWithLifecycle(initialValue = remember(workspace.id) { snapshots.find(workspace.id) })
    val colors = MaterialTheme.colorScheme
    val power = reading?.power
    val name = threadAssistantName(partner)
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 14.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(Modifier.size(width = 64.dp, height = 40.dp)) {
                Box(Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(colors.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    val shot = snapshot
                    if (shot != null) {
                        AsyncImage(shot.url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
                            colorFilter = if (power == SpacePower.ASLEEP) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.3f) }) else null)
                        if (power == SpacePower.ASLEEP) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                    } else AssistantAvatar(partner.name, partner.avatar, Modifier.size(28.dp))
                }
                if (snapshot != null) AssistantAvatar(partner.name, partner.avatar,
                    Modifier.align(Alignment.BottomEnd).offset(x = 6.dp, y = 6.dp).size(22.dp).border(2.dp, colors.surfaceContainerLow, CircleShape))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.im_computer_detail_box_name, name), maxLines = 1, overflow = TextOverflow.Ellipsis)
                val status = miniBoxStatus(workspace, reading)
                if (status.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    when (power) {
                        SpacePower.AWAKE -> Box(Modifier.size(6.dp).background(colors.tertiary, CircleShape))
                        SpacePower.ASLEEP -> Box(Modifier.size(6.dp).background(colors.outline, CircleShape))
                        else -> {}
                    }
                    Text(status, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(HugeIcons.ArrowRight01, null, tint = colors.onSurfaceVariant)
        }
    }
}

/** "Awake · Performance · using 0.7 GB": only what is known. */
@Composable
private fun miniBoxStatus(workspace: WorkspaceEntity, reading: MiniBoxReading?): String {
    val power = reading?.power
    val size = SpaceTier.of(workspace.spaceCpus, workspace.spaceMemoryMb)?.label() ?: workspace.spaceMemoryMb?.let { spaceMemoryText(it) }
    return listOfNotNull(
        when (power) {
            SpacePower.AWAKE -> stringResource(R.string.im_space_state_awake)
            SpacePower.ASLEEP -> stringResource(R.string.im_space_state_asleep)
            else -> null
        },
        size,
        reading?.usage?.takeIf { power == SpacePower.AWAKE }?.let { stringResource(R.string.im_computer_detail_box_used, spaceMemoryText(it.memoryMb)) },
    ).joinToString(" · ")
}

/** What the mini boxes may take of the computer's memory, with how much they use now; just the limits when the computer's size is unknown. */
@Composable
internal fun MiniBoxMemoryBar(memory: MiniBoxMemory) {
    val colors = MaterialTheme.colorScheme
    val shares = memory.shares()
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
        HorizontalDivider(color = colors.outlineVariant.copy(alpha = 0.5f))
        Row(Modifier.padding(top = 10.dp, bottom = if (shares == null) 12.dp else 0.dp)) {
            Text(stringResource(R.string.im_computer_detail_boxes_limit, spaceMemoryText(memory.limitMb)), Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            memory.totalMb?.let {
                Text(stringResource(R.string.im_computer_detail_boxes_total, spaceMemoryText(it)),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
        }
        if (shares != null) {
            val (used, rest) = shares
            Row(Modifier.padding(top = 6.dp, bottom = 12.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(colors.surfaceContainerHighest)) {
                if (used > 0f) Box(Modifier.weight(used).fillMaxHeight().background(colors.tertiary))
                if (rest > 0f) Box(Modifier.weight(rest).fillMaxHeight().background(colors.tertiary.copy(alpha = 0.35f)))
                val free = 1f - used - rest
                if (free > 0.0001f) Spacer(Modifier.weight(free))
            }
        }
    }
}

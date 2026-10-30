package me.ayuilos.miffan.ui.im.computer

import android.content.ClipData
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.ComputerSnapshots
import me.ayuilos.miffan.data.ai.computer.SpaceSetupJob
import me.ayuilos.miffan.data.ai.tools.spaceNetworkFixCommand
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Cube
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.WifiOff01
import org.koin.compose.koinInject


/** First disk size, roughly: the runtime, the image and room to work. */
private const val SPACE_DISK_GB = 5

/** What `miffan space install` runs, shown before the user agrees. */
private const val SPACE_INSTALL_COMMAND =
    "curl -fsSL https://cua.ai/install.sh | sh -s -- --cli-only --yes --no-modify-path --no-onboarding"

@Composable
private fun sharePartners(state: ComputerSetupState): List<Assistant> {
    val assistants = LocalSettings.current.assistants
    return state.sharePartners.mapNotNull { id -> assistants.find { it.id == id } }
}

/** A command in full, wrapped, on a quiet ground (the card it sits in is a step lighter or darker). */
@Composable
private fun CommandBlock(command: String, modifier: Modifier = Modifier) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp)) {
        Text(command, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

@Composable
private fun CopyCommandButton(command: String, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    OutlinedButton(onClick = {
        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("command", command))) }
    }, modifier = modifier) { Text(stringResource(R.string.im_space_copy_short)) }
}

/** Use the desktop, or give the partners their own computer inside this one. */
@Composable
internal fun ComputerHowStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    val partners = sharePartners(state)
    val computer = state.target?.name.orEmpty()
    val first = partners.firstOrNull()
    val name = first?.let { threadAssistantName(it) }.orEmpty()
    val colors = MaterialTheme.colorScheme
    var adjusting by rememberSaveable { mutableStateOf(false) }
    var showCommand by rememberSaveable { mutableStateOf(false) }
    val one = partners.size == 1 && first != null
    // With mini boxes only there is nothing to choose: the screen just confirms the box and its size.
    SetupScreen(
        title = when {
            state.spaceOnly -> if (one) stringResource(R.string.im_space_start_box, name) else stringResource(R.string.im_space_start_box_many)
            one -> stringResource(R.string.im_space_how_title, name, computer)
            else -> stringResource(R.string.im_space_how_title_many, computer)
        },
        subtitle = if (state.spaceOnly) stringResource(R.string.im_space_done_help, computer) else stringResource(R.string.im_space_how_help),
        state = state, onDismissError = vm::dismissError,
        primary = SetupAction(
            when {
                state.spaceOnly -> stringResource(R.string.im_computer_continue)
                !state.useSpace -> stringResource(R.string.im_space_start_desktop)
                partners.size == 1 && first != null -> stringResource(R.string.im_space_start_box, name)
                else -> stringResource(R.string.im_space_start_box_many)
            }, onClick = vm::continueHow),
    ) {
        HowCard(
            selected = state.useSpace, enabled = !state.busy,
            title = stringResource(if (partners.size > 1) R.string.im_space_own_title_many else R.string.im_space_own_title),
            tag = stringResource(R.string.im_space_safer),
            illustration = { HowIllustration(box = true, partner = first) },
            points = listOf(
                HowPoint.Good(stringResource(R.string.im_space_own_point_do)),
                HowPoint.Good(stringResource(R.string.im_space_own_point_apart)),
                HowPoint.Plain(stringResource(R.string.im_space_own_point_disk)),
            ),
            onClick = { vm.chooseSpace(true) },
        ) {
            AnimatedVisibility(state.useSpace) {
                Surface(
                    onClick = { adjusting = true }, enabled = !state.busy, shape = RoundedCornerShape(14.dp), color = colors.surfaceContainerHighest,
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    Row(Modifier.heightIn(min = 56.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                            Text(state.spaceTier.summary(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                when {
                                    state.pickedTier != null -> stringResource(R.string.im_space_tier_note_picked)
                                    state.spaceCapacity != null -> stringResource(R.string.im_space_tier_note_recommended, computer)
                                    else -> stringResource(R.string.im_space_tier_note_default)
                                },
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            )
                        }
                        TextButton(onClick = { adjusting = true }, enabled = !state.busy) {
                            Text(stringResource(R.string.im_space_tier_adjust), fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        if (!state.spaceOnly) HowCard(
            selected = !state.useSpace, enabled = !state.busy,
            title = stringResource(R.string.im_space_desktop_title),
            illustration = { HowIllustration(box = false, partner = first) },
            points = listOf(
                HowPoint.Good(stringResource(R.string.im_space_desktop_point_do)),
                HowPoint.Plain(stringResource(R.string.im_space_desktop_note)),
            ),
            onClick = { vm.chooseSpace(false) },
        )
        if (state.useSpace && state.spaceProbe?.cuaInstalled != true) Column(Modifier.padding(horizontal = 8.dp)) {
            Text(stringResource(R.string.im_space_cua_note, computer), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(stringResource(if (showCommand) R.string.im_space_cmd_hide else R.string.im_space_cmd_show),
                Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { showCommand = !showCommand }
                    .heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically),
                style = MaterialTheme.typography.labelLarge, color = colors.primary)
            AnimatedVisibility(showCommand) { CommandBlock(SPACE_INSTALL_COMMAND) }
        }
    }
    if (adjusting) {
        val host = state.spaceProbe?.host
        val capacity = state.spaceCapacity
        SpaceTierSheet(
            current = state.spaceTier,
            recommended = state.recommendedTier.takeIf { capacity != null },
            capacity = capacity,
            usedMemoryMb = state.spaceUsedMemoryMb,
            count = partners.size,
            hostLine = if (host?.cpus != null && host.memoryMb != null && capacity != null)
                stringResource(R.string.im_space_tier_host, computer, host.cpus, (host.memoryMb + 512) / 1024,
                    ((capacity.limitMemoryMb - state.spaceUsedMemoryMb).coerceAtLeast(0) + 512) / 1024)
            else null,
            onPick = { vm.pickTier(it); adjusting = false },
            onDismiss = { adjusting = false },
        )
    }
}

private sealed interface HowPoint {
    val text: String
    data class Good(override val text: String) : HowPoint
    data class Plain(override val text: String) : HowPoint
}

/** One way to use the computer: a small picture, a few lines, and [footer] under them (the size row). */
@Composable
private fun HowCard(
    selected: Boolean,
    enabled: Boolean,
    title: String,
    illustration: @Composable () -> Unit,
    points: List<HowPoint>,
    tag: String? = null,
    onClick: () -> Unit,
    footer: @Composable ColumnScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(22.dp), color = colors.surfaceContainerLow,
        border = BorderStroke(if (selected) 2.dp else 1.dp, if (selected) colors.primary else colors.outlineVariant),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                illustration()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(title, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        tag?.let {
                            Surface(shape = RoundedCornerShape(9.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
                                Text(it, Modifier.padding(horizontal = 8.dp, vertical = 1.dp), style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    points.forEach { point ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(Modifier.padding(top = 3.dp).size(16.dp), contentAlignment = Alignment.Center) {
                                when (point) {
                                    is HowPoint.Good -> Icon(HugeIcons.Tick01, null, Modifier.size(16.dp), tint = colors.primary)
                                    is HowPoint.Plain -> Box(Modifier.size(5.dp).clip(CircleShape).background(colors.outline))
                                }
                            }
                            Text(point.text, style = MaterialTheme.typography.bodyMedium,
                                color = if (point is HowPoint.Good) colors.onSurface else colors.onSurfaceVariant)
                        }
                    }
                }
            }
            footer()
        }
    }
}

/** A monitor with either a small box holding the partner (the mini box) or the partner's pointer on the desktop. */
@Composable
private fun HowIllustration(box: Boolean, partner: Assistant?) {
    val colors = MaterialTheme.colorScheme
    val ground = colors.surfaceContainerHighest
    val screen = colors.surface
    val line = colors.outline
    val icon = colors.outlineVariant
    val accent = colors.tertiary
    val accentSoft = colors.tertiaryContainer
    Box(Modifier.size(width = 76.dp, height = 64.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val k = size.width / 76f
            fun at(x: Float, y: Float) = Offset(x * k, y * k)
            fun sized(w: Float, h: Float) = Size(w * k, h * k)
            drawRoundRect(ground, size = size, cornerRadius = CornerRadius(14 * k))
            drawRoundRect(screen, at(6f, 7f), sized(64f, 40f), CornerRadius(5 * k))
            drawRoundRect(line, at(6f, 7f), sized(64f, 40f), CornerRadius(5 * k), Stroke(1.5f * k))
            drawLine(line, at(30f, 55f), at(46f, 55f), 1.5f * k, StrokeCap.Round)
            drawLine(line, at(38f, 47f), at(38f, 55f), 1.5f * k, StrokeCap.Round)
            drawRoundRect(icon, at(12f, 14f), sized(10f, 11f), CornerRadius(1.5f * k))
            drawRoundRect(icon, at(12f, 29f), sized(10f, 11f), CornerRadius(1.5f * k))
            if (box) {
                drawRoundRect(accentSoft, at(34f, 12f), sized(30f, 30f), CornerRadius(7 * k))
                drawRoundRect(accent, at(34f, 12f), sized(30f, 30f), CornerRadius(7 * k), Stroke(1.5f * k))
            } else {
                drawRoundRect(accentSoft, at(28f, 14f), sized(34f, 24f), CornerRadius(3 * k))
                val pointer = Path().apply {
                    moveTo(24f * k, 22f * k); lineTo(33f * k, 26.6f * k); lineTo(29.1f * k, 27.8f * k); lineTo(27.4f * k, 31.6f * k); close()
                }
                drawPath(pointer, accent)
                drawPath(pointer, screen, style = Stroke(1.1f * k))
            }
        }
        if (partner != null) {
            if (box) AssistantAvatar(partner.name, partner.avatar, Modifier.offset(x = 38.dp, y = 16.dp).size(22.dp))
            else AssistantAvatar(partner.name, partner.avatar, Modifier.offset(x = 46.dp, y = 24.dp).size(18.dp))
        }
    }
}

/** Docker or cua is missing, or the computer cannot run them for this account yet: what is in place and what is not. */
@Composable
internal fun ComputerSpaceInstallStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    val probe = state.spaceProbe
    val computer = state.target?.name.orEmpty()
    val problem = dockerProblem(probe?.docker)
    val cua = probe?.cuaInstalled == true
    val command = problem?.let { dockerFixCommand(it, state.spaceFamily) }
    val hostId = state.hostId
    var running by rememberSaveable { mutableStateOf(false) }
    var ranOk by remember { mutableStateOf(false) }
    // A fixed Docker is checked again when the terminal is closed, so its output can be read first.
    if (running && hostId != null && command != null) HostCommandTerminal(hostId, command,
        onFinished = { ok -> ranOk = ok },
        onDismiss = { running = false; if (ranOk) { ranOk = false; vm.recheckSpaces() } })
    SetupScreen(
        title = stringResource(if (problem != null) R.string.im_space_fix_title else R.string.im_space_install_title, computer),
        subtitle = stringResource(if (problem != null) R.string.im_space_fix_help else R.string.im_space_install_help),
        state = state, onDismissError = vm::dismissError,
        primary = if (problem != null) SetupAction(stringResource(R.string.im_space_check_again), onClick = vm::recheckSpaces)
            else SetupAction(stringResource(if (cua) R.string.im_space_continue else R.string.im_space_install_action), onClick = vm::installCua),
        secondary = SetupAction(stringResource(R.string.im_space_later)) { vm.back() },
    ) {
        SetupCard(padding = 6.dp, spacing = 0.dp) {
            SpaceCheckRow(true, stringResource(R.string.im_space_check_connected, computer))
            probe?.diskFreeGb?.let { free ->
                SpaceCheckRow(free >= SPACE_DISK_GB,
                    stringResource(if (free >= SPACE_DISK_GB) R.string.im_space_check_disk else R.string.im_space_check_disk_low, free, SPACE_DISK_GB))
            }
            if (problem != null) DockerPanel(problem, command, computer, canRun = hostId != null && !state.busy, onRun = { running = true })
            else if (probe?.docker != null) SpaceCheckRow(true, stringResource(R.string.im_space_check_docker))
            SpaceCheckRow(cua,
                stringResource(if (cua) R.string.im_space_check_cua else R.string.im_space_check_cua_missing),
                stringResource(if (problem != null && !cua) R.string.im_space_check_cua_later else R.string.im_space_check_cua_detail).takeIf { !cua })
        }
        if (!cua && problem == null) SetupDisclosure(stringResource(R.string.im_space_show_command)) {
            CommandBlock(SPACE_INSTALL_COMMAND)
        }
    }
}

/** A line of the list: a soft tick when in place, a dashed ring when not. */
@Composable
private fun SpaceCheckRow(done: Boolean, title: String, detail: String? = null) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (done) Box(Modifier.size(22.dp).clip(CircleShape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(HugeIcons.Tick01, null, Modifier.size(14.dp), tint = colors.onPrimaryContainer)
        } else DashedRing(Modifier.size(22.dp), colors.outline)
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
            detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant.copy(alpha = 0.8f)) }
        }
    }
}

@Composable
private fun DashedRing(modifier: Modifier, color: Color) {
    Canvas(modifier) {
        val width = 1.6.dp.toPx()
        drawCircle(color, radius = (size.minDimension - width) / 2, style = Stroke(width, pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
    }
}

/** What is wrong with Docker and the one command that fixes it, to run from the phone or copy; or plain words if no command is safe. */
@Composable
private fun DockerPanel(problem: DockerProblem, command: String?, computer: String, canRun: Boolean, onRun: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = RoundedCornerShape(16.dp), color = colors.tertiaryContainer,
        contentColor = colors.onTertiaryContainer) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(HugeIcons.AlertCircle, null, Modifier.size(22.dp))
                Text(stringResource(if (problem == DockerProblem.NOT_INSTALLED) R.string.im_space_docker_missing_title else R.string.im_space_docker_unusable_title),
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
            Column(Modifier.padding(start = 34.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (command != null) {
                    Text(stringResource(R.string.im_space_docker_run, computer), style = MaterialTheme.typography.bodyMedium)
                    CommandBlock(command)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onRun, enabled = canRun) { Text(stringResource(R.string.im_space_run_here)) }
                        CopyCommandButton(command)
                    }
                } else Text(stringResource(R.string.im_space_docker_unknown, computer), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** The run under way: four named stages and a ring; the page moves on by itself when the box is ready. */
@Composable
internal fun ComputerSpacePrepareStep(vm: ComputerSetupVM, state: ComputerSetupState, onChat: () -> Unit) {
    val job by vm.spaceJob.collectAsStateWithLifecycle()
    val partners = sharePartners(state)
    val first = partners.firstOrNull()
    val computer = state.target?.name.orEmpty()
    val colors = MaterialTheme.colorScheme
    val failed = job?.stage == SpaceSetupJob.Stage.FAILED
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(failed) {
        while (!failed) { now = System.currentTimeMillis(); delay(1_000) }
    }
    val elapsed = ((now - (job?.startedAt ?: now)) / 1000).coerceAtLeast(0)
    val rows = prepareRows(job?.stage)
    // The failure sits in the error card; dismissing it clears nothing on the run itself.
    val shown = if (failed) state.copy(error = job?.error) else state
    SetupScreen(
        title = null, subtitle = null,
        state = shown, onDismissError = vm::dismissError,
        primary = if (failed) SetupAction(stringResource(R.string.im_space_retry), onClick = vm::retrySpaces) else null,
        secondary = SetupAction(stringResource(R.string.im_space_go_chat), onClick = onChat),
    ) {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            PrepareRing(spinning = !failed, partner = first)
            Text(
                if (partners.size == 1 && first != null) stringResource(R.string.im_space_prepare_title, threadAssistantName(first))
                else stringResource(R.string.im_space_prepare_title_many),
                Modifier.padding(top = 20.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
            )
            Text(
                stringResource(when {
                    failed -> R.string.im_space_now_failed
                    rows[3] == PrepareRow.LIVE -> R.string.im_space_now_network
                    else -> R.string.im_space_now_start
                }),
                Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
            )
            if (!failed) Text(stringResource(R.string.im_space_prepare_elapsed, formatElapsed(elapsed)), style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant)
        }
        SetupCard(padding = 8.dp, spacing = 0.dp) {
            listOf(
                stringResource(R.string.im_space_stage_check, computer), stringResource(R.string.im_space_stage_install),
                stringResource(R.string.im_space_stage_start), stringResource(R.string.im_space_stage_network),
            ).forEachIndexed { i, label -> PrepareStageRow(rows[i], label) }
        }
        Surface(shape = RoundedCornerShape(16.dp), color = colors.primaryContainer, contentColor = colors.onPrimaryContainer) {
            Text(stringResource(R.string.im_space_prepare_help), Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** A turning arc around the partner; a plain ring once the run has failed. */
@Composable
private fun PrepareRing(spinning: Boolean, partner: Assistant?) {
    val colors = MaterialTheme.colorScheme
    val turn by rememberInfiniteTransition(label = "prepare").animateFloat(0f, 360f, infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart), label = "turn")
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val width = 4.dp.toPx()
            val inset = width / 2
            drawCircle(colors.outlineVariant, radius = size.minDimension / 2 - inset, style = Stroke(width))
            if (spinning) drawArc(colors.tertiary, turn, 100f, useCenter = false, topLeft = Offset(inset, inset),
                size = Size(size.width - width, size.height - width), style = Stroke(width, cap = StrokeCap.Round))
        }
        Surface(Modifier.size(92.dp), shape = RoundedCornerShape(26.dp), color = colors.tertiaryContainer) {
            Box(contentAlignment = Alignment.Center) {
                if (partner != null) AssistantAvatar(partner.name, partner.avatar, Modifier.size(62.dp))
                else Icon(HugeIcons.Cube, null, Modifier.size(40.dp), tint = colors.onTertiaryContainer)
            }
        }
    }
}

@Composable
private fun PrepareStageRow(row: PrepareRow, label: String) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            when (row) {
                PrepareRow.DONE -> Box(Modifier.size(22.dp).clip(CircleShape).background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(HugeIcons.Tick01, null, Modifier.size(14.dp), tint = colors.onPrimaryContainer)
                }
                PrepareRow.LIVE -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.6.dp, color = colors.tertiary, trackColor = colors.tertiaryContainer)
                PrepareRow.LATER -> DashedRing(Modifier.size(22.dp), colors.outlineVariant)
                PrepareRow.FAILED -> Box(Modifier.size(22.dp).clip(CircleShape).background(colors.errorContainer), contentAlignment = Alignment.Center) {
                    Icon(HugeIcons.Cancel01, null, Modifier.size(14.dp), tint = colors.onErrorContainer)
                }
            }
        }
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (row == PrepareRow.LIVE) FontWeight.Bold else FontWeight.Normal,
            color = when (row) {
                PrepareRow.LIVE, PrepareRow.FAILED -> colors.onSurface
                PrepareRow.DONE -> colors.onSurfaceVariant
                PrepareRow.LATER -> colors.onSurfaceVariant.copy(alpha = 0.7f)
            })
        if (row == PrepareRow.LIVE) LinearProgressIndicator(Modifier.width(56.dp).height(4.dp).clip(CircleShape), color = colors.tertiary, trackColor = colors.tertiaryContainer)
    }
}

private fun formatElapsed(seconds: Long): String = "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"

/** Made, but offline: the firewall fix to run from the phone when the check points at ufw, else a hint to look at the network. */
@Composable
internal fun ComputerSpaceNetworkStep(vm: ComputerSetupVM, state: ComputerSetupState) {
    val job by vm.spaceJob.collectAsStateWithLifecycle()
    val partners = sharePartners(state)
    val first = partners.firstOrNull()
    val computer = state.target?.name.orEmpty()
    val colors = MaterialTheme.colorScheme
    val checking = job?.stage == SpaceSetupJob.Stage.CHECKING_NETWORK
    val firewall = job?.firewallHint == true
    var running by rememberSaveable { mutableStateOf(false) }
    var showCommand by rememberSaveable { mutableStateOf(false) }
    val hostId = state.hostId
    // The Spaces of one run share a host, so one command fits them all.
    val command = spaceNetworkFixCommand(job?.network?.values?.firstOrNull { it?.firewallFix == true })
    if (running && hostId != null) HostCommandTerminal(hostId, command,
        // A fixed firewall checks again by itself; the terminal stays open to show what happened.
        onFinished = { ok -> if (ok) vm.recheckNetwork() },
        onDismiss = { running = false })
    SetupScreen(
        title = if (partners.size == 1 && first != null) stringResource(R.string.im_space_network_title, threadAssistantName(first))
            else stringResource(R.string.im_space_network_title_many),
        subtitle = stringResource(if (firewall) R.string.im_space_network_help_ufw else R.string.im_space_network_help, computer),
        state = state, onDismissError = vm::dismissError,
        centered = true,
        hero = {
            Surface(Modifier.size(84.dp), shape = RoundedCornerShape(26.dp), color = colors.surfaceContainerLow) {
                Box(contentAlignment = Alignment.Center) { Icon(HugeIcons.WifiOff01, null, Modifier.size(40.dp), tint = colors.tertiary) }
            }
        },
        primary = if (firewall) SetupAction(stringResource(R.string.im_space_fix_on_phone), enabled = !checking && hostId != null) { running = true }
            else SetupAction(stringResource(if (checking) R.string.im_space_net_checking else R.string.im_space_check_again),
                enabled = !checking, onClick = vm::recheckNetwork),
        secondary = SetupAction(stringResource(R.string.im_space_network_skip), onClick = vm::skipNetwork),
    ) {
        if (firewall) SetupCard {
            Text(stringResource(R.string.im_space_net_fix_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.im_space_net_fix_body), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.im_space_fix_password, computer), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Text(stringResource(if (showCommand) R.string.im_space_collapse else R.string.im_space_net_self_run),
                Modifier.clip(MaterialTheme.shapes.small).clickable(role = Role.Button) { showCommand = !showCommand }
                    .heightIn(min = 40.dp).wrapContentHeight(Alignment.CenterVertically),
                style = MaterialTheme.typography.labelLarge, color = colors.primary)
            AnimatedVisibility(showCommand) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CommandBlock(command)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CopyCommandButton(command)
                        OutlinedButton(onClick = vm::recheckNetwork, enabled = !checking) {
                            Text(stringResource(if (checking) R.string.im_space_net_checking else R.string.im_space_check_again))
                        }
                    }
                }
            }
        }
    }
}

/** The new box at a glance: its last picture, what it can and cannot do, and things to try. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComputerSpaceDoneStep(
    vm: ComputerSetupVM,
    state: ComputerSetupState,
    onChat: (Assistant, String?) -> Unit,
    onChangeMode: (Assistant) -> Unit,
    onScreen: (Assistant) -> Unit,
) {
    val job by vm.spaceJob.collectAsStateWithLifecycle()
    val partners = sharePartners(state)
    val first = partners.firstOrNull()
    val computer = state.target?.name.orEmpty()
    val colors = MaterialTheme.colorScheme
    val online = job?.online == true
    val snapshots: ComputerSnapshots = koinInject()
    val workspaceId = first?.let { job?.created?.get(it.id) ?: it.workspaceId?.toString() }
    val snapshot by remember(snapshots, workspaceId) { workspaceId?.let(snapshots::latest) ?: flowOf(null) }
        .collectAsStateWithLifecycle(initialValue = remember(workspaceId) { workspaceId?.let(snapshots::find) })
    SetupScreen(
        title = null, subtitle = null,
        state = state, onDismissError = vm::dismissError,
        primary = first?.let { partner ->
            SetupAction(stringResource(R.string.im_computer_go_chat_with, threadAssistantName(partner))) { onChat(partner, null) }
        },
        secondary = first?.let { partner -> SetupAction(stringResource(R.string.im_space_look_screen)) { onScreen(partner) } },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp), color = colors.surfaceContainerLow) {
                    Box(Modifier.padding(10.dp).fillMaxWidth().height(214.dp).clip(RoundedCornerShape(16.dp)).background(colors.surfaceContainerHighest)) {
                        val shot = snapshot
                        if (shot != null) AsyncImage(shot.url, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        else Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(HugeIcons.Cube, null, Modifier.size(36.dp), tint = colors.tertiary)
                            Text(stringResource(R.string.im_space_shot_fresh), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                        }
                        Surface(Modifier.align(Alignment.TopStart).padding(10.dp), shape = CircleShape, color = Color.Black.copy(alpha = 0.66f)) {
                            Row(Modifier.padding(start = 8.dp, end = 10.dp, top = 3.dp, bottom = 3.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(if (online) Color(0xFF3CCB7F) else Color(0xFFF5B14C)))
                                Text(stringResource(if (online) R.string.im_space_chip_online else R.string.im_space_chip_offline),
                                    style = MaterialTheme.typography.labelMedium, color = Color.White)
                            }
                        }
                    }
                }
                if (first != null) Box(Modifier.align(Alignment.BottomStart).offset(x = 22.dp, y = 18.dp).size(52.dp).clip(CircleShape)
                    .background(colors.surface).padding(4.dp).clip(CircleShape).background(colors.tertiaryContainer), contentAlignment = Alignment.Center) {
                    AssistantAvatar(first.name, first.avatar, Modifier.size(44.dp))
                }
            }
            Text(
                if (partners.size == 1 && first != null) stringResource(R.string.im_space_done_title, threadAssistantName(first))
                else stringResource(R.string.im_space_done_title_many),
                Modifier.padding(start = 4.dp, end = 4.dp, top = 30.dp), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(R.string.im_space_done_help, computer), Modifier.padding(horizontal = 4.dp),
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DoneTile(Modifier.weight(1f).fillMaxHeight(), true, stringResource(R.string.im_space_can),
                stringResource(if (online) R.string.im_space_can_short else R.string.im_space_can_short_offline))
            DoneTile(Modifier.weight(1f).fillMaxHeight(), false, stringResource(R.string.im_space_cannot),
                stringResource(R.string.im_space_cannot_body, computer))
        }
        Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLow) {
            Row(Modifier.fillMaxWidth().padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(stringResource(R.string.im_space_mode_auto), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    Text(stringResource(R.string.im_space_mode_note), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (first != null) TextButton(onClick = { onChangeMode(first) }) {
                    Text(stringResource(R.string.im_space_mode_change), fontWeight = FontWeight.Bold)
                }
            }
        }
        if (first != null) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.im_space_try_title), Modifier.padding(horizontal = 4.dp), style = MaterialTheme.typography.labelLarge,
                color = colors.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                spaceIdeas(online).forEach { idea ->
                    val text = stringResource(idea.label())
                    Surface(onClick = { onChat(first, text) }, shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLow,
                        border = BorderStroke(1.dp, colors.outlineVariant)) {
                        Text(text, Modifier.heightIn(min = 40.dp).padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun SpaceIdea.label(): Int = when (this) {
    SpaceIdea.WEATHER -> R.string.im_space_idea_weather
    SpaceIdea.PAPERS -> R.string.im_space_idea_papers
    SpaceIdea.PYTHON -> R.string.im_space_idea_python
    SpaceIdea.SORT -> R.string.im_space_idea_sort
    SpaceIdea.TOUR -> R.string.im_space_idea_tour
}

@Composable
private fun DoneTile(modifier: Modifier, can: Boolean, title: String, body: String) {
    val colors = MaterialTheme.colorScheme
    Surface(modifier, shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLow) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(if (can) HugeIcons.Tick01 else HugeIcons.Cancel01, null, Modifier.size(16.dp), tint = if (can) colors.primary else colors.error)
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if (can) colors.primary else colors.error)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        }
    }
}

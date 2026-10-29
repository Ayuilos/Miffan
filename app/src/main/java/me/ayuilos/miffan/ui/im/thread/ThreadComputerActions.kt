package me.ayuilos.miffan.ui.im.thread

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.PartnerComputer
import me.ayuilos.miffan.data.ai.tools.COMPUTER_TOOL_PREFIX
import me.ayuilos.miffan.ui.components.message.ComputerToolStatus
import me.ayuilos.miffan.ui.components.message.computerToolStatus
import me.ayuilos.miffan.ui.components.message.tools.DefaultToolPreview
import me.ayuilos.miffan.ui.components.message.tools.ToolUIContext
import me.ayuilos.miffan.ui.im.ImActionCard
import me.ayuilos.miffan.ui.im.ImCardBlock
import me.ayuilos.miffan.ui.im.ImCardButton
import me.ayuilos.miffan.ui.im.ImCardButtons
import me.ayuilos.miffan.ui.im.ImCardOutlinedButton
import me.ayuilos.miffan.utils.computerActionTitle
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.ComputerVideo
import me.rerere.hugeicons.stroke.CursorPointer01
import me.rerere.hugeicons.stroke.Cube
import me.rerere.hugeicons.stroke.Refresh01
import me.rerere.hugeicons.stroke.Tick01
import kotlin.math.roundToInt

internal val computerObservationNames = setOf(
    "start", "get_desktop_state", "get_window_state", "list_windows", "list_apps", "read_guide",
    "get_screen_size", "get_cursor_position", "zoom", "verify_state", "clipboard_read",
)

internal fun UIMessagePart.Tool.isComputerTool() = toolName.startsWith(COMPUTER_TOOL_PREFIX)

internal data class ThreadComputerEvidence(
    val tools: List<UIMessagePart.Tool>,
    val actionCount: Int,
    val screenshot: String?,
    val status: ComputerToolStatus?,
) {
    val latest: UIMessagePart.Tool get() = tools.last()
}

internal fun threadComputerEvidence(parts: List<UIMessagePart>): ThreadComputerEvidence? {
    // Declined or answered-in-chat calls never touched the computer.
    val tools = parts.filterIsInstance<UIMessagePart.Tool>()
        .filter { it.isComputerTool() && it.approvalState !is ToolApprovalState.Denied }
    if (tools.isEmpty()) return null
    return ThreadComputerEvidence(
        tools = tools,
        actionCount = tools.count {
            it.isExecuted && computerToolStatus(it.output) == null &&
                it.toolName.removePrefix(COMPUTER_TOOL_PREFIX) !in computerObservationNames
        },
        screenshot = tools.flatMap { it.output }.filterIsInstance<UIMessagePart.Image>().lastOrNull()?.url,
        status = computerToolStatus(tools.flatMap { it.output }),
    )
}

internal fun latestComputerAction(parts: List<UIMessagePart>): UIMessagePart.Tool? =
    (parts.lastOrNull { it is UIMessagePart.Tool || it is UIMessagePart.ServerTool } as? UIMessagePart.Tool)
        ?.takeIf { it.isComputerTool() }

/** The machine a computer tool acts on: [spaceName] for a partner's own computer, else its host's name. */
internal fun computerTargetName(tool: UIMessagePart.Tool, computer: PartnerComputer?, fallback: String, spaceName: String): String =
    if (tool.workspaceTarget?.kind == me.ayuilos.miffan.data.db.entity.WorkspaceEntity.KIND_SPACE) spaceName
    else tool.workspaceTarget?.remoteHostName?.takeIf { it.isNotBlank() } ?: computer?.name ?: fallback

internal fun canOpenComputer(tool: UIMessagePart.Tool, computer: PartnerComputer?): Boolean =
    computer?.showsEntry == true &&
        (tool.workspaceTarget?.remoteHostId == null || tool.workspaceTarget?.remoteHostId == computer.hostId)

@Composable
internal fun computerActionText(tool: UIMessagePart.Tool): String {
    val action = computerActionTitle(tool.toolName, tool.inputAsJson())
    val title = action.label?.let { stringResource(it) } ?: tool.toolName
    return action.detail?.let { stringResource(R.string.computer_use_action_detail, title, it) } ?: title
}

/** Screen pictures sit on dark, like on the screen page. */
private val ScreenDark = Color(0xFF111317)

@Composable
private fun ComputerRecordStep.text(): String = step?.text() ?: computerActionText(tool)

/**
 * The computer work of one reply, as one card: live while the partner is at it (a mini screen with the step it is on,
 * and its question when it waits for a yes), afterwards one record that opens to the steps taken.
 */
@Composable
internal fun ThreadComputerBlock(
    tools: List<UIMessagePart.Tool>,
    live: Boolean,
    computer: PartnerComputer?,
    onScreen: () -> Unit,
    onApproval: (String, Boolean) -> Unit = { _, _ -> },
    partner: String = "",
) {
    // A question waiting for the owner always belongs to the live card, even if the reply has moved on.
    val live = live || pendingComputerActions(tools).isNotEmpty()
    val steps = remember(tools, live) { computerRecordSteps(tools, live) }
    val evidence = remember(tools) { threadComputerEvidence(tools) }
    val latest = tools.last()
    val miniBox = latest.inMiniBox()
    val name = computerTargetName(latest, computer, stringResource(R.string.im_computer_computer),
        stringResource(R.string.im_computer_detail_user_space))
    val canOpen = canOpenComputer(latest, computer)
    val key = tools.first().toolCallId
    // The owner said no to the last thing it asked: the record ends with that.
    val declined = declinedAtEnd(tools)
    when {
        live -> ThreadComputerLiveCard(tools, steps, evidence?.screenshot, name, canOpen, onScreen, onApproval, partner)
        steps.isNotEmpty() -> ThreadComputerRecordCard(key, steps, recordFailure(steps, tools), declined, evidence?.screenshot, name, miniBox, canOpen, onScreen)
        evidence != null -> ThreadComputerObserved(evidence.screenshot, name, miniBox, canOpen, onScreen)
    }
}

@Composable
private fun ComputerThumb(url: String, onClick: (() -> Unit)? = null) {
    AsyncImage(model = url, contentDescription = onClick?.let { stringResource(R.string.im_computer_view_screen) }, contentScale = ContentScale.Crop,
        modifier = Modifier.size(width = 64.dp, height = 40.dp).clip(RoundedCornerShape(8.dp)).background(ScreenDark)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier))
}

@Composable
private fun ComputerTile(icon: androidx.compose.ui.graphics.vector.ImageVector, container: Color, content: Color) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(36.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp)) }
    }
}

/** "View screen" with its arrow, at the end of a card. */
@Composable
private fun ViewScreenLink(onClick: (() -> Unit)? = null) {
    Row(if (onClick != null) Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick).heightIn(min = 32.dp) else Modifier,
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.im_computer_view_screen), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Icon(HugeIcons.ArrowRight01, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
    }
}

/** The partner only looked: the last picture it saw, and that nothing was touched. */
@Composable
private fun ThreadComputerObserved(screenshot: String?, name: String, miniBox: Boolean, canOpen: Boolean, onScreen: () -> Unit) {
    Surface(onClick = onScreen, enabled = canOpen, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 10.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (screenshot != null) ComputerThumb(screenshot)
            else ComputerTile(HugeIcons.Computer, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
            Column(Modifier.weight(1f)) {
                Text(if (miniBox) stringResource(R.string.im_computer_observed_mini_box) else stringResource(R.string.im_computer_observed, name),
                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(stringResource(R.string.im_computer_observed_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (canOpen) ViewScreenLink()
        }
    }
}

/**
 * What was done on the computer in this reply: "did N steps", the last of them, and a timeline when tapped. A
 * failed step the partner got past stays grey; only work that did not end well turns the card to a warning.
 */
@Composable
private fun ThreadComputerRecordCard(
    key: String,
    steps: List<ComputerRecordStep>,
    failure: ComputerRecordStep?,
    declined: UIMessagePart.Tool?,
    screenshot: String?,
    name: String,
    miniBox: Boolean,
    canOpen: Boolean,
    onScreen: () -> Unit,
) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val count = steps.size
    val title = (if (miniBox) pluralStringResource(R.plurals.im_computer_record_mini_box, count, count)
        else pluralStringResource(R.plurals.im_computer_record_desktop, count, name, count)) +
        if (failure != null) stringResource(R.string.im_computer_record_not_done) else ""
    val subtitle = when {
        failure != null -> stringResource(R.string.im_computer_record_stuck, failure.text())
        declined != null -> stringResource(R.string.im_computer_record_declined, computerActionText(declined))
        else -> stringResource(R.string.im_computer_record_last, steps.last().text())
    }
    val toggle = stringResource(if (expanded) R.string.im_steps_collapse else R.string.im_steps_expand)
    Surface(onClick = { expanded = !expanded }, shape = RoundedCornerShape(14.dp),
        color = if (failure != null) colors.tertiaryContainer.copy(alpha = .4f) else colors.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = if (expanded) 6.dp else 9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                when {
                    failure != null -> ComputerTile(HugeIcons.Alert02, colors.tertiaryContainer, colors.onTertiaryContainer)
                    !miniBox -> ComputerTile(HugeIcons.Computer, colors.primaryContainer, colors.onPrimaryContainer)
                    screenshot != null -> ComputerThumb(screenshot, if (canOpen) onScreen else null)
                    else -> ComputerTile(HugeIcons.Cube, colors.tertiaryContainer, colors.onTertiaryContainer)
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (failure != null) colors.onTertiaryContainer else colors.onSurfaceVariant)
                }
                Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, toggle, Modifier.size(16.dp), tint = colors.onSurfaceVariant)
            }
            if (expanded) Column(Modifier.padding(start = 8.dp, end = 4.dp, top = 8.dp)) {
                steps.forEachIndexed { index, step -> ComputerStepRow(step, rail = index < steps.lastIndex, strong = index == steps.lastIndex && !step.failed) }
            }
        }
    }
}

@Composable
private fun ComputerStepRow(step: ComputerRecordStep, rail: Boolean, strong: Boolean) {
    val colors = MaterialTheme.colorScheme
    val line = colors.outlineVariant
    val note = when {
        step.workedAround -> stringResource(R.string.im_computer_record_retried)
        step.note == ComputerToolStatus.UNCONFIRMED -> stringResource(R.string.computer_use_unconfirmed)
        step.note == ComputerToolStatus.REFUSED -> stringResource(R.string.im_computer_taken_over)
        else -> null
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 32.dp).drawBehind {
        if (rail) drawLine(line, Offset(9.dp.toPx(), 22.dp.toPx()), Offset(9.dp.toPx(), size.height), strokeWidth = 1.5.dp.toPx())
    }, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val (icon, container, tint) = when {
            step.failed && !step.workedAround -> Triple(HugeIcons.Cancel01, colors.tertiaryContainer, colors.onTertiaryContainer)
            step.workedAround -> Triple(HugeIcons.Refresh01, colors.surfaceContainerHighest, colors.onSurfaceVariant)
            else -> Triple(HugeIcons.Tick01, colors.primaryContainer, colors.primary)
        }
        Box(Modifier.padding(top = 1.dp).size(18.dp).background(container, CircleShape), contentAlignment = Alignment.Center) {
            if (step.running) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp)
            else Icon(icon, null, Modifier.size(11.dp), tint = tint)
        }
        Column(Modifier.weight(1f).padding(bottom = 8.dp)) {
            Text(step.text(), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontWeight = if (strong) FontWeight.Bold else null,
                color = if (step.workedAround) colors.onSurfaceVariant else colors.onSurface)
            note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
        }
    }
}

/**
 * While the partner works: a mini screen with the latest picture it got and the step it is on, a pointer where the
 * action lands, and, when it waits for a yes, its question right there. Not a video; it moves on with each step.
 */
@Composable
private fun ThreadComputerLiveCard(
    tools: List<UIMessagePart.Tool>,
    steps: List<ComputerRecordStep>,
    screenshot: String?,
    name: String,
    canOpen: Boolean,
    onScreen: () -> Unit,
    onApproval: (String, Boolean) -> Unit,
    partner: String,
) {
    val waiting = pendingComputerActions(tools)
    val pending = waiting.firstOrNull()
    val current = steps.currentStep()
    val action = pending ?: current?.tool
    val caption = pending?.let { computerActionText(it) } ?: current?.text()
    val position = if (current == null) 1 else steps.indexOf(current) + 1
    val stepLine = if (current == null) stringResource(R.string.im_computer_operating, name)
        else stringResource(R.string.im_computer_live_step, name, position)
    // x,y are places on the picture the partner saw; the action's own picture already shows the result.
    val pointerTool = action?.takeIf { tool -> tool.hasPointer() && tool.output.none { it is UIMessagePart.Image } }
    val open = if (canOpen) onScreen else null
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (screenshot != null) {
            ComputerMiniScreen(screenshot, pointerTool, highlight = pending != null, caption = caption.takeIf { pending == null }, onClick = open)
        } else Surface(onClick = onScreen, enabled = canOpen, color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Column(Modifier.weight(1f)) {
                    Text(stepLine, style = MaterialTheme.typography.titleSmall)
                    caption?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                }
                if (canOpen) ViewScreenLink()
            }
        }
        if (pending != null) ComputerInPlaceApproval(waiting, name, partner, onApproval)
        else if (screenshot != null) Row(Modifier.padding(start = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stepLine, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (canOpen) ViewScreenLink(onScreen)
        }
    }
}

/** The latest screen picture in a frame the partner's colour, with the pointer or the highlighted spot over it. */
@Composable
private fun ComputerMiniScreen(url: String, pointerTool: UIMessagePart.Tool?, highlight: Boolean, caption: String?, onClick: (() -> Unit)?) {
    val colors = MaterialTheme.colorScheme
    val accent = colors.tertiary
    val shape = RoundedCornerShape(14.dp)
    var image by remember(url) { mutableStateOf<Size?>(null) }
    val context = LocalPlatformContext.current
    // Full size: the partner's x,y are in the picture's own pixels.
    val request = remember(url) { ImageRequest.Builder(context).data(url).size(coil3.size.Size.ORIGINAL).build() }
    Box(Modifier.fillMaxWidth().aspectRatio((image?.let { it.width / it.height } ?: 1.6f).coerceIn(1f, 2.2f))
        .clip(shape).background(ScreenDark).border(2.dp, accent, shape)
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)) {
        AsyncImage(model = request, contentDescription = stringResource(R.string.im_computer_screenshot), contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(), onSuccess = { image = Size(it.result.image.width.toFloat(), it.result.image.height.toFloat()) })
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val box = Size(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat())
            val picture = image
            val point = if (pointerTool != null && picture != null) computerPointer(pointerTool, picture.width, picture.height) else null
            if (picture != null && point != null) {
                val fit = minOf(box.width / picture.width, box.height / picture.height)
                val shown = Size(picture.width * fit, picture.height * fit)
                val at = Offset((box.width - shown.width) / 2 + point.first * shown.width, (box.height - shown.height) / 2 + point.second * shown.height)
                if (highlight) ComputerHighlight(at, accent, pointerTool?.isClick() == true)
                ComputerPointer(at, accent)
            }
            caption?.let {
                Surface(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp, start = 10.dp, end = 10.dp), color = ScreenDark.copy(alpha = .72f),
                    contentColor = Color.White, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(start = 8.dp, end = 11.dp, top = 5.dp, bottom = 5.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = accent)
                        Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/** A pulse and an arrow where the action lands; the arrow's tip is the spot. */
@Composable
private fun ComputerPointer(at: Offset, color: Color) {
    val pulse by rememberInfiniteTransition(label = "pointer").animateFloat(.7f, 1.15f,
        infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    val radius = with(LocalDensity.current) { 15.dp.roundToPx() }
    Box(Modifier.offset { IntOffset(at.x.roundToInt() - radius, at.y.roundToInt() - radius) }.size(30.dp).scale(pulse)
        .background(color.copy(alpha = .4f), CircleShape))
    Icon(HugeIcons.CursorPointer01, null, Modifier.offset { IntOffset(at.x.roundToInt() - 2, at.y.roundToInt() - 2) }.size(18.dp), tint = color)
}

/** The spot the partner asks to act on, framed with the rest of the picture dimmed. */
@Composable
private fun BoxScope.ComputerHighlight(at: Offset, color: Color, click: Boolean) {
    val spot = with(LocalDensity.current) { Size(46.dp.toPx(), 32.dp.toPx()) }
    Canvas(Modifier.fillMaxSize()) {
        val hole = Rect(Offset(at.x - spot.width / 2, at.y - spot.height / 2), spot)
        val radius = CornerRadius(7.dp.toPx())
        val scrim = Path().apply {
            fillType = PathFillType.EvenOdd
            addRect(Rect(Offset.Zero, size))
            addRoundRect(RoundRect(hole, radius))
        }
        drawPath(scrim, Color.Black.copy(alpha = .32f))
        drawRoundRect(color, hole.topLeft, hole.size, radius, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
    }
    if (click) Surface(Modifier.offset { IntOffset((at.x - 40.dp.toPx()).roundToInt().coerceAtLeast(4), (at.y - spot.height / 2 - 24.dp.toPx()).roundToInt().coerceAtLeast(4)) },
        color = color, contentColor = MaterialTheme.colorScheme.onTertiary, shape = RoundedCornerShape(8.dp)) {
        Text(stringResource(R.string.im_computer_click_here), Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
    }
}

/**
 * The partner's question inside its live card: the first action waiting, allow or not, and the exact arguments one tap
 * away. Actions asked together are answered one by one, or all at once; saying no to one leaves the rest waiting.
 */
@Composable
private fun ComputerInPlaceApproval(waiting: List<UIMessagePart.Tool>, name: String, partner: String, onApproval: (String, Boolean) -> Unit) {
    val tool = waiting.first()
    var answered by remember(tool.toolCallId) { mutableStateOf(false) }
    var details by remember(tool.toolCallId) { mutableStateOf(false) }
    val who = partner.ifBlank { stringResource(R.string.im_computer_partner) }
    val action = computerActionText(tool)
    Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column {
            Text(if (tool.inMiniBox()) stringResource(R.string.im_computer_asks_mini_box, who, action)
                else stringResource(R.string.im_computer_asks_desktop, who, name, action),
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (waiting.size > 1) Text(pluralStringResource(R.plurals.im_computer_more_waiting, waiting.size - 1, waiting.size - 1),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RelayApprovalNotice(tool)
        if (tool.interruptsOwner()) {
            Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.im_computer_foreground_warning), Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        ImCardButtons {
            ImCardOutlinedButton(stringResource(R.string.im_computer_decline), enabled = !answered,
                onClick = { answered = true; onApproval(tool.toolCallId, false) })
            ImCardButton(stringResource(R.string.im_computer_allow), enabled = !answered,
                onClick = { answered = true; onApproval(tool.toolCallId, true) })
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = { details = true }) {
                Text(stringResource(R.string.im_computer_approval_what), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (waiting.size > 1) TextButton(enabled = !answered, onClick = { answered = true; waiting.forEach { onApproval(it.toolCallId, true) } }) {
                Text(stringResource(R.string.im_computer_allow_all), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (details) ModalBottomSheet(onDismissRequest = { details = false }) {
        DefaultToolPreview(ToolUIContext(tool, tool.inputAsJson(), null, false))
    }
}

@Composable
internal fun ThreadComputerApprovalCard(
    tool: UIMessagePart.Tool,
    name: String,
    canOpen: Boolean,
    onScreen: () -> Unit,
    onApproval: (String, Boolean) -> Unit,
    compact: Boolean = false,
) {
    var answered by remember(tool.toolCallId) { mutableStateOf(false) }
    var details by remember(tool.toolCallId) { mutableStateOf(false) }
    ImActionCard(title = stringResource(R.string.im_computer_wants_to_operate, name), icon = HugeIcons.Computer, compact = compact,
        trailing = if (canOpen) ({
            IconButton(onClick = onScreen) { Icon(HugeIcons.ComputerVideo, stringResource(R.string.im_computer_view_screen), Modifier.size(20.dp)) }
        }) else null) {
        // The action itself; tapping it shows the exact arguments the partner sent.
        ImCardBlock(onClick = { details = true }) {
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(computerActionText(tool), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Icon(HugeIcons.ArrowRight01, stringResource(R.string.im_computer_details), Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        RelayApprovalNotice(tool)
        if (tool.interruptsOwner()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                Text(stringResource(R.string.im_computer_foreground_warning), Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        ImCardButtons {
            ImCardButton(stringResource(R.string.im_computer_allow), enabled = !answered,
                onClick = { answered = true; onApproval(tool.toolCallId, true) })
            ImCardOutlinedButton(stringResource(R.string.im_computer_decline), enabled = !answered,
                onClick = { answered = true; onApproval(tool.toolCallId, false) })
        }
    }
    if (details) ModalBottomSheet(onDismissRequest = { details = false }) {
        DefaultToolPreview(ToolUIContext(tool, tool.inputAsJson(), null, false))
    }
}

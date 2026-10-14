package me.ayuilos.miffan.ui.im.computer

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.remote.NearbySystem
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff

internal class SetupAction(val label: String, val enabled: Boolean = true, val onClick: () -> Unit)

/**
 * One screen of the setup, in the manner of a phone's own setup assistant: an optional picture, a
 * short title and one line under it, the content, and at most one main action with one quiet one
 * pinned at the bottom. Progress and errors sit right above the actions.
 */
@Composable
internal fun SetupScreen(
    title: String?,
    subtitle: String?,
    state: ComputerSetupState,
    onDismissError: () -> Unit,
    primary: SetupAction?,
    secondary: SetupAction? = null,
    /** Centre the title (screens led by a picture). */
    centered: Boolean = false,
    hero: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resources = LocalResources.current
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            hero?.let {
                Box(Modifier.fillMaxWidth().padding(top = 12.dp), contentAlignment = Alignment.Center) { it() }
            }
            if (title != null) Column(Modifier.fillMaxWidth().padding(top = if (hero == null) 8.dp else 0.dp, bottom = 8.dp),
                horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold,
                    textAlign = if (centered) TextAlign.Center else TextAlign.Start)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = if (centered) TextAlign.Center else TextAlign.Start)
                }
            }
            content()
        }
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp, top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            state.error?.let { error ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(bottom = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(computerErrorMessage(resources, error), Modifier.weight(1f).padding(vertical = 8.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                        TextButton(enabled = !state.busy, onClick = onDismissError) { Text(stringResource(R.string.im_computer_dismiss)) }
                    }
                }
            }
            primary?.let { action ->
                Button(onClick = action.onClick, enabled = action.enabled && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    if (state.busy) CircularProgressIndicator(Modifier.padding(end = 10.dp).size(18.dp), strokeWidth = 2.dp,
                        color = LocalContentColor.current)
                    Text(if (state.busy) stringResource(state.task?.label() ?: R.string.im_computer_working) else action.label,
                        style = MaterialTheme.typography.titleMedium)
                }
            }
            secondary?.let { action ->
                TextButton(onClick = action.onClick, enabled = action.enabled && !state.busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(action.label, style = MaterialTheme.typography.titleSmall) }
            }
        }
    }
}

internal fun ComputerSetupTask.label(): Int = when (this) {
    ComputerSetupTask.CREATING_KEY -> R.string.im_computer_task_creating_key
    ComputerSetupTask.LOOKING_UP -> R.string.im_computer_task_looking_up
    ComputerSetupTask.CONNECTING -> R.string.im_computer_task_connecting
    ComputerSetupTask.VERIFYING -> R.string.im_computer_task_verifying
    ComputerSetupTask.PREPARING -> R.string.im_computer_task_checking
    ComputerSetupTask.INSTALLING -> R.string.im_computer_installing_short
    ComputerSetupTask.CHECKING -> R.string.im_computer_partner_checking
    ComputerSetupTask.SAVING, ComputerSetupTask.BINDING -> R.string.im_computer_task_saving
    ComputerSetupTask.OPENING_SETTINGS -> R.string.im_computer_task_opening_settings
}

/** Where the user is among find, sign in, get ready and share. */
@Composable
internal fun StepDots(index: Int, count: Int = 4) {
    val label = stringResource(R.string.im_computer_step_progress, index + 1, count)
    Row(Modifier.semantics { contentDescription = label }, horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            Box(Modifier.height(6.dp).width(if (i == index) 20.dp else 6.dp).clip(CircleShape)
                .background(if (i <= index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant))
        }
    }
}

/** The mark a computer is shown with: its system's logo, or a plain computer. */
internal fun NearbySystem.icon(): ImageVector = when (this) {
    NearbySystem.MAC -> AppleLogo
    NearbySystem.WINDOWS -> WindowsLogo
    NearbySystem.LINUX -> LinuxLogo
    NearbySystem.UNKNOWN -> HugeIcons.Computer
}

@Composable
internal fun NearbySystem.label(detail: String? = null): String = when (this) {
    NearbySystem.MAC -> "Mac"
    NearbySystem.WINDOWS -> "Windows"
    NearbySystem.LINUX -> listOfNotNull("Linux", detail?.takeIf { it != "Linux" }).joinToString(" · ")
    NearbySystem.UNKNOWN -> stringResource(R.string.im_computer_kind_unknown)
}

@Composable
internal fun DeviceTile(icon: ImageVector, size: Dp = 48.dp, dimmed: Boolean = false) {
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3))
        .background(if (dimmed) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center) {
        Icon(icon, null, Modifier.size(size / 2),
            tint = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/**
 * A computer to pick: large, tappable, with its system's mark. [dimmed] for one that is not around
 * right now; [badges] follow the subtitle, [footer] sits under it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ComputerCard(
    name: String,
    subtitle: String?,
    icon: ImageVector,
    enabled: Boolean = true,
    dimmed: Boolean = false,
    badges: (@Composable () -> Unit)? = null,
    footer: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    Surface(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).padding(16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DeviceTile(icon, dimmed = dimmed)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    badges?.invoke()
                }
                footer?.let { Spacer(Modifier.height(4.dp)); it() }
            }
            Icon(HugeIcons.ArrowRight01, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A small tag naming a network a computer was found on. */
@Composable
internal fun NetworkTag(icon: ImageVector, label: String) {
    Surface(shape = RoundedCornerShape(11.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.heightIn(min = 22.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(icon, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A gentle caution inside a card, such as a found computer that may be a saved one's other system. */
@Composable
internal fun CardHint(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(HugeIcons.InformationCircle, null, Modifier.size(14.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

/** A soft pulse that says "looking". */
@Composable
internal fun SearchingPulse() {
    val transition = rememberInfiniteTransition(label = "searching")
    val pulse by transition.animateFloat(0.6f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse")
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(28.dp).scale(pulse).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)))
        Box(Modifier.size(18.dp).scale(pulse).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)))
        Box(Modifier.size(8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
    }
}

/** A white group of rows, like a settings list. */
@Composable
internal fun SetupGroup(
    modifier: Modifier = Modifier,
    /** Inside a card the group sits one step darker, so it still reads as fields. */
    nested: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(modifier, color = if (nested) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(if (nested) 14.dp else 20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).animateContentSize(), content = content)
    }
}

/** A labelled text row inside a [SetupGroup], as on a phone's sign-in sheet. */
@Composable
internal fun SetupField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean = true,
    placeholder: String? = null,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    divider: Boolean = false,
) {
    var visible by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    Column {
        // The whole row is the field: tapping the label starts typing too.
        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .clickable(enabled = enabled, interactionSource = null, indication = null) { focus.requestFocus(); keyboard?.show() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(label, Modifier.widthIn(min = 64.dp), style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                BasicTextField(value, onValueChange, Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = label }, enabled = enabled,
                    singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = if (password && !visible) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType,
                        autoCorrectEnabled = false))
            }
            if (password) IconButton(onClick = { visible = !visible }) {
                Icon(if (visible) HugeIcons.ViewOff else HugeIcons.View,
                    stringResource(if (visible) R.string.im_computer_hide_password else R.string.im_computer_show_password),
                    Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** A small icon and a quiet line, for reassurance or a hint under a group. */
@Composable
internal fun SetupNote(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A numbered instruction, its extra content (a command, buttons) under the words. */
@Composable
internal fun SetupInstruction(number: Int, text: String, content: @Composable ColumnScope.() -> Unit = {}) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$number", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            content()
        }
    }
}

/** A one-line command in a quiet box, cut off at the edge: the buttons beside it copy or send all of it. */
@Composable
internal fun CommandPreview(command: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp)) {
        Text(command, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal enum class CheckState {
    DONE, TODO, WAITING, LATER,
    /** Nice to have: a faint ring, but its action stays usable. */
    OPTIONAL,
}

/**
 * One item of the get-ready list: a tick when done, a ring while it needs the user, a spinner while
 * Miffan works on it, a faint ring for what comes later. [content] holds its action.
 */
@Composable
internal fun ChecklistRow(state: CheckState, title: String, detail: String? = null, divider: Boolean = true,
                          content: @Composable ColumnScope.() -> Unit = {}) {
    val faint = state == CheckState.LATER
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.padding(top = 1.dp).size(24.dp), contentAlignment = Alignment.Center) {
                when (state) {
                    CheckState.DONE -> Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center) {
                        Icon(HugeIcons.Tick01, stringResource(R.string.im_computer_ready), Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onPrimary)
                    }
                    CheckState.WAITING -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else -> Box(Modifier.size(22.dp).border(2.dp,
                        if (faint || state == CheckState.OPTIONAL) MaterialTheme.colorScheme.outlineVariant else MaterialTheme.colorScheme.primary, CircleShape))
                }
            }
            Column(Modifier.weight(1f).alpha(if (faint) 0.6f else 1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge)
                    detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (!faint) content()
            }
        }
        if (divider) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

/** The IM card: a rounded, low-contrast container for one group of content. */
@Composable
internal fun SetupCard(padding: Dp = 16.dp, spacing: Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

@Composable
internal fun SetupWarning(text: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(20.dp)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(HugeIcons.AlertCircle, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
            Text(text, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

/** A collapsed "more about this" row, so the main path stays short. */
@Composable
internal fun SetupDisclosure(title: String, initiallyExpanded: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Column {
        Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { expanded = !expanded }.padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Icon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, null, Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary)
        }
        AnimatedVisibility(expanded) { Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
    }
}

/**
 * Whether the computer's screen is used. Off suits a server or NAS without a display: partners
 * still use the computer through commands.
 */
@Composable
internal fun ComputerScreenSwitch(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.im_computer_use_screen), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(if (checked) R.string.im_computer_use_screen_on else R.string.im_computer_use_screen_off),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

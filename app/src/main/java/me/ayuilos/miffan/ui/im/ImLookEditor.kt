package me.ayuilos.miffan.ui.im

import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.MiffanColorSource
import me.ayuilos.miffan.data.model.MiffanKind
import me.ayuilos.miffan.data.model.MiffanMotionProfile
import me.ayuilos.miffan.data.model.MiffanPalette
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.components.ui.AvatarEmojiSheet
import me.ayuilos.miffan.ui.components.ui.description
import me.ayuilos.miffan.ui.components.ui.displayName
import me.ayuilos.miffan.ui.components.ui.miffanColors
import me.ayuilos.miffan.ui.components.ui.rememberAvatarImagePicker
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ImageAdd01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Smile

private enum class LookTab(@param:StringRes val label: Int) {
    KIND(R.string.im_look_tab_kind),
    COLOR(R.string.im_look_tab_color),
    MOTION(R.string.im_look_tab_motion),
}

/**
 * How a partner looks, on a page of its own: a live preview above tabs for its character, colours and
 * motion. Choices that don't apply to the current character say why instead of disappearing.
 * Nothing is kept until Done.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImLookEditor(name: String, initial: Avatar, onCancel: () -> Unit, onDone: (Avatar) -> Unit) {
    var avatarJson by rememberSaveable { mutableStateOf(Json.encodeToString(Avatar.serializer(), initial.forLook())) }
    val avatar = remember(avatarJson) { Json.decodeFromString(Avatar.serializer(), avatarJson) }
    // The last Miffan, so trying the whale or a photo and coming back keeps its colours and motion.
    var miffanJson by rememberSaveable { mutableStateOf(Json.encodeToString(Avatar.serializer(), initial.forLook() as? Avatar.Miffan ?: Avatar.Miffan())) }
    val lastMiffan = remember(miffanJson) { Json.decodeFromString(Avatar.serializer(), miffanJson) as Avatar.Miffan }
    val set: (Avatar) -> Unit = { picked ->
        avatarJson = Json.encodeToString(Avatar.serializer(), picked)
        if (picked is Avatar.Miffan) miffanJson = avatarJson
    }
    var tab by rememberSaveable { mutableStateOf(LookTab.KIND) }
    var choosingEmoji by remember { mutableStateOf(false) }
    val pickImage = rememberAvatarImagePicker(set)
    BackHandler(onBack = onCancel)

    Scaffold(topBar = {
        CenterAlignedTopAppBar(
            title = { Text(stringResource(R.string.im_look_title)) },
            navigationIcon = { TextButton(onClick = onCancel) { Text(stringResource(R.string.chat_page_cancel)) } },
            actions = { Button(onClick = { onDone(avatar) }, Modifier.padding(end = 8.dp)) { Text(stringResource(R.string.im_look_done)) } },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            ImLookStage(name, avatar)
            ImLookTabs(tab) { tab = it }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 24.dp)) {
                when (tab) {
                    LookTab.KIND -> ImKindGrid(avatar, lastMiffan, set, onPhoto = pickImage, onEmoji = { choosingEmoji = true })
                    LookTab.COLOR -> ImColorChoices(avatar, set)
                    LookTab.MOTION -> ImMotionChoices(avatar, set)
                }
            }
        }
    }
    if (choosingEmoji) AvatarEmojiSheet(onPick = set, onDismiss = { choosingEmoji = false })
}

/** The avatar on the edit and new-partner pages, with a way into [ImLookEditor] and what it looks like now. */
@Composable
internal fun ImLookEntry(name: String, avatar: Avatar, onOpen: () -> Unit, extra: @Composable RowScope.() -> Unit = {}) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AssistantAvatar(name, avatar, Modifier.size(112.dp), onClick = onOpen)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = onOpen) {
                Icon(HugeIcons.PaintBoard, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.im_look_title))
            }
            extra()
        }
        Text(lookSummary(avatar), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** What a look is, in a few words: the character, then its colours and motion when it has them. */
@Composable
internal fun lookSummary(avatar: Avatar): String = when (val look = avatar.forLook()) {
    is Avatar.Miffan -> listOf(
        look.appearance.kind.displayName,
        if (look.appearance.colorSource == MiffanColorSource.APP_THEME) stringResource(R.string.im_look_follow_short)
        else stringResource(look.appearance.palette.label),
        stringResource(look.motionProfile.label),
    ).joinToString(" · ")
    is Avatar.WhaleGirl -> stringResource(R.string.whale_name)
    is Avatar.Image -> stringResource(R.string.im_look_photo)
    is Avatar.Emoji -> stringResource(R.string.im_look_emoji)
    Avatar.Dummy -> ""
}

/** A partner that has never picked a look shows the default Miffan, so it is edited as one. */
private fun Avatar.forLook(): Avatar = if (this == Avatar.Dummy) Avatar.Miffan() else this

private val MiffanPalette.label: Int
    get() = when (this) {
        MiffanPalette.CLASSIC -> R.string.im_look_palette_classic
        MiffanPalette.MATCHA -> R.string.im_look_palette_matcha
        MiffanPalette.SAKURA -> R.string.im_look_palette_sakura
        MiffanPalette.MOONLIGHT -> R.string.im_look_palette_moonlight
        MiffanPalette.SEA_SALT -> R.string.im_look_palette_sea_salt
        MiffanPalette.INK_JADE -> R.string.im_look_palette_ink_jade
    }

private val MiffanMotionProfile.label: Int
    get() = when (this) {
        MiffanMotionProfile.LIVELY -> R.string.miffan_character_motion_lively
        MiffanMotionProfile.CALM -> R.string.miffan_character_motion_calm
        MiffanMotionProfile.CURIOUS -> R.string.miffan_character_motion_curious
    }

private val MiffanMotionProfile.detail: Int
    get() = when (this) {
        MiffanMotionProfile.LIVELY -> R.string.miffan_character_motion_lively_desc
        MiffanMotionProfile.CALM -> R.string.miffan_character_motion_calm_desc
        MiffanMotionProfile.CURIOUS -> R.string.miffan_character_motion_curious_desc
    }

/** The preview on a stage tinted with the look's own colours. */
@Composable
private fun ImLookStage(name: String, avatar: Avatar) {
    val colors = MaterialTheme.colorScheme
    val appearance = (avatar as? Avatar.Miffan)?.appearance
    val target = when {
        appearance == null -> colors.surfaceContainer
        appearance.colorSource == MiffanColorSource.APP_THEME -> colors.primaryContainer
        else -> lerp(appearance.palette.miffanColors().bowl, colors.surfaceContainerLow, 0.78f)
    }
    val tint by animateColorAsState(target, label = "look stage")
    Surface(color = tint, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
        Column(Modifier.padding(top = 20.dp, bottom = 14.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AssistantAvatar(name, avatar, Modifier.size(148.dp))
            Spacer(Modifier.height(2.dp))
            if (name.isNotBlank()) Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(lookSummary(avatar), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ImLookTabs(selected: LookTab, onSelect: (LookTab) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerHigh, shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.padding(4.dp).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LookTab.entries.forEach { tab ->
                val on = tab == selected
                Surface(color = if (on) colors.surface else Color.Transparent, shape = RoundedCornerShape(18.dp),
                    shadowElevation = if (on) 1.dp else 0.dp,
                    modifier = Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(18.dp))
                        .selectable(on, role = Role.Tab, onClick = { onSelect(tab) })) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(stringResource(tab.label), style = if (on) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
                            color = if (on) colors.onSurface else colors.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Every character to be: the Miffan kinds in the current colours, the whale, a photo or an emoji. */
@Composable
private fun ImKindGrid(avatar: Avatar, lastMiffan: Avatar.Miffan, onPick: (Avatar) -> Unit, onPhoto: () -> Unit, onEmoji: () -> Unit) {
    val miffan = avatar as? Avatar.Miffan ?: lastMiffan
    val choices: List<Any> = MiffanKind.entries + LookOther.entries
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        choices.chunked(3).forEach { row ->
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { choice ->
                    when (choice) {
                        is MiffanKind -> {
                            val look = miffan.copy(appearance = miffan.appearance.copy(kind = choice))
                            ImLookTile((avatar as? Avatar.Miffan)?.appearance?.kind == choice, choice.displayName,
                                choice.description.replace("\n", " · "), { onPick(look) }) { AssistantAvatar("", look, Modifier.size(60.dp)) }
                        }
                        LookOther.WHALE -> ImLookTile(avatar is Avatar.WhaleGirl, stringResource(R.string.whale_name),
                            stringResource(R.string.im_look_whale_desc), { onPick(avatar as? Avatar.WhaleGirl ?: Avatar.WhaleGirl()) }) {
                            AssistantAvatar("", Avatar.WhaleGirl(), Modifier.size(60.dp))
                        }
                        LookOther.PHOTO -> {
                            val photo = avatar as? Avatar.Image
                            ImLookTile(photo != null, stringResource(R.string.im_look_photo),
                                stringResource(if (photo != null) R.string.im_look_photo_again else R.string.im_look_photo_desc), onPhoto) {
                                if (photo != null) AssistantAvatar("", photo, Modifier.size(60.dp)) else ImLookGlyph(HugeIcons.ImageAdd01)
                            }
                        }
                        LookOther.EMOJI -> {
                            val emoji = avatar as? Avatar.Emoji
                            ImLookTile(emoji != null, stringResource(R.string.im_look_emoji),
                                stringResource(if (emoji != null) R.string.im_look_emoji_again else R.string.im_look_emoji_desc), onEmoji) {
                                if (emoji != null) AssistantAvatar("", emoji, Modifier.size(60.dp)) else ImLookGlyph(HugeIcons.Smile)
                            }
                        }
                    }
                }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private enum class LookOther { WHALE, PHOTO, EMOJI }

@Composable
private fun ImLookGlyph(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        shape = CircleShape, modifier = Modifier.size(60.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(26.dp)) }
    }
}

@Composable
private fun RowScope.ImLookTile(selected: Boolean, title: String, detail: String, onClick: () -> Unit, art: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = if (selected) colors.primary.copy(alpha = 0.12f).compositeOver(colors.surface) else colors.surface,
        border = if (selected) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
            .selectable(selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) { art() }
            Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Palettes drawn in the current kind, and following the app's colours instead. Miffan only. */
@Composable
private fun ImColorChoices(avatar: Avatar, onPick: (Avatar) -> Unit) {
    val miffan = avatar as? Avatar.Miffan
    if (miffan == null) {
        ImLookNote(if (avatar is Avatar.WhaleGirl) R.string.im_look_whale_colors else R.string.im_look_picture_colors)
        return
    }
    val appearance = miffan.appearance
    val follow = appearance.colorSource == MiffanColorSource.APP_THEME
    val colors = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        ImCardGroup {
            Column(Modifier.padding(vertical = 16.dp, horizontal = 8.dp).alpha(if (follow) 0.4f else 1f).selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MiffanPalette.entries.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEach { palette ->
                            val on = !follow && appearance.palette == palette
                            val look = miffan.copy(appearance = appearance.copy(palette = palette, colorSource = MiffanColorSource.PALETTE))
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                                .selectable(on, enabled = !follow, role = Role.RadioButton, onClick = { onPick(look) }).padding(vertical = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Box(Modifier.size(64.dp).border(2.dp, if (on) colors.primary else Color.Transparent, CircleShape).padding(4.dp)) {
                                    AssistantAvatar("", look, Modifier.fillMaxSize())
                                }
                                Text(stringResource(palette.label), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
        ImCardGroup {
            ImSwitchRow(stringResource(R.string.im_look_follow_app), follow, { on ->
                onPick(miffan.copy(appearance = appearance.copy(colorSource = if (on) MiffanColorSource.APP_THEME else MiffanColorSource.PALETTE)))
            }, supporting = stringResource(R.string.im_look_follow_app_desc))
        }
    }
}

/** How a Miffan moves; the preview above moves the chosen way. The whale and pictures have none to pick. */
@Composable
private fun ImMotionChoices(avatar: Avatar, onPick: (Avatar) -> Unit) {
    val miffan = avatar as? Avatar.Miffan
    if (miffan == null) {
        ImLookNote(if (avatar is Avatar.WhaleGirl) R.string.im_look_whale_motion else R.string.im_look_picture_motion)
        return
    }
    val colors = MaterialTheme.colorScheme
    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MiffanMotionProfile.entries.forEach { motion ->
            val on = miffan.motionProfile == motion
            Surface(
                color = if (on) colors.primary.copy(alpha = 0.12f).compositeOver(colors.surface) else colors.surface,
                border = if (on) BorderStroke(2.dp, colors.primary) else BorderStroke(1.dp, colors.outlineVariant),
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                    .selectable(on, role = Role.RadioButton, onClick = { onPick(miffan.copy(motionProfile = motion)) }),
            ) {
                Row(Modifier.heightIn(min = 64.dp).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(motion.label), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(motion.detail), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                    RadioButton(on, onClick = null)
                }
            }
        }
        Text(stringResource(R.string.im_look_motion_hint), Modifier.padding(horizontal = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun ImLookNote(@StringRes text: Int) {
    ImCardGroup {
        Text(stringResource(text), Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

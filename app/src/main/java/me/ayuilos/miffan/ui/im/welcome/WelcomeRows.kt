package me.ayuilos.miffan.ui.im.welcome

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.MiffanAppearance
import me.ayuilos.miffan.data.model.MiffanPalette
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.components.ui.AutoAIIcon
import me.ayuilos.miffan.ui.im.ImActionCard
import me.ayuilos.miffan.ui.im.ImCardBlock
import me.ayuilos.miffan.ui.im.ImCardBlockDivider
import me.ayuilos.miffan.ui.im.ImCardButton
import me.ayuilos.miffan.ui.im.ImCardButtons
import me.ayuilos.miffan.ui.im.ImCardOutlinedButton
import me.ayuilos.miffan.ui.im.ImChoiceQuiz
import me.ayuilos.miffan.ui.im.ImLookEditor
import me.ayuilos.miffan.ui.im.ImOption
import me.ayuilos.miffan.ui.im.ImOptionList
import me.ayuilos.miffan.ui.im.PARTNER_TRAITS
import me.ayuilos.miffan.ui.im.imPartnerTemplates
import me.ayuilos.miffan.ui.im.partnerPrompt
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Tick01
import kotlin.uuid.Uuid

@Composable
internal fun Words.text(): String = raw ?: stringResource(id, *args.toTypedArray())

/** Bubbles leave a gutter on the other side, as in a partner's chat. */
@Composable
private fun bubbleMaxWidth() = (LocalConfiguration.current.screenWidthDp * .86f).coerceAtMost(640f).dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun WelcomeRow(
    entry: WelcomeEntry,
    busy: Boolean,
    connected: Boolean,
    miffan: Assistant?,
    onOpenRouter: () -> Unit,
    onPickService: () -> Unit,
    onBackup: () -> Unit,
    onPro: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCancelLogin: () -> Unit,
    onReopenLogin: () -> Unit,
    onRetry: (KeyFailure) -> Unit,
    onUndoConnection: () -> Unit,
    onModel: (Long, Model) -> Unit,
    onAddress: (Long, ProviderSetting?, String, String) -> Unit,
    onPartner: (PartnerChoice, String) -> Unit,
    onDiy: () -> Unit,
    onLeaveDiy: () -> Unit,
    onBackupChoice: (Int) -> Unit,
    onGoPro: (Long) -> Unit,
    onStay: (Long) -> Unit,
) {
    when (entry) {
        is WelcomeEntry.Said -> WelcomeBubble(mine = false) { Text(entry.words.text(), style = MaterialTheme.typography.bodyLarge) }
        is WelcomeEntry.Typing -> WelcomeBubble(mine = false) { TypingDots() }
        is WelcomeEntry.Mine -> WelcomeBubble(mine = true) {
            // An echoed key is masked and shown in the key field's monospace.
            Text(entry.words.text(), style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (entry.words.raw != null) FontFamily.Monospace else null)
        }
        is WelcomeEntry.Note -> NoteLine(entry.words.text())
        is WelcomeEntry.Connected -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            NoteLine(entry.words.text(), Modifier.padding(top = 16.dp))
            if (entry.undoable) TextButton(onClick = onUndoConnection, enabled = !busy) { Text(stringResource(R.string.onboarding_undo_connect)) }
            else Spacer(Modifier.height(16.dp))
        }
        // Once a service is connected, connecting another is for Me › AI services.
        is WelcomeEntry.Connect -> ConnectCard(busy || connected, onOpenRouter, onPickService, onBackup, onPro)
        is WelcomeEntry.KeyAsk -> FlowRow(Modifier.padding(top = 2.dp)) {
            entry.url?.let { url ->
                TextButton(onClick = { onOpenUrl(url) }) {
                    Icon(HugeIcons.Link01, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.onboarding_key_link, entry.providerName))
                }
            }
            // Picked the wrong service: choose another without scrolling back up to the first card.
            if (entry.active) TextButton(onClick = onPickService, enabled = !busy) { Text(stringResource(R.string.onboarding_key_switch)) }
        }
        is WelcomeEntry.Progress -> ProgressCard(entry, busy, onCancelLogin, onReopenLogin, onRetry, onOpenUrl)
        is WelcomeEntry.AddressForm -> if (!entry.used) AddressCard(entry, busy, onAddress)
        is WelcomeEntry.ModelPick -> if (!entry.used) ModelPickCard(entry, busy, onPickService) { onModel(entry.key, it) }
        is WelcomeEntry.Partners -> if (!entry.used) PartnerCard(miffan, busy, onPartner, onDiy)
        is WelcomeEntry.Diy -> if (!entry.used) DiyCard(busy, onPartner, onLeaveDiy)
        is WelcomeEntry.Choices -> if (!entry.used) when (entry.kind) {
            ChoiceKind.BACKUP -> ChoiceChips(busy, listOf(R.string.onboarding_backup_file, R.string.onboarding_backup_webdav,
                R.string.onboarding_backup_s3, R.string.onboarding_backup_other), onPick = onBackupChoice)
            ChoiceKind.PRO -> ChoiceChips(busy, listOf(R.string.onboarding_pro_go, R.string.onboarding_pro_stay)) {
                if (it == R.string.onboarding_pro_go) onGoPro(entry.key) else onStay(entry.key)
            }
        }
    }
}

@Composable
private fun NoteLine(text: String, modifier: Modifier = Modifier.padding(vertical = 16.dp)) {
    Text(text, modifier.fillMaxWidth().padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
}

@Composable
private fun WelcomeBubble(mine: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Surface(Modifier.widthIn(max = bubbleMaxWidth()), shape = RoundedCornerShape(20.dp),
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
            Box(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) { content() }
        }
    }
}

@Composable
private fun TypingDots() {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(0f, 3f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "dots")
    Row(Modifier.height(24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { index ->
            Box(Modifier.size(7.dp).alpha(if (phase.toInt() == index) 1f else .35f)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
        }
    }
}

/** A card in Miffan's turn: the shared card frame, as wide as a bubble can be, so choices are easy to hit. */
@Composable
private fun WelcomeCard(title: String? = null, icon: ImageVector? = null, content: @Composable ColumnScope.() -> Unit) {
    ImActionCard(Modifier.padding(top = 8.dp).widthIn(max = bubbleMaxWidth()).fillMaxWidth(), title = title, icon = icon, content = content)
}

/** A row that does something right away, in a card's block. */
@Composable
private fun ActionRow(enabled: Boolean, onClick: () -> Unit, leading: @Composable () -> Unit, title: String, description: String?) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).alpha(if (enabled) 1f else .5f)
        .heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(HugeIcons.ArrowRight01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IconDisc(icon: ImageVector) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectCard(busy: Boolean, onOpenRouter: () -> Unit, onPickService: () -> Unit, onBackup: () -> Unit, onPro: () -> Unit) {
    WelcomeCard(stringResource(R.string.onboarding_connect_title), HugeIcons.Link01) {
        ImCardBlock {
            ActionRow(!busy, onOpenRouter, { AutoAIIcon("OpenRouter", Modifier.size(40.dp)) },
                stringResource(R.string.onboarding_connect_openrouter), stringResource(R.string.onboarding_connect_openrouter_desc))
            ImCardBlockDivider()
            ActionRow(!busy, onPickService, { IconDisc(HugeIcons.Key01) },
                stringResource(R.string.onboarding_connect_key), stringResource(R.string.onboarding_connect_key_desc))
        }
        // Side by side when they fit; longer translations put the second on a line of its own.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onBackup, enabled = !busy) { Text(stringResource(R.string.onboarding_connect_backup)) }
            TextButton(onClick = onPro, enabled = !busy) { Text(stringResource(R.string.onboarding_connect_pro)) }
        }
    }
}

@Composable
private fun ProgressCard(
    entry: WelcomeEntry.Progress,
    busy: Boolean,
    onCancelLogin: () -> Unit,
    onReopenLogin: () -> Unit,
    onRetry: (KeyFailure) -> Unit,
    onOpenUrl: (String) -> Unit,
) {
    WelcomeCard {
        ImCardBlock {
            entry.steps.forEachIndexed { index, step ->
                if (index > 0) ImCardBlockDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.padding(top = 2.dp).size(20.dp), contentAlignment = Alignment.Center) {
                        when (step.status) {
                            StepStatus.DOING -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            StepStatus.DONE -> Icon(HugeIcons.Tick01, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                            StepStatus.FAILED -> Icon(HugeIcons.Cancel01, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(step.label.text(), style = MaterialTheme.typography.bodyLarge,
                            color = if (step.status == StepStatus.FAILED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                        step.detail?.text()?.takeIf { it.isNotBlank() }?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
        if (entry.waitingForLogin) ImCardButtons {
            ImCardOutlinedButton(stringResource(R.string.cancel), onClick = onCancelLogin)
            ImCardButton(stringResource(R.string.onboarding_login_reopen), onClick = onReopenLogin)
        }
        entry.failure?.let { failure ->
            ImCardButtons {
                failure.siteUrl?.let { url ->
                    ImCardOutlinedButton(stringResource(R.string.onboarding_fail_site, failure.provider.name), onClick = { onOpenUrl(url) })
                }
                ImCardButton(stringResource(failure.retry.label), enabled = !busy, onClick = { onRetry(failure) })
            }
        }
    }
}

/** A service at an address of its own: the address and its key, checked like any other key. */
@Composable
private fun AddressCard(entry: WelcomeEntry.AddressForm, busy: Boolean, onSubmit: (Long, ProviderSetting?, String, String) -> Unit) {
    var address by rememberSaveable(entry.key) { mutableStateOf(entry.address) }
    var key by rememberSaveable(entry.key) { mutableStateOf("") }
    val valid = address.trim().let { it.startsWith("http://") || it.startsWith("https://") }
    val placeholder = (entry.provider as? ProviderSetting.OpenAI)?.baseUrl ?: "https://api.example.com/v1"
    WelcomeCard {
        OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.onboarding_form_address)) }, placeholder = { Text(placeholder) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.onboarding_form_key)) },
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
        ImCardButtons {
            ImCardButton(stringResource(R.string.onboarding_key_connect), enabled = valid && !busy,
                onClick = { onSubmit(entry.key, entry.provider, address, key) })
        }
    }
}

/**
 * Every chat model of the service, by name and without a recommendation: the user picks one, then
 * confirms. Long lists get a search field; "another service" stays at hand.
 */
@Composable
private fun ModelPickCard(entry: WelcomeEntry.ModelPick, busy: Boolean, onOtherService: () -> Unit, onChoose: (Model) -> Unit) {
    var query by rememberSaveable(entry.key) { mutableStateOf("") }
    var selectedId by rememberSaveable(entry.key) { mutableStateOf<String?>(null) }
    val shown = query.trim().lowercase().let { q ->
        if (q.isEmpty()) entry.models
        else entry.models.filter { q in it.modelId.lowercase() || q in it.displayName.lowercase() }
    }
    val free = stringResource(R.string.onboarding_model_free)
    WelcomeCard {
        if (entry.models.size > MODEL_SEARCH_FROM) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                shape = RoundedCornerShape(26.dp), leadingIcon = { Icon(HugeIcons.Search01, null, Modifier.size(20.dp)) },
                placeholder = { Text(stringResource(R.string.onboarding_model_search)) })
        }
        ImOptionList(shown.map { model ->
            val name = model.displayName.ifBlank { model.modelId }
            // The id tells apart models that share a display name across versions.
            ImOption(model.modelId, name, supporting = model.modelId.takeIf { it != name },
                tag = free.takeIf { model.modelId in entry.free })
        }, selected = setOfNotNull(selectedId), onSelect = { selectedId = it }, enabled = !busy, maxHeight = 360.dp)
        ImCardButtons {
            ImCardOutlinedButton(stringResource(R.string.onboarding_key_switch), enabled = !busy, onClick = onOtherService)
            ImCardButton(stringResource(R.string.onboarding_model_confirm), enabled = selectedId != null && !busy,
                onClick = { entry.models.firstOrNull { it.modelId == selectedId }?.let(onChoose) })
        }
    }
}

private const val MODEL_SEARCH_FROM = 6

/** One choice of the first partner, as the card lists it. */
private class PartnerOption(val key: String, val name: String, val description: String, val avatar: Avatar, val choice: PartnerChoice)

/** Tapping a partner selects it; nothing happens until "that's the one", so a slip of the finger costs nothing. */
@Composable
private fun PartnerCard(miffan: Assistant?, busy: Boolean, onPick: (PartnerChoice, String) -> Unit, onDiy: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    // The first partner is someone to talk to; the bug reporter waits in the Partners tab.
    val templates = remember(locale) { imPartnerTemplates(locale).filter { LocalToolOption.BugReport !in it.localTools } }
    val whaleName = stringResource(R.string.whale_name)
    val options = listOf(
        PartnerOption("miffan", ImWelcomeVM.MIFFAN_NAME, stringResource(R.string.onboarding_partner_miffan_desc),
            miffan?.avatar ?: Avatar.Miffan(), PartnerChoice.Miffan),
        PartnerOption("whale", whaleName, stringResource(R.string.onboarding_partner_whale_desc, stringResource(R.string.whale_card_tagline)),
            Avatar.WhaleGirl(), PartnerChoice.Whale(whaleName)),
    ) + templates.map { PartnerOption(it.id.toString(), it.name, it.description, it.avatar, PartnerChoice.New(it.assistant(), madeHere = false)) }
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = options.firstOrNull { it.key == selectedKey }
    WelcomeCard {
        ImOptionList(options.map { option ->
            ImOption(option.key, option.name, supporting = option.description,
                leading = { AssistantAvatar(option.name, option.avatar, Modifier.size(40.dp)) })
        }, selected = setOfNotNull(selectedKey), onSelect = { selectedKey = it }, enabled = !busy)
        ImCardButtons {
            ImCardButton(stringResource(R.string.onboarding_diy_finish), enabled = selected != null && !busy,
                onClick = { selected?.let { onPick(it.choice, it.name) } })
        }
        ImCardBlock {
            ActionRow(!busy, onDiy, { IconDisc(HugeIcons.Add01) },
                stringResource(R.string.onboarding_partner_diy), stringResource(R.string.onboarding_partner_diy_desc))
        }
    }
}

/** Making a partner in the chat: the same name, look and traits as Partners › Add › Make your own. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiyCard(busy: Boolean, onPick: (PartnerChoice, String) -> Unit, onBack: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var avatarJson by rememberSaveable { mutableStateOf(Json.encodeToString(Avatar.serializer(), Avatar.Miffan())) }
    val avatar = remember(avatarJson) { Json.decodeFromString(Avatar.serializer(), avatarJson) }
    var traits by rememberSaveable { mutableStateOf(listOf<Int>()) }
    var extra by rememberSaveable { mutableStateOf("") }
    var lookOpen by rememberSaveable { mutableStateOf(false) }
    val trimmed = name.trim()
    val prompt = partnerPrompt(trimmed, traits, extra)
    val traitNames = traits.map { stringResource(it) }
    val setAvatar = { picked: Avatar -> avatarJson = Json.encodeToString(Avatar.serializer(), picked) }

    WelcomeCard {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // The partner as it will look, updated while it is made.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                AssistantAvatar(trimmed, avatar, Modifier.size(56.dp))
                Column(Modifier.weight(1f)) {
                    Text(trimmed.ifEmpty { stringResource(R.string.onboarding_diy_name_placeholder) }, style = MaterialTheme.typography.titleMedium,
                        color = if (trimmed.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    Text(traitNames.joinToString(" · ").ifEmpty { stringResource(R.string.onboarding_diy_no_traits) },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(stringResource(R.string.onboarding_diy_look), style = MaterialTheme.typography.titleSmall)
            val miffanLook = (avatar as? Avatar.Miffan)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically) {
                MiffanPalette.entries.forEach { palette ->
                    val selected = miffanLook?.appearance?.palette == palette
                    val look = Avatar.Miffan(appearance = (miffanLook?.appearance ?: MiffanAppearance()).copy(palette = palette))
                    Box(Modifier.size(44.dp).clip(CircleShape)
                        .border(if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(0.dp, MaterialTheme.colorScheme.surface), CircleShape)
                        .clickable { setAvatar(miffanLook?.copy(appearance = look.appearance) ?: look) }
                        .padding(3.dp)) {
                        AssistantAvatar(trimmed, look, Modifier.fillMaxSize())
                    }
                }
                OutlinedButton(onClick = { lookOpen = true }, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Text(stringResource(R.string.onboarding_diy_more))
                }
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.im_add_partner_name)) },
                placeholder = { Text(stringResource(R.string.im_add_partner_name_hint)) })
            Text(stringResource(R.string.im_add_partner_traits), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PARTNER_TRAITS.forEach { trait ->
                    val on = trait in traits
                    FilterChip(selected = on, onClick = { traits = if (on) traits - trait else traits + trait },
                        label = { Text(stringResource(trait)) })
                }
            }
            OutlinedTextField(extra, { extra = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                label = { Text(stringResource(R.string.im_add_partner_extra)) },
                placeholder = { Text(stringResource(R.string.im_add_partner_extra_hint)) })
            ImCardButtons {
                ImCardOutlinedButton(stringResource(R.string.onboarding_diy_back), enabled = !busy, onClick = onBack)
                ImCardButton(stringResource(R.string.onboarding_diy_finish), enabled = trimmed.isNotEmpty() && !busy, onClick = {
                    val partner = Assistant(id = Uuid.random(), name = trimmed, systemPrompt = prompt, avatar = avatar, useAssistantAvatar = true)
                    onPick(PartnerChoice.New(partner, madeHere = true), trimmed)
                })
            }
        }
    }
    if (lookOpen) Dialog(onDismissRequest = { lookOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        ImLookEditor(trimmed, avatar, onCancel = { lookOpen = false }) { picked ->
            setAvatar(picked)
            lookOpen = false
        }
    }
}

/** Short answers to Miffan's question, as a quiz under its bubble. */
@Composable
private fun ChoiceChips(busy: Boolean, labels: List<Int>, onPick: (Int) -> Unit) {
    ImChoiceQuiz(null, labels.map { stringResource(it) }, answer = null, onPick = { onPick(labels[it]) },
        modifier = Modifier.padding(top = 4.dp).widthIn(max = bubbleMaxWidth()).fillMaxWidth(), enabled = !busy, typeHint = false)
}

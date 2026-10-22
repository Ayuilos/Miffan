package me.ayuilos.miffan.ui.im

import androidx.activity.compose.BackHandler
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.MiffanAppearance
import me.ayuilos.miffan.data.model.MiffanKind
import me.ayuilos.miffan.data.model.MiffanPalette
import me.ayuilos.miffan.data.model.MiffanMotionProfile
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.components.ui.MiffanMascot
import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import me.ayuilos.miffan.ui.components.ui.displayName
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Shuffle
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowRight01
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

private enum class AddStep { PICK, CUSTOM, DONE }

private val TRAITS = listOf(
    R.string.im_trait_gentle, R.string.im_trait_direct, R.string.im_trait_funny, R.string.im_trait_curious,
    R.string.im_trait_rigorous, R.string.im_trait_brief, R.string.im_trait_encouraging, R.string.im_trait_snarky,
)

/**
 * Adding a partner, one decision per screen: make one from a name, a colour and a few traits, or take
 * a template; either way it ends on the new partner with a way to say hello.
 */
@Composable
fun ImAddPartnerPage(custom: Boolean) {
    val nav = LocalNavController.current
    val settingsStore: SettingsStore = koinInject()
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val templates = imPartnerTemplates(LocalConfiguration.current.locales[0])
    var step by rememberSaveable { mutableStateOf(if (custom) AddStep.CUSTOM else AddStep.PICK) }
    var addedId by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val added = addedId?.let { id -> settings.assistants.firstOrNull { it.id.toString() == id } }

    fun add(partner: Assistant) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                settingsStore.update { current ->
                    if (current.assistants.any { it.id == partner.id }) current else current.copy(assistants = current.assistants + partner)
                }
                addedId = partner.id.toString()
                step = AddStep.DONE
            } finally { busy = false }
        }
    }
    // Leave the flow for a page of the new partner, so back returns to where adding started.
    fun leaveTo(screen: Screen) { nav.replace(Screen.AddPartner(custom), screen, Screen.Home) }

    // Opened straight into making one, back leaves; otherwise it returns to the choice.
    BackHandler(enabled = step == AddStep.CUSTOM && !custom) { step = AddStep.PICK }
    when (step) {
        AddStep.PICK -> Scaffold(topBar = { ImPageBar(stringResource(R.string.im_partners_add)) }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(onClick = { step = AddStep.CUSTOM }, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary) {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f), modifier = Modifier.size(52.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(HugeIcons.Add01, null) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(stringResource(R.string.im_add_partner_custom), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.im_add_partner_custom_desc), style = MaterialTheme.typography.bodySmall)
                        }
                        Icon(HugeIcons.ArrowRight01, null)
                    }
                }
                ImSectionLabel(stringResource(R.string.im_add_partner_templates))
                ImCardGroup {
                    templates.forEachIndexed { index, template ->
                        if (index > 0) ImCardDivider(start = 82.dp)
                        val exists = settings.assistants.any { it.id == template.id }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            AssistantAvatar(template.name, template.avatar, Modifier.size(52.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(template.name, style = MaterialTheme.typography.titleMedium)
                                Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (exists) Text(stringResource(R.string.im_p5_added), style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else FilledTonalButton(onClick = { add(template.assistant()) }, enabled = !busy,
                                contentPadding = PaddingValues(horizontal = 16.dp)) { Text(stringResource(R.string.im_partners_add_short)) }
                        }
                    }
                }
                Text(stringResource(R.string.im_add_partner_footer), Modifier.padding(horizontal = 4.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AddStep.CUSTOM -> ImCustomPartnerStep(busy, onBack = { if (custom) nav.popBackStack() else step = AddStep.PICK }, onDone = ::add)
        AddStep.DONE -> Scaffold { padding ->
            val partner = added
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 28.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
                if (partner != null) {
                    AssistantAvatar(partner.name, partner.avatar, Modifier.size(128.dp))
                    Text(stringResource(R.string.im_add_partner_arrived, partnerName(partner)), style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    Text(stringResource(R.string.im_add_partner_arrived_desc), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { leaveTo(Screen.Thread(partner.id.toString())) }, Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 52.dp)) {
                        Text(stringResource(R.string.im_add_partner_say_hi))
                    }
                    TextButton(onClick = { leaveTo(Screen.PartnerEdit(partner.id.toString())) }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.im_add_partner_edit_first))
                    }
                    TextButton(onClick = { step = AddStep.PICK }) {
                        Text(stringResource(R.string.im_add_partner_more), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Look, name and traits; the traits become the new partner's personality. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImCustomPartnerStep(busy: Boolean, onBack: () -> Unit, onDone: (Assistant) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var avatarJson by rememberSaveable { mutableStateOf(Json.encodeToString(Avatar.serializer(), Avatar.Miffan())) }
    val avatar = remember(avatarJson) { Json.decodeFromString(Avatar.serializer(), avatarJson) }
    var lookOpen by rememberSaveable { mutableStateOf(false) }
    var traits by rememberSaveable { mutableStateOf(listOf(R.string.im_trait_gentle)) }
    var extra by rememberSaveable { mutableStateOf("") }
    val traitNames = traits.map { stringResource(it) }
    val separator = stringResource(R.string.im_list_separator)
    val trimmed = name.trim()
    val prompt = listOf(
        if (traitNames.isEmpty()) stringResource(R.string.im_add_partner_prompt_plain, trimmed)
        else stringResource(R.string.im_add_partner_prompt, trimmed, traitNames.joinToString(separator)),
        extra.trim(),
    ).filter { it.isNotEmpty() }.joinToString("\n")

    if (lookOpen) {
        ImLookEditor(trimmed, avatar, onCancel = { lookOpen = false }) { picked ->
            avatarJson = Json.encodeToString(Avatar.serializer(), picked)
            lookOpen = false
        }
        return
    }
    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_add_partner_new), onBack = onBack) }, bottomBar = {
        Box(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            Button(enabled = trimmed.isNotEmpty() && !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), onClick = {
                onDone(Assistant(id = Uuid.random(), name = trimmed, systemPrompt = prompt, avatar = avatar, useAssistantAvatar = true))
            }) { Text(stringResource(R.string.im_add_partner_finish)) }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ImLookEntry(trimmed, avatar, onOpen = { lookOpen = true }) {
                    OutlinedButton(onClick = { avatarJson = Json.encodeToString(Avatar.serializer(), randomMiffan(avatar)) }) {
                        Icon(HugeIcons.Shuffle, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.im_look_shuffle))
                    }
                }
            }
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.im_add_partner_name)) },
                placeholder = { Text(stringResource(R.string.im_add_partner_name_hint)) })
            Text(stringResource(R.string.im_add_partner_traits), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TRAITS.forEach { trait ->
                    val on = trait in traits
                    FilterChip(selected = on, onClick = { traits = if (on) traits - trait else traits + trait },
                        label = { Text(stringResource(trait)) })
                }
            }
            OutlinedTextField(extra, { extra = it }, Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), minLines = 3, maxLines = 6,
                label = { Text(stringResource(R.string.im_add_partner_extra)) },
                placeholder = { Text(stringResource(R.string.im_add_partner_extra_hint)) })
        }
    }
}

/** A Miffan unlike [current] in kind and colours, with any motion, for a partner nobody wants to dress up by hand. */
private fun randomMiffan(current: Avatar): Avatar.Miffan {
    val now = (current as? Avatar.Miffan)?.appearance
    return Avatar.Miffan(
        appearance = MiffanAppearance(
            kind = MiffanKind.entries.filter { it != now?.kind }.random(),
            palette = MiffanPalette.entries.filter { it != now?.palette }.random(),
        ),
        motionProfile = MiffanMotionProfile.entries.random(),
    )
}

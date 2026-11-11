package me.ayuilos.miffan.ui.im.thread

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.appsettings.CONNECT_PROVIDER_TOOL
import me.ayuilos.miffan.data.appsettings.HeaderTarget
import me.ayuilos.miffan.data.appsettings.SECRET_CARD_TOOLS
import me.ayuilos.miffan.data.appsettings.SearchTarget
import me.ayuilos.miffan.data.appsettings.VoiceKind
import me.ayuilos.miffan.data.appsettings.VoiceSampler
import me.ayuilos.miffan.data.appsettings.VoiceTarget
import me.ayuilos.miffan.data.appsettings.asrApiKey
import me.ayuilos.miffan.data.appsettings.displayName
import me.ayuilos.miffan.data.appsettings.previewSaved
import me.ayuilos.miffan.data.appsettings.ttsApiKey
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.hugeicons.stroke.VolumeHigh
import me.rerere.hugeicons.stroke.Settings03
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.appsettings.resolveSecretTarget
import me.ayuilos.miffan.data.appsettings.searchApiKey
import me.rerere.hugeicons.stroke.Key01
import me.rerere.hugeicons.stroke.Search01
import me.ayuilos.miffan.data.appsettings.ConnectTarget
import me.ayuilos.miffan.data.appsettings.ConnectTargetResult
import me.ayuilos.miffan.data.appsettings.ProviderConnectCards
import me.ayuilos.miffan.data.appsettings.maskedKey
import me.ayuilos.miffan.data.appsettings.resolveConnectTarget
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.ui.components.ui.AutoAIIcon
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.ImActionCard
import me.ayuilos.miffan.ui.im.ImCardButton
import me.ayuilos.miffan.ui.im.ImCardButtons
import me.ayuilos.miffan.ui.im.ImCardIcon
import me.ayuilos.miffan.ui.im.ImCardOutlinedButton
import me.ayuilos.miffan.ui.im.partnerName
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.ArrowUpRight01
import me.rerere.hugeicons.stroke.CloudServer
import me.rerere.hugeicons.stroke.LockPassword
import me.rerere.hugeicons.stroke.Shield01
import me.rerere.hugeicons.stroke.Tick01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.ViewOff
import me.rerere.hugeicons.stroke.Wifi01
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/** The service a connect_provider call names, for titles and records. */
@Composable
internal fun connectProviderName(tool: UIMessagePart.Tool): String {
    val settings = LocalSettings.current
    return remember(tool.input, settings.providers) {
        when (val resolved = resolveSecretTarget(tool.toolName, tool.inputAsJson(), settings)) {
            is ConnectTargetResult.Ok -> resolved.target.name
            is ConnectTargetResult.Invalid -> null
        }
    } ?: stringResource(R.string.im_connect_unknown_service)
}

/**
 * The secure key card. What the owner types stays in this card and goes only to [ProviderConnectCards], which checks
 * it with the one service shown and stores it there; the partner hears the outcome, never the key. The card cannot
 * be answered from anywhere else, and the screen cannot be recorded while it is up.
 */
@Composable
internal fun ThreadConnectProviderCard(
    tool: UIMessagePart.Tool,
    assistant: Assistant?,
    conversationId: String?,
    onApproval: (String, Boolean) -> Unit,
) {
    val settings = LocalSettings.current
    val cards = koinInject<ProviderConnectCards>()
    val phases by cards.phases.collectAsStateWithLifecycle()
    val phase = phases[tool.toolCallId]
    // Fixed when the card first shows: saving checks the service still matches what the owner saw.
    val resolved = remember(tool.toolCallId) { resolveSecretTarget(tool.toolName, tool.inputAsJson(), settings) }
    val target = (resolved as? ConnectTargetResult.Ok)?.target
    val partner = if (assistant != null) partnerName(assistant) else stringResource(R.string.im_connect_partner_fallback)
    val conversation = remember(conversationId) { conversationId?.let { runCatching { Uuid.parse(it) }.getOrNull() } }
    val checking = phase == ProviderConnectCards.Phase.Checking
    var key by remember(tool.toolCallId) { mutableStateOf("") }
    var shown by remember(tool.toolCallId) { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current

    SecureWindowWhileShown()
    // A key the owner typed into the chat box and moved here: fill it in and check it right away.
    val handoffs by cards.handoffs.collectAsStateWithLifecycle()
    val handedOff = tool.toolCallId in handoffs

    fun cancel(reason: String = "") {
        if (conversation != null) cards.cancel(conversation, tool.toolCallId, reason) else onApproval(tool.toolCallId, false)
    }

    fun connect(value: String) {
        if (target != null && conversation != null && value.isNotBlank()) cards.connect(conversation, tool.toolCallId, target, value)
    }

    LaunchedEffect(handedOff) {
        if (handedOff) cards.takeHandoff(tool.toolCallId)?.let { handed -> key = handed; connect(handed) }
    }

    val rekey = (target as? ConnectTarget.Existing)?.hasKey == true || (target as? SearchTarget)?.hasKey == true ||
        (target as? VoiceTarget)?.hasKey == true
    ImActionCard(
        title = when (target) {
            null -> stringResource(R.string.im_connect_title_unknown)
            is HeaderTarget -> stringResource(R.string.im_secret_header_title, target.name)
            else -> stringResource(if (rekey) R.string.im_connect_title_rekey else R.string.im_connect_title, target.name)
        },
        leading = {
            when (target) {
                is ConnectTarget.Existing -> AutoAIIcon(target.name, Modifier.size(40.dp))
                is SearchTarget -> ImCardIcon(HugeIcons.Search01)
                is HeaderTarget -> ImCardIcon(HugeIcons.Key01)
                is VoiceTarget -> ImCardIcon(if (target.kind == VoiceKind.TTS) HugeIcons.VolumeHigh else HugeIcons.Mic01)
                else -> ImCardIcon(HugeIcons.CloudServer)
            }
        },
    ) {
        if (target == null) {
            Text(stringResource(R.string.im_connect_invalid, partner), style = MaterialTheme.typography.bodyMedium)
            ImCardButtons { ImCardOutlinedButton(stringResource(R.string.im_connect_cancel), onClick = { cancel() }) }
            return@ImActionCard
        }
        OriginBadge(target.origin)
        CardNote(HugeIcons.Shield01, MaterialTheme.colorScheme.primary, when {
            target is HeaderTarget -> stringResource(R.string.im_secret_header_privacy, partner, target.header)
            target is ConnectTarget.Custom -> stringResource(R.string.im_connect_privacy_custom, partner)
            rekey -> stringResource(R.string.im_connect_privacy_rekey, partner)
            else -> stringResource(R.string.im_connect_privacy, partner)
        })
        // The sample is how the owner knows the key works; say so before they paste it.
        if (target is VoiceTarget && target.kind == VoiceKind.TTS) {
            // A model or voice set with the key is not a secret: show it, so the owner knows what the sample will use.
            val setup = listOfNotNull(
                target.model?.let { stringResource(R.string.im_voice_setup_model, it) },
                target.voice?.let { stringResource(R.string.im_voice_setup_voice, it) },
            )
            if (setup.isNotEmpty()) CardNote(HugeIcons.Settings03, MaterialTheme.colorScheme.onSurfaceVariant, setup.joinToString(" · "))
            CardNote(HugeIcons.VolumeHigh, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.im_voice_sample_note))
        }
        if (conversation == null) {
            CardNote(HugeIcons.LockPassword, MaterialTheme.colorScheme.onSurfaceVariant, stringResource(R.string.im_connect_phone_only))
            ImCardButtons { ImCardOutlinedButton(stringResource(if (target is HeaderTarget) R.string.im_secret_cancel else R.string.im_connect_cancel), onClick = { cancel() }) }
            return@ImActionCard
        }
        if (phase == ProviderConnectCards.Phase.Saved) {
            CardNote(HugeIcons.Tick01, MaterialTheme.colorScheme.primary, stringResource(R.string.im_connect_done, target.name))
            return@ImActionCard
        }
        if (phase == ProviderConnectCards.Phase.TargetChanged) {
            CardNote(HugeIcons.Alert02, MaterialTheme.colorScheme.error, stringResource(R.string.im_connect_target_changed))
            ImCardButtons { ImCardOutlinedButton(stringResource(if (target is HeaderTarget) R.string.im_secret_cancel else R.string.im_connect_cancel), onClick = { cancel() }) }
            return@ImActionCard
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                modifier = Modifier.weight(1f),
                enabled = !checking,
                singleLine = true,
                isError = phase == ProviderConnectCards.Phase.KeyRejected,
                shape = MaterialTheme.shapes.medium,
                placeholder = { Text(stringResource(if (target is HeaderTarget) R.string.im_secret_header_hint else R.string.im_connect_key_hint)) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (shown) VisualTransformation.None else PasswordVisualTransformation(),
                // A password field: keyboards neither suggest nor learn what is typed.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                keyboardActions = KeyboardActions(onDone = { connect(key) }),
                trailingIcon = {
                    IconButton(onClick = { shown = !shown }) {
                        Icon(if (shown) HugeIcons.ViewOff else HugeIcons.View,
                            stringResource(if (shown) R.string.im_connect_hide_key else R.string.im_connect_show_key))
                    }
                },
            )
            Button(
                onClick = {
                    if (key.isNotBlank()) connect(key)
                    else context.takeClipboardText()?.let { pasted ->
                        key = pasted
                        connect(pasted)
                    }
                },
                enabled = !checking,
                modifier = Modifier.heightIn(min = 56.dp),
            ) {
                if (checking) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer8()
                    Text(stringResource(R.string.im_connect_checking))
                } else Text(stringResource(when {
                    key.isBlank() -> R.string.onboarding_key_paste
                    target is HeaderTarget -> R.string.im_secret_save
                    else -> R.string.onboarding_key_connect
                }))
            }
        }
        when (phase) {
            ProviderConnectCards.Phase.KeyRejected ->
                CardNote(HugeIcons.Alert02, MaterialTheme.colorScheme.error, stringResource(R.string.im_connect_rejected, target.name))
            is ProviderConnectCards.Phase.ServiceError ->
                CardNote(HugeIcons.Alert02, MaterialTheme.colorScheme.error, stringResource(R.string.im_connect_service_error, target.name, phase.detail))
            ProviderConnectCards.Phase.Unreachable -> {
                CardNote(HugeIcons.Wifi01, MaterialTheme.colorScheme.tertiary, stringResource(R.string.im_connect_unreachable, target.origin))
                ImCardButtons {
                    ImCardOutlinedButton(stringResource(R.string.im_connect_save_anyway),
                        onClick = { cards.saveUnchecked(conversation, tool.toolCallId, target, key) })
                    ImCardButton(stringResource(R.string.im_connect_retry), onClick = { connect(key) })
                }
            }
            else -> Unit
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            val keyUrl = (target as? ConnectTarget.Existing)?.keyUrl ?: (target as? SearchTarget)?.service?.keyUrl ?: (target as? VoiceTarget)?.keyUrl
            when {
                target is ConnectTarget.Custom -> {
                    val wrong = stringResource(R.string.im_connect_wrong_address)
                    TextButton(onClick = { cancel(wrong) }, enabled = !checking, modifier = Modifier.weight(1f, fill = false)) {
                        Text(wrong, color = MaterialTheme.colorScheme.error)
                    }
                }
                // Long service names wrap here instead of pushing the cancel button off the card.
                keyUrl != null -> TextButton(onClick = { uriHandler.openUri(keyUrl) }, modifier = Modifier.weight(1f, fill = false)) {
                    Text(stringResource(R.string.im_connect_get_key, target.name), Modifier.weight(1f, fill = false))
                    Spacer8()
                    Icon(HugeIcons.ArrowUpRight01, null, Modifier.size(16.dp))
                }
                else -> Unit
            }
            TextButton(onClick = { cancel() }, enabled = !checking) {
                Text(stringResource(if (target is HeaderTarget) R.string.im_secret_cancel else R.string.im_connect_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** The settled card: the service, connected, and the key masked. Only the owner sees the mask; the partner never did. */
@Composable
internal fun ThreadConnectRecord(tool: UIMessagePart.Tool) {
    val answered = tool.approvalState as? ToolApprovalState.Answered ?: return
    val settings = LocalSettings.current
    val result = remember(answered.answer) { runCatching { Json.parseToJsonElement(answered.answer).jsonObject }.getOrNull() } ?: return
    val providerId = result["provider_id"]?.jsonPrimitive?.contentOrNull
    val provider = remember(settings.providers, providerId) { settings.providers.firstOrNull { it.id.toString() == providerId } }
    val searchId = result["search_service_id"]?.jsonPrimitive?.contentOrNull
    val search = remember(settings.searchServices, searchId) { settings.searchServices.firstOrNull { it.id.toString() == searchId } }
    val header = result["header"]?.jsonPrimitive?.contentOrNull
    val voiceId = result["voice_service_id"]?.jsonPrimitive?.contentOrNull
    val voiceKind = result["kind"]?.jsonPrimitive?.contentOrNull
    val tts = remember(settings.ttsProviders, voiceId) { settings.ttsProviders.firstOrNull { it.id.toString() == voiceId } }
    val asr = remember(settings.asrProviders, voiceId) { settings.asrProviders.firstOrNull { it.id.toString() == voiceId } }
    val name = provider?.name ?: search?.displayName ?: tts?.displayName() ?: asr?.displayName()
        ?: (result["provider_name"] ?: result["name"])?.jsonPrimitive?.contentOrNull.orEmpty()
    val checked = result["status"]?.jsonPrimitive?.contentOrNull == "connected"
    val models = result["model_count"]?.jsonPrimitive?.intOrNull
    val currentKey = provider?.currentKey() ?: search?.searchApiKey() ?: tts?.ttsApiKey() ?: asr?.asrApiKey()
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                header != null -> Icon(HugeIcons.Key01, null, Modifier.size(32.dp).padding(4.dp))
                voiceId != null -> Icon(if (voiceKind == "asr") HugeIcons.Mic01 else HugeIcons.VolumeHigh, null, Modifier.size(32.dp).padding(4.dp))
                searchId != null -> Icon(HugeIcons.Search01, null, Modifier.size(32.dp).padding(4.dp))
                else -> AutoAIIcon(name, Modifier.size(32.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(when {
                        header != null -> stringResource(R.string.im_secret_header_saved, name, header)
                        voiceKind == "asr" -> stringResource(R.string.im_voice_asr_saved, name)
                        else -> stringResource(if (checked) R.string.im_connect_done else R.string.im_connect_saved_unchecked, name)
                    },
                        style = MaterialTheme.typography.bodyMedium)
                    if (checked || header != null) Icon(HugeIcons.Tick01, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                }
                currentKey?.takeIf { it.isNotBlank() }?.let {
                    Text(maskedKey(it), style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // Hear the voice again: read by the saved service, so the key never comes near the card.
            if (tts != null) VoiceSampleButton(tts.id)
            if (checked && models != null) Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                Text(pluralStringResource(R.plurals.im_connect_models, models, models), Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun VoiceSampleButton(serviceId: Uuid) {
    val sampler = koinInject<VoiceSampler>()
    val store = koinInject<me.ayuilos.miffan.data.datastore.SettingsStore>()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var playing by remember(serviceId) { mutableStateOf(false) }
    var failed by remember(serviceId) { mutableStateOf(false) }
    TextButton(enabled = !playing, onClick = {
        playing = true
        scope.launch {
            failed = !sampler.previewSaved(store, serviceId)
            playing = false
        }
    }) {
        if (playing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Icon(if (failed) HugeIcons.Alert02 else HugeIcons.VolumeHigh, null, Modifier.size(18.dp))
        Spacer8()
        Text(stringResource(if (failed) R.string.im_voice_sample_failed else R.string.im_voice_sample_play))
    }
}

internal fun UIMessagePart.Tool.isConnectRecord(): Boolean =
    toolName in SECRET_CARD_TOOLS && approvalState is ToolApprovalState.Answered

private fun ProviderSetting.currentKey(): String = when (this) {
    is ProviderSetting.OpenAI -> apiKey
    is ProviderSetting.Google -> apiKey
    is ProviderSetting.Claude -> apiKey
}

@Composable
private fun OriginBadge(origin: String) {
    val plain = origin.startsWith("http://")
    Surface(color = if (plain) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.extraLarge) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(HugeIcons.LockPassword, null, Modifier.size(14.dp))
            Text(origin, style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace))
        }
    }
}

@Composable
private fun CardNote(icon: ImageVector, tint: androidx.compose.ui.graphics.Color, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, null, Modifier.padding(top = 2.dp).size(16.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.bodySmall,
            color = if (tint == MaterialTheme.colorScheme.error) tint else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Spacer8() = androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))

/** Reads the clipboard once for a key and empties it, so the key does not linger where other apps or tools can read it. */
private fun android.content.Context.takeClipboardText(): String? {
    val clipboard = getSystemService(ClipboardManager::class.java) ?: return null
    val text = clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
    if (text.isNullOrEmpty()) return null
    runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) clipboard.clearPrimaryClip()
        else clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
    }
    return text
}

/** Keeps screenshots, screen recording and the recents thumbnail out while a key card is on screen. */
@Composable
private fun SecureWindowWhileShown() {
    val window = LocalActivity.current?.window ?: return
    DisposableEffect(window) {
        if (SecureCards.count++ == 0) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (--SecureCards.count == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}

private object SecureCards {
    var count = 0
}

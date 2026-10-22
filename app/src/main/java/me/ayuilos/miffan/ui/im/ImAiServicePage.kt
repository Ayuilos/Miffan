package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.ai.BackgroundModelHealth
import me.ayuilos.miffan.data.ai.BackgroundModelSource
import me.ayuilos.miffan.data.ai.BackgroundTask
import me.ayuilos.miffan.data.ai.backgroundModelFor
import me.ayuilos.miffan.data.ai.tools.imageToolModelOrNull
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.data.datastore.findProvider
import me.ayuilos.miffan.data.datastore.isNotConfigured
import me.ayuilos.miffan.data.datastore.translationModel
import me.ayuilos.miffan.ui.components.ai.ModelListSheet
import me.ayuilos.miffan.ui.components.ai.rememberModelListState
import me.ayuilos.miffan.ui.components.ui.AutoAIIcon
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.pages.setting.ImageToolModelSheet
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Message01
import me.rerere.hugeicons.stroke.Translate
import org.koin.compose.koinInject

/** Easy chat's one stop for AI: which services are connected, and which model does each job. */
@Composable
fun ImAiServicePage(settingsStore: SettingsStore = koinInject(), backgroundHealth: BackgroundModelHealth = koinInject()) {
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val backgroundFailure by backgroundHealth.lastFailure.collectAsStateWithLifecycle()
    val colors = MaterialTheme.colorScheme
    val connected = settings.providers.filter { provider -> provider.enabled && provider.models.any { it.type == ModelType.CHAT } }
    val defaultModel = settings.findModelById(settings.chatModelId)
    val defaultModels = rememberModelListState(defaultModel?.id, settings.providers, ModelType.CHAT)
    // Memory is the chore every partner relies on, so its model stands for the whole helper.
    val background = settings.backgroundModelFor(BackgroundTask.MEMORY, settings.assistants.find { it.id == settings.assistantId })
    val backgroundModels = rememberModelListState(
        background?.takeIf { it.source == BackgroundModelSource.FAST }?.model?.id,
        settings.providers,
        ModelType.CHAT,
    )
    val pickedTranslation = settings.findModelById(settings.translateModeId)
    val translation = settings.translationModel()
    val translationModels = rememberModelListState(pickedTranslation?.id, settings.providers, ModelType.CHAT, textOnly = true)
    val imageModel = settings.imageToolModelOrNull()
    val imageProvider = imageModel?.findProvider(settings.providers)
    var imageSheetVisible by remember { mutableStateOf(false) }

    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_me_ai_service)) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item("providers") {
                ImSectionLabel(stringResource(R.string.im_ai_service_providers))
                Column(Modifier.padding(top = 8.dp)) {
                    ImCardGroup {
                        if (connected.isEmpty()) {
                            ImListRow(HugeIcons.Link01, stringResource(R.string.im_ai_service_providers_none), { nav.navigate(Screen.SettingProvider) },
                                tone = ImIconTone.TERTIARY, titleColor = colors.error)
                        } else {
                            connected.forEach { provider ->
                                ImProviderRow(provider) { nav.navigate(Screen.SettingProviderDetail(provider.id.toString())) }
                                ImCardDivider()
                            }
                            ImListRow(HugeIcons.Add01, stringResource(R.string.im_ai_service_manage), { nav.navigate(Screen.SettingProvider) },
                                tone = ImIconTone.PRIMARY, titleColor = colors.primary, trailing = null)
                        }
                    }
                }
            }
            if (!settings.isNotConfigured()) item("models") {
                ImSectionLabel(stringResource(R.string.im_ai_service_models))
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ImCardGroup {
                        ImListRow(HugeIcons.Message01, stringResource(R.string.im_ai_service_default_model), defaultModels::open,
                            supporting = defaultModel?.label() ?: stringResource(R.string.im_p5_model_no_default),
                            tone = ImIconTone.PRIMARY, supportingColor = if (defaultModel == null) colors.error else colors.onSurfaceVariant)
                        ImCardDivider()
                        val failure = backgroundFailure
                        ImListRow(HugeIcons.AiMagic, stringResource(R.string.im_me_background_model), backgroundModels::open,
                            supporting = when {
                                failure != null -> stringResource(R.string.im_me_background_model_failed, failure.message)
                                background == null -> stringResource(R.string.im_me_background_model_none)
                                background.source == BackgroundModelSource.FAST ->
                                    stringResource(R.string.im_me_background_model_fast, background.model.label())
                                else -> stringResource(R.string.im_me_background_model_fallback)
                            },
                            tone = ImIconTone.SECONDARY,
                            supportingColor = if (failure != null || background == null) colors.error else colors.onSurfaceVariant)
                        ImCardDivider()
                        ImListRow(HugeIcons.Image03, stringResource(R.string.im_ai_service_draw), { imageSheetVisible = true },
                            supporting = imageModel?.let { listOfNotNull(it.label(), imageProvider?.name).joinToString(" · ") }
                                ?: stringResource(R.string.im_ai_service_draw_off),
                            tone = ImIconTone.TERTIARY)
                        ImCardDivider()
                        ImListRow(HugeIcons.Translate, stringResource(R.string.im_discover_translate), translationModels::open,
                            supporting = when {
                                pickedTranslation != null -> pickedTranslation.label()
                                translation != null -> stringResource(R.string.im_ai_service_same_as_default)
                                else -> stringResource(R.string.im_translate_no_model)
                            },
                            tone = ImIconTone.SECONDARY)
                    }
                    Text(
                        text = stringResource(R.string.im_ai_service_partner_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
                }
            }
        }
    }

    if (imageSheetVisible) {
        ImageToolModelSheet(
            settings = settings,
            onUpdate = { updated -> scope.launch { settingsStore.update(updated) } },
            onDismiss = { imageSheetVisible = false },
            easy = true,
        )
    }
    ModelListSheet(defaultModels) { model ->
        scope.launch { settingsStore.update { it.copy(chatModelId = model.id) } }
    }
    ModelListSheet(backgroundModels) { model ->
        backgroundHealth.dismiss()
        scope.launch { settingsStore.update { it.copy(fastModelId = model.id) } }
    }
    ModelListSheet(translationModels) { model ->
        scope.launch { settingsStore.update { it.copy(translateModeId = model.id) } }
    }
}

private fun Model.label() = displayName.ifBlank { modelId }

/** A connected service with its own logo and how many models it brings. */
@Composable
private fun ImProviderRow(provider: ProviderSetting, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AutoAIIcon(provider.name, Modifier.size(38.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(provider.name, style = MaterialTheme.typography.bodyLarge)
            Text(pluralStringResource(R.plurals.im_ai_service_model_count, provider.models.size, provider.models.size),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

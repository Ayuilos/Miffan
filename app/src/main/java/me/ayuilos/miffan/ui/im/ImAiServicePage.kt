package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.ai.BackgroundModelHealth
import me.ayuilos.miffan.data.ai.BackgroundModelSource
import me.ayuilos.miffan.data.ai.BackgroundTask
import me.ayuilos.miffan.data.ai.backgroundModelFor
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.data.datastore.isNotConfigured
import me.ayuilos.miffan.ui.components.ai.ModelListSheet
import me.ayuilos.miffan.ui.components.ai.rememberModelListState
import me.ayuilos.miffan.ui.components.ui.CardGroup
import me.ayuilos.miffan.ui.context.LocalNavController
import me.rerere.ai.provider.ModelType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiMagic
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Message01
import org.koin.compose.koinInject

/** Easy chat's one stop for AI: which services are connected and which models do the work. */
@Composable
fun ImAiServicePage(settingsStore: SettingsStore = koinInject(), backgroundHealth: BackgroundModelHealth = koinInject()) {
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val backgroundFailure by backgroundHealth.lastFailure.collectAsStateWithLifecycle()
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

    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_me_ai_service)) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item("providers") {
                CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                    item(
                        onClick = { nav.navigate(Screen.SettingProvider) },
                        leadingContent = { Icon(HugeIcons.Link01, null) },
                        headlineContent = { Text(stringResource(R.string.im_ai_service_providers)) },
                        supportingContent = {
                            Text(
                                text = if (connected.isEmpty()) stringResource(R.string.im_ai_service_providers_none)
                                else connected.joinToString(" · ") { it.name },
                                color = if (connected.isEmpty()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                }
            }
            if (!settings.isNotConfigured()) item("models") {
                CardGroup(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    title = { Text(stringResource(R.string.im_ai_service_models)) },
                ) {
                    item(
                        onClick = defaultModels::open,
                        leadingContent = { Icon(HugeIcons.Message01, null) },
                        headlineContent = { Text(stringResource(R.string.im_ai_service_default_model)) },
                        supportingContent = {
                            Text(
                                text = defaultModel?.displayName?.ifBlank { defaultModel.modelId }
                                    ?: stringResource(R.string.im_p5_model_no_default),
                                color = if (defaultModel == null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                    item(
                        onClick = backgroundModels::open,
                        leadingContent = { Icon(HugeIcons.AiMagic, null) },
                        headlineContent = { Text(stringResource(R.string.im_me_background_model)) },
                        supportingContent = {
                            val failure = backgroundFailure
                            Text(
                                text = when {
                                    failure != null -> stringResource(R.string.im_me_background_model_failed, failure.message)
                                    background == null -> stringResource(R.string.im_me_background_model_none)
                                    background.source == BackgroundModelSource.FAST -> stringResource(
                                        R.string.im_me_background_model_fast,
                                        background.model.displayName.ifBlank { background.model.modelId },
                                    )
                                    else -> stringResource(R.string.im_me_background_model_fallback)
                                },
                                color = if (failure != null || background == null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = { Icon(HugeIcons.ArrowRight01, null) },
                    )
                }
            }
            if (!settings.isNotConfigured()) item("hint") {
                Text(
                    text = stringResource(R.string.im_ai_service_partner_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }

    ModelListSheet(defaultModels) { model ->
        scope.launch { settingsStore.update { it.copy(chatModelId = model.id) } }
    }
    ModelListSheet(backgroundModels) { model ->
        backgroundHealth.dismiss()
        scope.launch { settingsStore.update { it.copy(fastModelId = model.id) } }
    }
}

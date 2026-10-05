package me.ayuilos.miffan.ui.im

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterAuthService
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterAuthState
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterSavedKeyState
import me.ayuilos.miffan.data.datastore.OPENROUTER_PROVIDER_ID
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.rerere.ai.provider.ModelType
import org.koin.compose.koinInject

@Composable
fun ImOnboardingPage(settingsStore: SettingsStore = koinInject(), auth: OpenRouterAuthService = koinInject()) {
    val nav = LocalNavController.current
    val settings by settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val authState by auth.state.collectAsStateWithLifecycle()
    val savedKey by auth.savedKeyState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val locale = LocalConfiguration.current.locales[0]
    val templates = imPartnerTemplates(locale)
    var step by rememberSaveable { mutableIntStateOf(0) }
    var selectedService by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedPartner by rememberSaveable { mutableStateOf<String?>(null) }
    var connecting by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val failure = stringResource(R.string.im_p5_failed)
    val providers = settings.providers.sortedBy { if (it.id == OPENROUTER_PROVIDER_ID) 0 else 1 }
    val provider = providers.find { it.id.toString() == selectedService }
    val busy = authState == OpenRouterAuthState.Authorizing || savedKey == OpenRouterSavedKeyState.Restoring || saving
    LaunchedEffect(Unit) { auth.checkExistingKey() }
    LaunchedEffect(authState, settings.providers) {
        if (connecting && provider?.models?.any { it.type == ModelType.CHAT } == true) {
            connecting = false
            step = 1
        }
    }
    BackHandler(enabled = step == 1) { step = 0 }
    val fallbackPartner = remember { Assistant(name = "Miffan") }
    val defaultPartner = settings.assistants.firstOrNull() ?: fallbackPartner
    LaunchedEffect(step) { if (step == 1 && selectedPartner == null) selectedPartner = defaultPartner.id.toString() }
    Scaffold(snackbarHost = { SnackbarHost(snackbar) }, bottomBar = {
        Column(Modifier.navigationBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (authState is OpenRouterAuthState.Error) Text((authState as OpenRouterAuthState.Error).message, color = MaterialTheme.colorScheme.error, maxLines = 3)
            Button(modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp), enabled = !busy && if (step == 0) selectedService != null else selectedPartner != null, onClick = {
                if (step == 0) {
                    if (provider?.models?.any { it.type == ModelType.CHAT } == true) step = 1
                    else if (provider?.id == OPENROUTER_PROVIDER_ID) {
                        connecting = true
                        when (savedKey) {
                            is OpenRouterSavedKeyState.Valid -> auth.restoreFreeModel()
                            OpenRouterSavedKeyState.CheckFailed -> auth.checkExistingKey()
                            else -> auth.startAuthorization(locale.toLanguageTag())
                        }
                    } else if (provider != null) nav.navigate(Screen.SettingProviderDetail(provider.id.toString()))
                    else nav.navigate(Screen.SettingProvider)
                } else scope.launch {
                    saving = true
                    try {
                        val chosen = (settings.assistants + templates.map { it.assistant() } + defaultPartner).find { it.id.toString() == selectedPartner } ?: return@launch
                        val model = provider?.models?.find { it.id == settings.chatModelId && it.type == ModelType.CHAT }
                            ?: provider?.models?.firstOrNull { it.type == ModelType.CHAT }
                        if (model == null) { step = 0; return@launch }
                        settingsStore.update { current -> current.copy(
                            assistants = if (current.assistants.any { it.id == chosen.id }) current.assistants else current.assistants + chosen,
                            assistantId = chosen.id, chatModelId = model.id,
                            providers = current.providers.map { if (it.id == provider?.id) it.copyProvider(enabled = true) else it },
                        ) }
                        nav.clearAndNavigate(Screen.Home)
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { snackbar.showSnackbar(failure) }
                    finally { saving = false }
                }
            }) { Text(stringResource(if (step == 0) R.string.im_p5_continue else R.string.im_p5_finish)) }
            if (step == 0) Text(stringResource(R.string.im_p5_select_service), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (authState == OpenRouterAuthState.Authorizing) TextButton(onClick = { connecting = false; auth.cancelAuthorization() }) { Text(stringResource(R.string.chat_page_cancel)) }
        }
    }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("welcome") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${step + 1} / 2", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    AssistantAvatar("Miffan", defaultPartner.avatar.takeIf { step == 1 } ?: Avatar.Miffan(), Modifier.size(124.dp))
                    Text(stringResource(if (step == 0) R.string.im_p5_connect else R.string.im_p5_choose_partner), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                    Text(stringResource(if (step == 0) R.string.im_p5_connect_desc else R.string.im_p5_choose_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 16.dp))
                }
            }
            if (step == 0) {
                items(providers, key = { it.id.toString() }) { service ->
                    ImOnboardingChoice(service.name, stringResource(if (service.id == OPENROUTER_PROVIDER_ID) R.string.im_p5_one_click else R.string.im_p5_api_key),
                        selected = selectedService == service.id.toString(), enabled = !busy) { selectedService = service.id.toString() }
                }
            } else {
                item("default") { ImOnboardingChoice(defaultPartner.name.ifBlank { "Miffan" }, defaultPartner.systemPrompt.take(100),
                    selectedPartner == defaultPartner.id.toString(), !busy) { selectedPartner = defaultPartner.id.toString() } }
                items(templates, key = { it.id.toString() }) { template ->
                    ImOnboardingChoice(template.name, template.description, selectedPartner == template.id.toString(), !busy) { selectedPartner = template.id.toString() }
                }
            }
        }
    }
}

@Composable
private fun ImOnboardingChoice(title: String, description: String, selected: Boolean, enabled: Boolean, onSelect: () -> Unit) {
    Surface(onClick = onSelect, enabled = enabled, color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected, onClick = null)
        }
    }
}

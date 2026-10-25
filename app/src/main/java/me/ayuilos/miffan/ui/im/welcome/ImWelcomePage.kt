package me.ayuilos.miffan.ui.im.welcome

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.data.model.isImMode
import me.ayuilos.miffan.data.onboarding.OnboardingStage
import me.ayuilos.miffan.data.onboarding.onboardingStage
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.im.navigateHome
import me.ayuilos.miffan.ui.pages.backup.BackupVM
import me.ayuilos.miffan.ui.pages.backup.components.BackupDialog
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.LockPassword
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.io.FileOutputStream

/**
 * The first run, as a chat with Miffan: it introduces itself, connects a service, and hands over to
 * the partner the user picks. Both shells start here while nothing is connected.
 */
@Composable
fun ImWelcomePage(vm: ImWelcomeVM = koinViewModel(), backupVM: BackupVM = koinViewModel()) {
    val nav = LocalNavController.current
    val settings = LocalSettings.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val keyFor by vm.keyFor.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val connectedTo by vm.connectedTo.collectAsStateWithLifecycle()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }

    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is WelcomeEvent.OpenThread -> {
                    nav.clearAndNavigate(Screen.Home)
                    nav.navigate(Screen.Thread(event.assistantId.toString()))
                }
                WelcomeEvent.Home -> nav.navigateHome(settings)
                WelcomeEvent.ProviderSettings -> nav.navigate(Screen.SettingProvider)
            }
        }
    }
    // Professional mode connects in its own settings; once a model is there, this page is done.
    LaunchedEffect(settings.isImMode, settings.onboardingStage()) {
        if (!settings.init && !settings.isImMode && settings.onboardingStage() == OnboardingStage.NONE) nav.navigateHome(settings)
    }
    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        vm.backupPicked(R.string.onboarding_backup_file)
        vm.restoreStarted()
        scope.launch {
            val temp = File(context.cacheDir, "temp_import_${System.currentTimeMillis()}.zip")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { input.copyTo(it) } }
                backupVM.restoreFromLocalFile(temp)
                restarting = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                vm.restoreFailed(e.message ?: resources.getString(R.string.im_p5_failed))
            } finally {
                temp.delete()
            }
        }
    }
    val listState = rememberLazyListState()
    // Every new line or card is something to answer, so the list follows it.
    LaunchedEffect(entries.size) { if (entries.isNotEmpty()) listState.animateScrollToItem(0) }
    val miffan = settings.assistants.firstOrNull()
    val pickingPartner = entries.any { (it is WelcomeEntry.Partners && !it.used) || (it is WelcomeEntry.Diy && !it.used) }

    Scaffold(
        topBar = { WelcomeTopBar(miffan?.avatar ?: Avatar.Miffan(), connectedTo, busy) },
        bottomBar = {
            val provider = keyFor
            if (provider != null) WelcomeKeyBar(provider.name, onSubmit = vm::submitKey)
            else WelcomeQuietBar(stringResource(when {
                entries.any { entry -> entry is WelcomeEntry.Progress && entry.steps.any { it.status == StepStatus.DOING } } ->
                    R.string.onboarding_input_busy
                pickingPartner -> R.string.onboarding_input_pick
                else -> R.string.onboarding_input_offline
            }))
        },
    ) { padding ->
        // Newest at the bottom, like a chat; the list grows upward from the input.
        LazyColumn(
            // Above the keyboard, so a card's field being typed in stays in view.
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        ) {
            items(entries.asReversed(), key = { it.key }) { entry ->
                Box(Modifier.animateItem()) {
                    WelcomeRow(entry, busy, connectedTo != null, miffan,
                        onOpenRouter = vm::signInWithOpenRouter,
                        onPickService = { sheetOpen = true },
                        onBackup = vm::chooseBackup,
                        onPro = vm::choosePro,
                        onOpenUrl = { runCatching { uriHandler.openUri(it) } },
                        onCancelLogin = vm::cancelLogin,
                        onReopenLogin = vm::reopenLogin,
                        onRetry = vm::retry,
                        onUndoConnection = vm::undoConnection,
                        onModel = vm::chooseModel,
                        onAddress = vm::submitAddress,
                        onPartner = vm::pickPartner,
                        onDiy = vm::openDiy,
                        onLeaveDiy = vm::leaveDiy,
                        onBackupChoice = { label ->
                            // A file is echoed once it is picked; the other sources open their own pages.
                            if (label != R.string.onboarding_backup_file) vm.backupPicked(label)
                            when (label) {
                                R.string.onboarding_backup_file -> restoreLauncher.launch(arrayOf("application/zip"))
                                R.string.onboarding_backup_webdav -> nav.navigate(Screen.Backup(tab = 1))
                                R.string.onboarding_backup_s3 -> nav.navigate(Screen.Backup(tab = 2))
                                else -> nav.navigate(Screen.ImBackup)
                            }
                        },
                        onGoPro = vm::goPro,
                        onStay = vm::stayHere,
                    )
                }
            }
        }
    }
    if (sheetOpen) WelcomeProviderSheet(settings.providers, onDismiss = { sheetOpen = false }) { provider ->
        sheetOpen = false
        vm.pickProvider(provider)
    }
    if (restarting) BackupDialog()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WelcomeTopBar(avatar: Avatar, connectedTo: String?, busy: Boolean) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AssistantAvatar(ImWelcomeVM.MIFFAN_NAME, avatar, Modifier.size(40.dp),
                semanticState = when {
                    connectedTo != null -> MiffanMascotState.Happy
                    busy -> MiffanMascotState.Thinking
                    else -> MiffanMascotState.Idle
                })
            Column {
                Text(ImWelcomeVM.MIFFAN_NAME, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(Modifier.size(7.dp).background(
                        if (connectedTo != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                    Text(stringResource(if (connectedTo != null) R.string.onboarding_status_online else R.string.onboarding_status_offline),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Nothing to type yet: the bar says what unlocks it. */
@Composable
private fun WelcomeQuietBar(hint: String) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Surface(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .6f)) {
            Text(hint, Modifier.padding(horizontal = 20.dp, vertical = 15.dp).animateContentSize(),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .7f), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** The input bar asks for one service's key; empty, its button pastes and connects in one tap. */
@Composable
private fun WelcomeKeyBar(providerName: String, onSubmit: (String) -> Unit) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var key by rememberSaveable(providerName) { mutableStateOf("") }
    fun submit(value: String) {
        if (value.isBlank()) return
        key = ""
        onSubmit(value)
    }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(key, { key = it }, Modifier.weight(1f), singleLine = true,
                    shape = RoundedCornerShape(26.dp),
                    leadingIcon = { Icon(HugeIcons.LockPassword, null, Modifier.size(20.dp)) },
                    placeholder = { Text(stringResource(R.string.onboarding_key_hint, providerName)) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { submit(key) }))
                Button(onClick = {
                    if (key.isNotBlank()) submit(key)
                    else scope.launch { clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.trim()?.let(::submit) }
                }, Modifier.heightIn(min = 52.dp)) {
                    Text(stringResource(if (key.isBlank()) R.string.onboarding_key_paste else R.string.onboarding_key_connect))
                }
            }
            Text(stringResource(R.string.onboarding_key_note, providerName), Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

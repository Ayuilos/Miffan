package me.ayuilos.miffan.ui.im

import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.BuildConfig
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.ai.BackgroundModelHealth
import me.ayuilos.miffan.data.ai.BackgroundTask
import me.ayuilos.miffan.data.ai.backgroundModelFor
import me.ayuilos.miffan.data.datastore.isNotConfigured
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.data.repository.MemoryRepository
import me.ayuilos.miffan.ui.components.ui.UIAvatar
import me.ayuilos.miffan.ui.components.ui.UpdateAvailableBanner
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.hooks.EditStateContent
import me.ayuilos.miffan.ui.hooks.rememberCurrentColorMode
import me.ayuilos.miffan.ui.hooks.useEditState
import me.ayuilos.miffan.ui.im.computer.rememberKnownComputers
import me.ayuilos.miffan.ui.theme.ColorMode
import me.ayuilos.miffan.utils.UpdateInfo
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cloud
import me.rerere.hugeicons.stroke.Computer
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Settings03
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * The owner's own page: who they are to their partners, what partners know and use, preferences in
 * plain words, and the professional settings folded away under "advanced".
 */
@Composable
internal fun ImMeTab(vm: ImHomeVM, innerPadding: PaddingValues, availableUpdate: UpdateInfo?) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val updateDownloadState by vm.downloadState.collectAsStateWithLifecycle()
    val computers by rememberKnownComputers()
    val scope = rememberCoroutineScope()
    var advanced by rememberSaveable { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    val colorMode = rememberCurrentColorMode()
    val nickname = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) }
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(userNickname = newNickname)) }
    }
    val backgroundHealth = koinInject<BackgroundModelHealth>()
    val backgroundFailure by backgroundHealth.lastFailure.collectAsStateWithLifecycle()
    val defaultPartner = settings.assistants.find { it.id == settings.assistantId }
    // Memory is the chore every partner relies on, so its health stands for the whole helper.
    val background = settings.backgroundModelFor(BackgroundTask.MEMORY, defaultPartner)
    val memoryOwner = defaultPartner?.memoryOwnerId() ?: MemoryRepository.GLOBAL_MEMORY_ID
    val memoryRepository = koinInject<MemoryRepository>()
    val memories by remember(memoryOwner) { memoryRepository.getMemoryRecordsFlow(memoryOwner) }
        .collectAsStateWithLifecycle(initialValue = null)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item("title") { ImTabTitle(R.string.im_tab_me) }
        item("profile") {
            Surface(
                onClick = { nicknameEditState.open(settings.displaySetting.userNickname) },
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    UIAvatar(
                        name = nickname,
                        value = settings.displaySetting.userAvatar,
                        onUpdate = { avatar -> vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(userAvatar = avatar)) } },
                        modifier = Modifier.size(60.dp),
                    )
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(nickname, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(stringResource(R.string.im_me_profile_desc), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(HugeIcons.ArrowRight01, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        availableUpdate?.let { info ->
            item("update") {
                UpdateAvailableBanner(
                    info = info,
                    downloadState = updateDownloadState,
                    onDownload = { vm.downloadUpdate(context, it) },
                    onCancelDownload = vm::cancelUpdateDownload,
                    onInstall = { vm.installUpdate(context) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }
        item("partners") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                ImSectionLabel(stringResource(R.string.im_me_section_partners))
                Spacer(Modifier.height(8.dp))
                ImCardGroup {
                    val count = memories?.size
                    ImListRow(HugeIcons.Brain02, stringResource(R.string.im_me_memory), { navController.navigate(Screen.ImMemory(memoryOwner)) },
                        tone = ImIconTone.SECONDARY,
                        supporting = when (count) {
                            null -> null
                            0 -> stringResource(R.string.im_me_memory_none)
                            else -> pluralStringResource(R.plurals.im_me_memory_count, count, count)
                        })
                    ImCardDivider()
                    ImListRow(HugeIcons.Computer, stringResource(R.string.im_me_computers), { navController.navigate(Screen.Computers) },
                        tone = ImIconTone.TERTIARY,
                        supporting = computers?.size?.let { count ->
                            if (count == 0) stringResource(R.string.im_me_computers_none) else stringResource(R.string.im_me_computers_count, count)
                        })
                    ImCardDivider()
                    // Background helper problems surface here; the details live inside the service page.
                    val status = when {
                        settings.isNotConfigured() -> R.string.im_me_ai_not_connected
                        backgroundFailure != null || background == null -> R.string.im_me_ai_needs_attention
                        else -> R.string.im_me_ai_connected
                    }
                    ImListRow(HugeIcons.Link01, stringResource(R.string.im_me_ai_service), { navController.navigate(Screen.ImAiService) },
                        tone = ImIconTone.PRIMARY, supporting = stringResource(status),
                        supportingColor = if (status == R.string.im_me_ai_connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                }
            }
        }
        item("prefs") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                ImSectionLabel(stringResource(R.string.im_me_section_prefs))
                Spacer(Modifier.height(8.dp))
                ImCardGroup {
                    ImListRow(HugeIcons.PaintBoard, stringResource(R.string.im_me_appearance), { navController.navigate(Screen.ImAppearance) },
                        trailing = {
                            ImRowValue(stringResource(when (colorMode) {
                                ColorMode.SYSTEM -> R.string.im_appearance_mode_system
                                ColorMode.LIGHT -> R.string.im_appearance_mode_light
                                ColorMode.DARK -> R.string.im_appearance_mode_dark
                            }))
                        })
                    ImCardDivider()
                    ImListRow(HugeIcons.Notification01, stringResource(R.string.im_me_notification), { navController.navigate(Screen.ImNotifications) },
                        trailing = {
                            ImRowValue(stringResource(if (settings.displaySetting.enableNotificationOnMessageGeneration) R.string.im_me_on else R.string.im_me_off))
                        })
                    ImCardDivider()
                    ImListRow(HugeIcons.Cloud, stringResource(R.string.im_me_backup), { navController.navigate(Screen.ImBackup) },
                        trailing = {
                            val days = settings.daysSinceBackup()
                            ImRowValue(
                                when (days) {
                                    null -> stringResource(R.string.im_backup_never)
                                    0 -> stringResource(R.string.im_backup_today)
                                    else -> pluralStringResource(R.plurals.im_me_backup_days, days, days)
                                },
                                if (settings.backupOverdue()) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        })
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        ImCardDivider()
                        ImListRow(HugeIcons.Earth, stringResource(R.string.im_me_language), {
                            context.startActivity(Intent(AndroidSettings.ACTION_APP_LOCALE_SETTINGS).setData("package:${context.packageName}".toUri()))
                        }, trailing = { ImRowValue(locale.getDisplayName(locale)) })
                    }
                }
            }
        }
        item("more") {
            ImCardGroup(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                ImListRow(HugeIcons.InformationCircle, stringResource(R.string.im_me_about_miffan), { navController.navigate(Screen.SettingAbout) },
                    trailing = { ImRowValue(BuildConfig.VERSION_NAME) })
                ImCardDivider()
                ImListRow(HugeIcons.Settings03, stringResource(R.string.im_me_advanced), { advanced = !advanced },
                    supporting = stringResource(R.string.im_me_advanced_desc),
                    trailing = {
                        Icon(HugeIcons.ArrowDown01, null, Modifier.rotate(if (advanced) 180f else 0f), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    })
                AnimatedVisibility(advanced) {
                    Column {
                        ImCardDivider()
                        ImListRow(null, stringResource(R.string.im_me_all_settings), { navController.navigate(Screen.Setting) },
                            supporting = stringResource(R.string.im_me_all_settings_desc), indent = true)
                        ImCardDivider()
                        ImListRow(null, stringResource(R.string.im_me_switch_professional), {
                            scope.launch {
                                vm.switchInterfaceMode(InterfaceMode.PROFESSIONAL)
                                navController.clearAndNavigate(Screen.Chat(Uuid.random().toString()))
                            }
                        }, supporting = stringResource(R.string.im_me_switch_professional_desc), indent = true)
                    }
                }
            }
        }
    }

    nicknameEditState.EditStateContent { value, onUpdate ->
        AlertDialog(
            onDismissRequest = { nicknameEditState.dismiss() },
            title = { Text(stringResource(R.string.chat_page_edit_nickname)) },
            text = {
                OutlinedTextField(
                    value = value,
                    onValueChange = onUpdate,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.chat_page_nickname_placeholder)) },
                )
            },
            confirmButton = {
                TextButton(onClick = { nicknameEditState.confirm() }) { Text(stringResource(R.string.chat_page_save)) }
            },
            dismissButton = {
                TextButton(onClick = { nicknameEditState.dismiss() }) { Text(stringResource(R.string.chat_page_cancel)) }
            },
        )
    }
}

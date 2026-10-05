package me.ayuilos.miffan.ui.im

import android.content.Intent
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import me.ayuilos.miffan.data.repository.MemoryRepository
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain01
import me.rerere.hugeicons.stroke.Cloud
import me.rerere.hugeicons.stroke.Earth
import me.rerere.hugeicons.stroke.Exchange01
import me.rerere.hugeicons.stroke.InformationCircle
import me.rerere.hugeicons.stroke.Link01
import me.rerere.hugeicons.stroke.Notification01
import me.rerere.hugeicons.stroke.PaintBoard
import me.rerere.hugeicons.stroke.Settings03
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.datastore.isNotConfigured
import me.ayuilos.miffan.data.model.InterfaceMode
import me.ayuilos.miffan.ui.components.ui.CardGroup
import me.ayuilos.miffan.ui.components.ui.UIAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.hooks.EditStateContent
import me.ayuilos.miffan.ui.hooks.useEditState
import kotlin.uuid.Uuid

@Composable
internal fun ImMeTab(vm: ImHomeVM, innerPadding: PaddingValues) {
    val navController = LocalNavController.current
    val context = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var advanced by rememberSaveable { mutableStateOf(true) }
    val locale = LocalConfiguration.current.locales[0]
    val nickname = settings.displaySetting.userNickname.ifBlank { stringResource(R.string.user_default_name) }
    val nicknameEditState = useEditState<String> { newNickname ->
        vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(userNickname = newNickname)) }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = innerPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("title") { ImTabTitle(R.string.im_tab_me) }
        item("profile") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                UIAvatar(
                    name = nickname,
                    value = settings.displaySetting.userAvatar,
                    onUpdate = { avatar ->
                        vm.updateSettings { it.copy(displaySetting = it.displaySetting.copy(userAvatar = avatar)) }
                    },
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    text = nickname,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { nicknameEditState.open(settings.displaySetting.userNickname) },
                )
            }
        }
        item("ai") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item(
                    onClick = { navController.navigate(Screen.ImMemory(settings.assistants.find { it.id == settings.assistantId }?.memoryOwnerId() ?: MemoryRepository.GLOBAL_MEMORY_ID)) },
                    leadingContent = { Icon(HugeIcons.Brain01, null) },
                    headlineContent = { Text(stringResource(R.string.im_me_memory)) },
                    trailingContent = { Text("›") },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingProvider) },
                    leadingContent = { Icon(HugeIcons.Link01, null) },
                    headlineContent = { Text(stringResource(R.string.im_me_ai_service)) },
                    trailingContent = {
                        Text(
                            text = stringResource(
                                if (settings.isNotConfigured()) R.string.im_me_ai_not_connected
                                else R.string.im_me_ai_connected
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (settings.isNotConfigured()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
        item("general") {
            CardGroup(modifier = Modifier.padding(horizontal = 16.dp)) {
                item(
                    onClick = { navController.navigate(Screen.SettingPreferencesTheme) },
                    leadingContent = { Icon(HugeIcons.PaintBoard, null) },
                    headlineContent = { Text(stringResource(R.string.im_me_appearance)) },
                    trailingContent = { Text("›") },
                )
                item(
                    onClick = { navController.navigate(Screen.Backup) },
                    leadingContent = { Icon(HugeIcons.Cloud, null) },
                    headlineContent = { Text(stringResource(R.string.im_me_backup)) },
                    trailingContent = { Text("›") },
                )
                item(
                    onClick = { navController.navigate(Screen.SettingPreferencesNotification) },
                    leadingContent = { Icon(HugeIcons.Notification01, null) },
                    headlineContent = { Text(stringResource(R.string.im_me_notification)) },
                    trailingContent = { Text("›") },
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    item(
                        onClick = {
                            context.startActivity(
                                Intent(AndroidSettings.ACTION_APP_LOCALE_SETTINGS)
                                    .setData("package:${context.packageName}".toUri())
                            )
                        },
                        leadingContent = { Icon(HugeIcons.Earth, null) },
                        headlineContent = { Text(stringResource(R.string.im_me_language)) },
                        trailingContent = { Text(locale.getDisplayName(locale), style = MaterialTheme.typography.bodySmall) },
                    )
                }
                item(
                    onClick = { navController.navigate(Screen.SettingAbout) },
                    leadingContent = { Icon(HugeIcons.InformationCircle, null) },
                    headlineContent = { Text(stringResource(R.string.im_me_about)) },
                    trailingContent = { Text("›") },
                )
            }
        }
        item("advanced") {
            CardGroup(
                modifier = Modifier.padding(horizontal = 16.dp),
                title = { TextButton(onClick = { advanced = !advanced }) { Text("${if (advanced) "⌃" else "⌄"}  ${stringResource(R.string.im_me_advanced)}") } },
            ) {
                if (advanced) item(
                    onClick = { navController.navigate(Screen.Setting) },
                    leadingContent = { Icon(HugeIcons.Settings03, null) },
                    headlineContent = { Text(stringResource(R.string.im_me_all_settings)) },
                    supportingContent = { Text(stringResource(R.string.im_me_all_settings_desc)) },
                )
                if (advanced) item(
                    onClick = {
                        scope.launch {
                            vm.switchInterfaceMode(InterfaceMode.PROFESSIONAL)
                            navController.clearAndNavigate(Screen.Chat(Uuid.random().toString()))
                        }
                    },
                    leadingContent = { Icon(HugeIcons.Exchange01, null) },
                    headlineContent = { Text(stringResource(R.string.im_me_switch_professional)) },
                    supportingContent = { Text(stringResource(R.string.im_me_switch_professional_desc)) },
                    trailingContent = { Text("›") },
                )
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

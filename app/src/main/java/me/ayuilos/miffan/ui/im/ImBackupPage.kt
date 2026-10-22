package me.ayuilos.miffan.ui.im

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.WebDavConfig
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.pages.backup.BackupVM
import me.ayuilos.miffan.ui.pages.backup.components.BackupDialog
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Alert02
import me.rerere.hugeicons.stroke.Cloud
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Database02
import me.rerere.hugeicons.stroke.DatabaseRestore
import me.rerere.hugeicons.stroke.Exchange01
import org.koin.androidx.compose.koinViewModel
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private const val DAY_MILLIS = 24 * 60 * 60 * 1_000L

/** Whole days since the last backup, or null when there never was one. */
internal fun Settings.daysSinceBackup(now: Long = System.currentTimeMillis()): Int? =
    backupReminderConfig.lastBackupTime.takeIf { it > 0 }?.let { ((now - it) / DAY_MILLIS).toInt().coerceAtLeast(0) }

/** Never backed up, or longer ago than the reminder interval. */
internal fun Settings.backupOverdue(): Boolean =
    daysSinceBackup()?.let { it >= backupReminderConfig.intervalDays } ?: true

private enum class ImportSource { BACKUP, CHATBOX, CHERRY }

/**
 * Easy chat's backup: how long since the last one and a button to make one now, what goes in it,
 * the online copies (set up on the full backup page), and restoring or moving over from another app.
 */
@Composable
fun ImBackupPage(vm: BackupVM = koinViewModel()) {
    val nav = LocalNavController.current
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val items by vm.localBackupItems.collectAsStateWithLifecycle()
    var busy by remember { mutableStateOf(false) }
    var confirmRestore by rememberSaveable { mutableStateOf(false) }
    var otherApps by rememberSaveable { mutableStateOf(false) }
    var restarting by remember { mutableStateOf(false) }
    var source by rememberSaveable { mutableStateOf(ImportSource.BACKUP) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            try {
                val file = vm.exportToFile()
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out -> FileInputStream(file).use { it.copyTo(out) } }
                } finally { file.delete() }
                toaster.show(resources.getString(R.string.backup_page_backup_success), type = ToastType.Success)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toaster.show(resources.getString(R.string.backup_page_restore_failed, e.message.orEmpty()), type = ToastType.Error) }
            finally { busy = false }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val temp = File(context.cacheDir, "temp_import_${System.currentTimeMillis()}.zip")
            try {
                context.contentResolver.openInputStream(uri)?.use { input -> FileOutputStream(temp).use { input.copyTo(it) } }
                when (source) {
                    ImportSource.BACKUP -> vm.restoreFromLocalFile(temp)
                    ImportSource.CHATBOX -> vm.restoreFromChatBox(temp)
                    ImportSource.CHERRY -> vm.restoreFromCherryStudio(temp)
                }
                toaster.show(resources.getString(R.string.backup_page_restore_success), type = ToastType.Success)
                restarting = true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { toaster.show(resources.getString(R.string.backup_page_restore_failed, e.message.orEmpty()), type = ToastType.Error) }
            finally { temp.delete(); busy = false }
        }
    }

    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_me_backup)) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item("status") {
                val days = settings.daysSinceBackup()
                val overdue = settings.backupOverdue()
                val colors = MaterialTheme.colorScheme
                ImCardGroup {
                    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Surface(shape = CircleShape, modifier = Modifier.size(56.dp),
                            color = if (overdue) colors.tertiaryContainer else colors.primaryContainer,
                            contentColor = if (overdue) colors.onTertiaryContainer else colors.onPrimaryContainer) {
                            Box(contentAlignment = Alignment.Center) { Icon(if (overdue) HugeIcons.Alert02 else HugeIcons.CheckmarkCircle02, null) }
                        }
                        Text(when (days) {
                            null -> stringResource(R.string.im_backup_never)
                            0 -> stringResource(R.string.im_backup_today)
                            else -> pluralStringResource(R.plurals.im_backup_days_ago, days, days)
                        }, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                        Text(stringResource(R.string.im_backup_contains), style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                        Button(enabled = !busy && items.isNotEmpty(), modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), onClick = {
                            val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
                            exportLauncher.launch("miffan_backup_$stamp.zip")
                        }) {
                            if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.onPrimary)
                            else Text(stringResource(if (days == 0) R.string.im_backup_again else R.string.backup_page_backup_now))
                        }
                        Text(stringResource(R.string.im_backup_choose_place), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
            }
            item("include") {
                ImSectionLabel(stringResource(R.string.im_backup_include))
                Spacer(Modifier.height(8.dp))
                ImCardGroup {
                    listOf(
                        Triple(WebDavConfig.BackupItem.DATABASE, R.string.backup_page_chat_records, R.string.im_backup_chats_desc),
                        Triple(WebDavConfig.BackupItem.FILES, R.string.im_backup_files, R.string.im_backup_files_desc),
                    ).forEachIndexed { index, (item, title, desc) ->
                        if (index > 0) ImCardDivider(start = 16.dp)
                        ImSwitchRow(stringResource(title), item in items, { on ->
                            vm.updateLocalBackupItems(if (on) items + item else items - item)
                        }, supporting = stringResource(desc))
                    }
                }
            }
            item("cloud") {
                ImSectionLabel(stringResource(R.string.im_backup_cloud))
                Spacer(Modifier.height(8.dp))
                val state = { set: Boolean -> resources.getString(if (set) R.string.im_backup_set else R.string.im_backup_not_set) }
                ImCardGroup {
                    ImListRow(HugeIcons.Cloud, stringResource(R.string.im_backup_webdav), { nav.navigate(Screen.Backup(tab = 1)) },
                        supporting = stringResource(R.string.im_backup_webdav_desc) + " · " + state(settings.webDavConfig.url.isNotBlank()),
                        tone = ImIconTone.PRIMARY)
                    ImCardDivider()
                    ImListRow(HugeIcons.Database02, stringResource(R.string.im_backup_s3), { nav.navigate(Screen.Backup(tab = 2)) },
                        supporting = stringResource(R.string.im_backup_s3_desc) + " · " + state(settings.s3Config.bucket.isNotBlank()),
                        tone = ImIconTone.PRIMARY)
                    ImCardDivider(start = 16.dp)
                    val reminder = settings.backupReminderConfig
                    ImSwitchRow(stringResource(R.string.im_backup_remind), reminder.enabled, { on ->
                        vm.updateSettings(settings.copy(backupReminderConfig = reminder.copy(enabled = on)))
                    }, supporting = pluralStringResource(R.plurals.im_backup_remind_desc, reminder.intervalDays, reminder.intervalDays))
                }
            }
            item("restore") {
                ImSectionLabel(stringResource(R.string.im_backup_restore_section))
                Spacer(Modifier.height(8.dp))
                ImCardGroup {
                    ImListRow(HugeIcons.DatabaseRestore, stringResource(R.string.im_backup_restore), { confirmRestore = true }, enabled = !busy)
                    ImCardDivider()
                    ImListRow(HugeIcons.Exchange01, stringResource(R.string.im_backup_import_other), { otherApps = true }, enabled = !busy,
                        supporting = "ChatBox · Cherry Studio")
                }
            }
        }
    }
    if (confirmRestore) AlertDialog(onDismissRequest = { confirmRestore = false },
        title = { Text(stringResource(R.string.im_backup_restore)) },
        text = { Text(stringResource(R.string.backup_page_import_overwrite_confirm)) },
        confirmButton = {
            TextButton(onClick = {
                confirmRestore = false
                source = ImportSource.BACKUP
                importLauncher.launch(arrayOf("application/zip"))
            }) { Text(stringResource(R.string.confirm)) }
        },
        dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(stringResource(R.string.cancel)) } })
    if (otherApps) ImOtherAppsSheet(onDismiss = { otherApps = false }) { picked ->
        otherApps = false
        source = picked
        importLauncher.launch(if (picked == ImportSource.CHATBOX) arrayOf("application/zip", "application/x-zip-compressed", "application/json")
            else arrayOf("application/zip"))
    }
    if (restarting) BackupDialog()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImOtherAppsSheet(onDismiss: () -> Unit, onPick: (ImportSource) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.im_backup_import_other), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp, bottom = 8.dp))
            ImCardGroup {
                ImListRow(null, stringResource(R.string.backup_page_import_from_chatbox), { onPick(ImportSource.CHATBOX) },
                    supporting = stringResource(R.string.backup_page_import_chatbox_desc))
                ImCardDivider(start = 16.dp)
                ImListRow(null, stringResource(R.string.backup_page_import_from_cherry_studio), { onPick(ImportSource.CHERRY) },
                    supporting = stringResource(R.string.backup_page_import_cherry_studio_desc))
            }
        }
    }
}

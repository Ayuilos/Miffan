package me.ayuilos.miffan.ui.pages.update

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.format.DateUtils
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.components.ui.AppCharacterMascot
import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import me.ayuilos.miffan.ui.components.ui.MiffanPresentation
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.pages.setting.rememberLauncherIconPreview
import me.ayuilos.miffan.utils.LauncherIcon
import me.ayuilos.miffan.utils.LauncherIconManager
import me.ayuilos.miffan.utils.ReleaseNoteItem
import me.ayuilos.miffan.utils.ReleaseNotes
import me.ayuilos.miffan.utils.UpdateDownloadState
import me.ayuilos.miffan.utils.UpdateInfo
import me.ayuilos.miffan.utils.fileSizeToString
import me.ayuilos.miffan.utils.parseReleaseNotes
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.WifiDisconnected02
import org.koin.androidx.compose.koinViewModel

/** The notes for the app's language: Chinese for any zh locale, English otherwise. */
@Composable
internal fun rememberReleaseNotes(info: UpdateInfo): ReleaseNotes {
    val chinese = LocalConfiguration.current.locales[0].language == "zh"
    return remember(info.changelog, chinese) { parseReleaseNotes(info.changelog, chinese) }
}

internal val UpdateDownloadState.progressFraction: Float?
    get() = (this as? UpdateDownloadState.Downloading)?.let { state ->
        state.totalBytes?.takeIf { it > 0 }?.let { (state.downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }
    }

/**
 * Starts the installer for a Ready APK. The first time, Android needs "install unknown apps" for
 * Miffan: a sheet explains it, opens the switch, and installation continues once the user is back.
 */
@Composable
internal fun rememberUpdateInstaller(vm: UpdateVM): () -> Unit {
    val context = LocalContext.current
    var asking by remember { mutableStateOf(false) }
    var waitingForPermission by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(waitingForPermission) {
        if (waitingForPermission && context.packageManager.canRequestPackageInstalls()) {
            waitingForPermission = false
            vm.install(context)
        }
        onPauseOrDispose { }
    }
    if (asking) {
        InstallPermissionSheet(
            onAllow = {
                asking = false
                if (context.openInstallPermission()) waitingForPermission = true else vm.install(context)
            },
            onDismiss = { asking = false },
        )
    }
    return remember(vm, context) {
        { if (context.packageManager.canRequestPackageInstalls()) vm.install(context) else asking = true }
    }
}

private fun Context.openInstallPermission(): Boolean = runCatching {
    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:$packageName".toUri()))
}.isSuccess

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstallPermissionSheet(onAllow: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(R.string.update_permission_title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(R.string.update_permission_text), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            // What the user will see in system settings, so the switch is easy to recognize.
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // System settings list the app under its launcher icon, not the in-app role theme.
                    val context = LocalContext.current
                    val launcherIcon = remember(context) {
                        runCatching { LauncherIconManager(context).selectedIcon() }.getOrNull() ?: LauncherIcon.MIFFAN
                    }
                    rememberLauncherIconPreview(launcherIcon)?.let { Image(it, null, Modifier.size(36.dp)) }
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.update_permission_switch), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = true, onCheckedChange = null, enabled = false)
                }
            }
            Text(stringResource(R.string.update_permission_after), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onAllow, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.update_permission_open))
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.cancel)) }
        }
    }
}

/** The 「我」 page (and Settings page) card: a new version, its download, or an APK waiting to be installed. */
@Composable
fun UpdateCard(modifier: Modifier = Modifier, vm: UpdateVM = koinViewModel()) {
    val presentation by vm.presentation.collectAsStateWithLifecycle()
    val info = presentation.available
    if (info == null || !presentation.showCard) return
    val nav = LocalNavController.current
    val install = rememberUpdateInstaller(vm)
    val notes = rememberReleaseNotes(info)
    val state = presentation.download
    val open = { nav.navigate(Screen.Update) }
    val headline = notes.items.firstOrNull()?.let { it.title ?: it.body }
    val (title, supporting) = when (state) {
        is UpdateDownloadState.Downloading -> stringResource(R.string.update_card_downloading_title, info.version) to
            (state.progressFraction?.let { stringResource(R.string.update_card_downloading_percent, (it * 100).toInt()) }
                ?: stringResource(R.string.update_card_downloading_indeterminate))
        is UpdateDownloadState.Verifying -> stringResource(R.string.update_card_downloading_title, info.version) to
            stringResource(R.string.update_card_verifying)
        is UpdateDownloadState.Ready -> stringResource(R.string.update_card_ready_title, info.version) to
            stringResource(R.string.update_keeps_data)
        is UpdateDownloadState.Failed -> stringResource(R.string.update_card_new_title, info.version) to
            stringResource(R.string.update_card_failed_tap_for_details)
        UpdateDownloadState.Idle -> stringResource(R.string.update_card_new_title, info.version) to
            (headline ?: stringResource(R.string.update_card_new_fallback))
    }
    Surface(
        onClick = open,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppCharacterMascot(MiffanMascotState.UpdateAvailable, Modifier.size(48.dp), presentation = MiffanPresentation.Avatar)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(supporting, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
                }
                Button(
                    onClick = if (state is UpdateDownloadState.Ready) install else open,
                    modifier = Modifier.heightIn(min = 44.dp),
                ) {
                    Text(stringResource(when (state) {
                        is UpdateDownloadState.Ready -> R.string.update_card_install
                        UpdateDownloadState.Idle -> R.string.update_card_look
                        else -> R.string.update_card_view
                    }))
                }
            }
            if (state is UpdateDownloadState.Downloading || state is UpdateDownloadState.Verifying) {
                val fraction = state.progressFraction
                val barModifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                if (fraction != null) LinearProgressIndicator(progress = { fraction }, modifier = barModifier)
                else LinearProgressIndicator(modifier = barModifier)
            }
        }
    }
}

/** One row per change: a short headline when the note has one, then the rest of the sentence. */
@Composable
internal fun ReleaseNoteRow(item: ReleaseNoteItem, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.padding(top = 8.dp).size(8.dp)) {
            Surface(Modifier.size(8.dp), shape = CircleShape, color = MaterialTheme.colorScheme.primary) { }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            item.title?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
            if (item.body.isNotEmpty()) {
                Text(item.body, style = MaterialTheme.typography.bodyMedium,
                    color = if (item.title != null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
            item.details.forEach { detail ->
                Text("· $detail", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Shown once at the top of 聊天 after an update; opens what changed, or goes away with ×. */
@Composable
fun UpdateWhatsNewCard(modifier: Modifier = Modifier, vm: UpdateVM = koinViewModel()) {
    val presentation by vm.presentation.collectAsStateWithLifecycle()
    val info = presentation.whatsNew ?: return
    val notes = rememberReleaseNotes(info)
    if (notes.items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Surface(
        onClick = { open = true },
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppCharacterMascot(MiffanMascotState.Happy, Modifier.size(44.dp), presentation = MiffanPresentation.Avatar)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.update_whats_new_card_title, info.version), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.update_whats_new_card_text), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = vm::dismissWhatsNew) {
                Icon(HugeIcons.Cancel01, stringResource(R.string.update_whats_new_dismiss), Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (open) WhatsNewSheet(info, notes, onDone = { open = false; vm.dismissWhatsNew() })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WhatsNewSheet(info: UpdateInfo, notes: ReleaseNotes, onDone: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.update_whats_new_title, info.version), style = MaterialTheme.typography.headlineSmall)
                Text(stringResource(R.string.update_whats_new_subtitle), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                notes.items.forEach { ReleaseNoteRow(it) }
            }
            Button(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.update_whats_new_done))
            }
        }
    }
}

/** A small tag beside 「关于 Miffan」 while a snoozed update is still waiting. */
@Composable
fun UpdateWaitingTag(text: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 10.dp, vertical = 3.dp), style = MaterialTheme.typography.labelMedium)
    }
}

/** The About page's update block: current state, a manual check, and the way into 更新设置. */
@Composable
fun UpdateStatusCard(modifier: Modifier = Modifier, vm: UpdateVM = koinViewModel()) {
    val presentation by vm.presentation.collectAsStateWithLifecycle()
    if (presentation.isPlayStore) return
    val nav = LocalNavController.current
    val colors = MaterialTheme.colorScheme
    val info = presentation.available
    Surface(modifier.fillMaxWidth(), color = colors.surfaceContainerLow, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val (tile, tileContent) = when {
                    presentation.checking -> colors.surfaceContainerHighest to colors.onSurfaceVariant
                    info != null -> colors.primary to colors.onPrimary
                    presentation.lastCheckFailed -> colors.errorContainer to colors.onErrorContainer
                    else -> colors.secondaryContainer to colors.onSecondaryContainer
                }
                Surface(Modifier.size(44.dp), shape = RoundedCornerShape(14.dp), color = tile, contentColor = tileContent) {
                    Box(contentAlignment = Alignment.Center) {
                        when {
                            presentation.checking -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.5.dp)
                            info != null -> Icon(HugeIcons.ArrowUp02, null, Modifier.size(22.dp))
                            presentation.lastCheckFailed -> Icon(HugeIcons.WifiDisconnected02, null, Modifier.size(22.dp))
                            else -> Icon(HugeIcons.CheckmarkCircle02, null, Modifier.size(22.dp))
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        when {
                            presentation.checking -> stringResource(R.string.update_status_checking)
                            info != null -> stringResource(R.string.update_card_new_version_found, info.version)
                            presentation.lastCheckFailed -> stringResource(R.string.update_status_failed)
                            else -> stringResource(R.string.update_status_latest)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    when {
                        presentation.checking -> Unit
                        info != null -> {
                            val count = rememberReleaseNotes(info).items.size
                            Text(
                                listOfNotNull(
                                    info.downloads.firstOrNull()?.sizeBytes?.fileSizeToString(),
                                    if (count > 0) pluralStringResource(R.plurals.update_status_change_count, count, count) else null,
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                            )
                        }
                        presentation.lastCheckFailed -> Text(stringResource(R.string.update_status_failed_desc),
                            style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                        else -> Surface(color = colors.surfaceContainerHighest, shape = RoundedCornerShape(50)) {
                            Text(
                                if (presentation.lastCheckedAt == 0L) stringResource(R.string.update_status_never_checked)
                                else stringResource(R.string.update_status_checked_at,
                                    DateUtils.getRelativeTimeSpanString(presentation.lastCheckedAt, System.currentTimeMillis(),
                                        DateUtils.MINUTE_IN_MILLIS).toString()),
                                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            if (info != null) {
                Button(onClick = { nav.navigate(Screen.Update) }, modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(R.string.update_status_open))
                }
            } else {
                FilledTonalButton(onClick = vm::checkNow, enabled = !presentation.checking,
                    modifier = Modifier.fillMaxWidth().height(48.dp)) {
                    Text(stringResource(when {
                        presentation.checking -> R.string.update_status_checking
                        presentation.lastCheckFailed -> R.string.update_card_retry
                        else -> R.string.update_status_check
                    }))
                }
            }
            Surface(onClick = { nav.navigate(Screen.UpdateSettings) }, color = colors.surfaceContainerLow, shape = RoundedCornerShape(12.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.update_settings_title), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(HugeIcons.ArrowRight01, null, tint = colors.onSurfaceVariant)
                }
            }
        }
    }
}

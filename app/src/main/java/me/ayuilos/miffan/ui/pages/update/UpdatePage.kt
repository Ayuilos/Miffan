package me.ayuilos.miffan.ui.pages.update

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.TextToastAction
import me.ayuilos.miffan.BuildConfig
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.ui.AppCharacterMascot
import me.ayuilos.miffan.ui.components.ui.MiffanMascotState
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.im.ImCardGroup
import me.ayuilos.miffan.ui.im.ImPageBar
import me.ayuilos.miffan.ui.im.ImSectionLabel
import me.ayuilos.miffan.utils.UpdateDownloadError
import me.ayuilos.miffan.utils.UpdateDownloadState
import me.ayuilos.miffan.utils.UpdateInfo
import me.ayuilos.miffan.utils.UpdatePresentation
import me.ayuilos.miffan.utils.fileSizeToString
import me.ayuilos.miffan.utils.openUrl
import me.ayuilos.miffan.utils.toLocalDate
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import org.koin.androidx.compose.koinViewModel
import java.time.Instant

@Composable
fun UpdatePage(vm: UpdateVM = koinViewModel()) {
    val presentation by vm.presentation.collectAsStateWithLifecycle()
    val info = presentation.available
    Scaffold(
        topBar = { ImPageBar(stringResource(R.string.update_page_title)) },
        bottomBar = { if (info != null) UpdateActions(vm, info, presentation) },
    ) { padding ->
        if (info == null) UpToDate(vm, presentation, padding) else UpdateDetails(vm, info, padding)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateDetails(vm: UpdateVM, info: UpdateInfo, padding: PaddingValues) {
    val context = LocalContext.current
    val notes = rememberReleaseNotes(info)
    // Looking at the page is enough to clear this version's red dot.
    LaunchedEffect(info.version) { vm.markSeen(info.version) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(),
            bottom = padding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("hero") {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AppCharacterMascot(MiffanMascotState.UpdateAvailable, Modifier.size(112.dp))
                Text(info.version, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.update_page_subtitle), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    info.downloads.firstOrNull()?.sizeBytes?.let { InfoChip(it.fileSizeToString()) }
                    runCatching { Instant.parse(info.publishedAt).toLocalDate() }.getOrNull()?.let { InfoChip(it) }
                    InfoChip(stringResource(R.string.update_page_from_version, BuildConfig.VERSION_NAME))
                }
            }
        }
        notes.notice?.let { notice ->
            item("notice") {
                Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                    shape = RoundedCornerShape(16.dp)) {
                    Text(notice, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (notes.sections.isEmpty()) {
            item("no-notes") {
                Text(stringResource(R.string.update_page_no_notes), Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center)
            }
        }
        notes.sections.forEachIndexed { index, section ->
            item("section:$index") {
                Column {
                    ImSectionLabel(section.title ?: stringResource(R.string.update_page_changes))
                    Spacer(Modifier.height(8.dp))
                    ImCardGroup {
                        section.items.forEachIndexed { i, item ->
                            if (i > 0) HorizontalDivider(Modifier.padding(start = 36.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                            ReleaseNoteRow(item, Modifier.padding(16.dp))
                        }
                    }
                }
            }
        }
        item("release") {
            TextButton(onClick = { context.openUrl(info.releaseUrl) }) {
                Text(stringResource(R.string.update_page_full_notes))
            }
        }
    }
}

@Composable
private fun InfoChip(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(50)) {
        Text(text, Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UpdateActions(vm: UpdateVM, info: UpdateInfo, presentation: UpdatePresentation) {
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    val resources = LocalResources.current
    val install = rememberUpdateInstaller(vm)
    val download = info.downloads.firstOrNull()
    val state = presentation.download
    // Open the installer by itself only for a download the user watched finish on this page.
    var watching by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        when (state) {
            is UpdateDownloadState.Ready -> if (watching) { watching = false; install() }
            is UpdateDownloadState.Failed, UpdateDownloadState.Idle -> watching = false
            else -> Unit
        }
    }
    val start = { if (download != null) { watching = true; vm.download(download) } }
    val laterOrSkip: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton(onClick = {
                vm.snooze(info.version)
                toaster.show(resources.getString(R.string.update_snoozed_toast))
                nav.popBackStack()
            }, modifier = Modifier.heightIn(min = 44.dp)) { Text(stringResource(R.string.update_page_later)) }
            TextButton(onClick = {
                vm.skip(info.version)
                toaster.show(
                    resources.getString(R.string.update_skipped_toast, info.version),
                    action = TextToastAction(resources.getString(R.string.update_undo)) { vm.unskip() },
                )
                nav.popBackStack()
            }, modifier = Modifier.heightIn(min = 44.dp)) {
                Text(stringResource(R.string.update_page_skip), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 2.dp) {
        Column(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            when (state) {
                UpdateDownloadState.Idle -> {
                    Button(onClick = start, enabled = download != null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(download?.sizeBytes?.let { stringResource(R.string.update_page_update_size, it.fileSizeToString()) }
                            ?: stringResource(R.string.update_page_update))
                    }
                    laterOrSkip()
                }

                is UpdateDownloadState.Downloading, is UpdateDownloadState.Verifying -> {
                    ProgressPill(state, onCancel = vm::cancelDownload)
                    Hint(stringResource(R.string.update_page_downloading_hint))
                }

                is UpdateDownloadState.Ready -> {
                    Button(onClick = install, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(stringResource(R.string.update_card_install))
                    }
                    Hint(stringResource(R.string.update_keeps_data))
                }

                is UpdateDownloadState.Failed -> {
                    Text(
                        stringResource(when (state.error) {
                            UpdateDownloadError.Network -> R.string.update_page_failed_network
                            UpdateDownloadError.Verification -> R.string.update_card_failed_verification
                            UpdateDownloadError.Storage -> R.string.update_card_failed_storage
                        }),
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = start, enabled = download != null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Text(stringResource(
                            if (state.error == UpdateDownloadError.Network) R.string.update_page_resume else R.string.update_card_retry
                        ))
                    }
                    laterOrSkip()
                }
            }
        }
    }
}

/** The download's progress fills the button it replaced; × cancels. */
@Composable
private fun ProgressPill(state: UpdateDownloadState, onCancel: () -> Unit) {
    val fraction = if (state is UpdateDownloadState.Verifying) 1f else state.progressFraction ?: 0f
    Box(
        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.72f)),
        contentAlignment = Alignment.Center,
    ) {
        // The filled part is the button's full colour; the rest is a paler version of it.
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(fraction)
            .background(MaterialTheme.colorScheme.primary))
        Text(
            if (state is UpdateDownloadState.Verifying) stringResource(R.string.update_card_verifying)
            else state.progressFraction?.let { stringResource(R.string.update_card_downloading_percent, (it * 100).toInt()) }
                ?: stringResource(R.string.update_card_downloading_indeterminate),
            style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onPrimary,
        )
        IconButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterEnd)) {
            Icon(HugeIcons.Cancel01, stringResource(R.string.update_card_cancel_download), Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxWidth().heightIn(min = 44.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center)
    }
}

/** Reached from a notification after the version was skipped or installed, or on the Play Store build. */
@Composable
private fun UpToDate(vm: UpdateVM, presentation: UpdatePresentation, padding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(padding).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        AppCharacterMascot(MiffanMascotState.Happy, Modifier.size(112.dp))
        val skipped = presentation.skippedVersion
        if (skipped != null) {
            // Skipped is not the same as up to date: say so, and offer the way back.
            Text(stringResource(R.string.update_page_skipped_title, skipped), style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center)
            Text(stringResource(R.string.update_page_skipped_text), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Button(onClick = vm::unskip) { Text(stringResource(R.string.update_settings_unskip)) }
            return@Column
        }
        Text(stringResource(R.string.update_status_latest), style = MaterialTheme.typography.titleLarge)
        Text(BuildConfig.VERSION_NAME, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!presentation.isPlayStore) {
            Button(onClick = vm::checkNow, enabled = !presentation.checking) {
                Text(stringResource(if (presentation.checking) R.string.update_status_checking else R.string.update_status_check))
            }
        }
    }
}

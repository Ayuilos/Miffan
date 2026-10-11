package me.ayuilos.miffan.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEach
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AlertCircle
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.CheckmarkCircle02
import me.rerere.hugeicons.stroke.Download01
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.richtext.MarkdownBlock
import me.ayuilos.miffan.ui.hooks.useThrottle
import me.ayuilos.miffan.utils.UpdateDownload
import me.ayuilos.miffan.utils.UpdateDownloadError
import me.ayuilos.miffan.utils.UpdateDownloadState
import me.ayuilos.miffan.utils.UpdateInfo
import me.ayuilos.miffan.utils.fileSizeToString
import me.ayuilos.miffan.utils.openUrl
import me.ayuilos.miffan.utils.toLocalDateTime
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlin.time.toJavaInstant

@OptIn(ExperimentalTime::class)
@Composable
fun UpdateAvailableBanner(
    info: UpdateInfo,
    downloadState: UpdateDownloadState,
    onDownload: (UpdateDownload) -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showDetail by remember { mutableStateOf(false) }
    // A state left over from an older release must not be shown against this one.
    val state = downloadState.takeIf { s ->
        s.download?.let { active -> info.downloads.any { it.name == active.name } } == true
    } ?: UpdateDownloadState.Idle

    Card(
        onClick = {
            if (state is UpdateDownloadState.Ready) onInstall() else showDetail = true
        },
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
    ) {
        ListItem(
            headlineContent = {
                Text(
                    text = stringResource(R.string.update_card_new_version_found, info.version),
                    style = MaterialTheme.typography.titleSmall,
                )
            },
            supportingContent = bannerStatusText(state)?.let { text ->
                {
                    Text(
                        text = text,
                        color = if (state is UpdateDownloadState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            Color.Unspecified
                        },
                    )
                }
            },
            leadingContent = {
                Icon(
                    imageVector = when (state) {
                        is UpdateDownloadState.Ready -> HugeIcons.CheckmarkCircle02
                        is UpdateDownloadState.Failed -> HugeIcons.AlertCircle
                        else -> HugeIcons.Download01
                    },
                    contentDescription = null,
                )
            },
            trailingContent = {
                Icon(
                    imageVector = HugeIcons.ArrowRight01,
                    contentDescription = null,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        DownloadProgress(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
    }

    if (showDetail) {
        // Only an install the user is watching may open the installer by itself.
        var sawVerifying by remember { mutableStateOf(false) }
        LaunchedEffect(state) {
            when (state) {
                is UpdateDownloadState.Verifying -> sawVerifying = true
                is UpdateDownloadState.Ready -> if (sawVerifying) {
                    sawVerifying = false
                    onInstall()
                }

                else -> sawVerifying = false
            }
        }
        val downloadHandler = useThrottle<UpdateDownload>(500) { item -> onDownload(item) }
        ModalBottomSheet(
            onDismissRequest = { showDetail = false },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = info.version,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = Instant.parse(info.publishedAt).toJavaInstant().toLocalDateTime(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (info.changelog.isNotBlank()) {
                    MarkdownBlock(
                        content = info.changelog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(stringResource(R.string.update_changelog_see_release))
                }
                info.downloads.fastForEach { downloadItem ->
                    val itemState = state.takeIf { it.download?.name == downloadItem.name }
                        ?: UpdateDownloadState.Idle
                    UpdateDownloadItem(
                        download = downloadItem,
                        state = itemState,
                        onDownload = { downloadHandler(downloadItem) },
                        onCancel = onCancelDownload,
                        onInstall = onInstall,
                    )
                }
                OutlinedCard(onClick = { context.openUrl(info.releaseUrl) }) {
                    ListItem(
                        headlineContent = { Text("GitHub Release") },
                        supportingContent = { Text(info.releaseUrl) },
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateDownloadItem(
    download: UpdateDownload,
    state: UpdateDownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onInstall: () -> Unit,
) {
    OutlinedCard(
        onClick = {
            when (state) {
                is UpdateDownloadState.Idle, is UpdateDownloadState.Failed -> onDownload()
                is UpdateDownloadState.Ready -> onInstall()
                else -> Unit
            }
        },
    ) {
        ListItem(
            headlineContent = { Text(text = download.name) },
            supportingContent = {
                when (state) {
                    is UpdateDownloadState.Downloading -> Text(
                        listOfNotNull(
                            state.totalBytes?.let {
                                "${state.downloadedBytes.fileSizeToString()} / ${it.fileSizeToString()}"
                            } ?: state.downloadedBytes.fileSizeToString(),
                            state.sourceHost,
                        ).joinToString(" · ")
                    )

                    is UpdateDownloadState.Verifying -> Text(stringResource(R.string.update_card_verifying))
                    is UpdateDownloadState.Ready -> Text(stringResource(R.string.update_card_ready))
                    is UpdateDownloadState.Failed -> Text(
                        text = failureText(state.error),
                        color = MaterialTheme.colorScheme.error,
                    )

                    is UpdateDownloadState.Idle -> Text(
                        listOfNotNull(
                            download.url.toHttpUrlOrNull()?.host,
                            download.sizeBytes?.fileSizeToString(),
                        ).joinToString(" · ")
                    )
                }
            },
            leadingContent = {
                Icon(
                    imageVector = when (state) {
                        is UpdateDownloadState.Ready -> HugeIcons.CheckmarkCircle02
                        is UpdateDownloadState.Failed -> HugeIcons.AlertCircle
                        else -> HugeIcons.Download01
                    },
                    contentDescription = null,
                )
            },
            trailingContent = when (state) {
                is UpdateDownloadState.Downloading, is UpdateDownloadState.Verifying -> {
                    {
                        IconButton(onClick = onCancel) {
                            Icon(
                                imageVector = HugeIcons.Cancel01,
                                contentDescription = stringResource(R.string.update_card_cancel_download),
                            )
                        }
                    }
                }

                is UpdateDownloadState.Failed -> {
                    { Text(stringResource(R.string.update_card_retry), color = MaterialTheme.colorScheme.primary) }
                }

                else -> null
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        DownloadProgress(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
        )
        if (state is UpdateDownloadState.Ready) {
            Button(
                onClick = onInstall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            ) {
                Text(stringResource(R.string.update_card_install))
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: UpdateDownloadState, modifier: Modifier = Modifier) {
    when (state) {
        is UpdateDownloadState.Downloading -> {
            val fraction = state.progressFraction()
            if (fraction != null) {
                LinearProgressIndicator(progress = { fraction }, modifier = modifier)
            } else {
                LinearProgressIndicator(modifier = modifier)
            }
        }

        is UpdateDownloadState.Verifying -> LinearProgressIndicator(modifier = modifier)
        else -> Unit
    }
}

@Composable
private fun bannerStatusText(state: UpdateDownloadState): String? = when (state) {
    is UpdateDownloadState.Downloading -> state.progressFraction()?.let {
        stringResource(R.string.update_card_downloading_percent, (it * 100).toInt())
    } ?: stringResource(R.string.update_card_downloading_indeterminate)

    is UpdateDownloadState.Verifying -> stringResource(R.string.update_card_verifying)
    is UpdateDownloadState.Ready -> stringResource(R.string.update_card_ready_tap_to_install)
    is UpdateDownloadState.Failed -> stringResource(R.string.update_card_failed_tap_for_details)
    is UpdateDownloadState.Idle -> null
}

@Composable
private fun failureText(error: UpdateDownloadError): String = stringResource(
    when (error) {
        UpdateDownloadError.Network -> R.string.update_card_failed_network
        UpdateDownloadError.Verification -> R.string.update_card_failed_verification
        UpdateDownloadError.Storage -> R.string.update_card_failed_storage
    }
)

private val UpdateDownloadState.download: UpdateDownload?
    get() = when (this) {
        is UpdateDownloadState.Downloading -> download
        is UpdateDownloadState.Verifying -> download
        is UpdateDownloadState.Ready -> download
        is UpdateDownloadState.Failed -> download
        UpdateDownloadState.Idle -> null
    }

private fun UpdateDownloadState.Downloading.progressFraction(): Float? =
    totalBytes?.takeIf { it > 0 }?.let { (downloadedBytes.toFloat() / it).coerceIn(0f, 1f) }

package me.ayuilos.miffan.ui.components.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
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
import com.dokar.sonner.ToastType
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Download01
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.components.richtext.MarkdownBlock
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.hooks.useThrottle
import me.ayuilos.miffan.utils.UpdateDownload
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
    onDownload: (UpdateDownload) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val downloadingMessage = stringResource(R.string.update_card_downloading)
    var showDetail by remember { mutableStateOf(false) }

    Card(
        onClick = { showDetail = true },
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
            leadingContent = {
                Icon(
                    imageVector = HugeIcons.Download01,
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
    }

    if (showDetail) {
        val downloadHandler = useThrottle<UpdateDownload>(500) { item ->
            onDownload(item)
            showDetail = false
            toaster.show(downloadingMessage, type = ToastType.Info)
        }
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
                    Text("更新日志请查看下方 GitHub Release。")
                }
                info.downloads.fastForEach { downloadItem ->
                    OutlinedCard(
                        onClick = { downloadHandler(downloadItem) },
                    ) {
                        ListItem(
                            headlineContent = { Text(text = downloadItem.name) },
                            supportingContent = {
                                Text(
                                    listOfNotNull(
                                        downloadItem.url.toHttpUrlOrNull()?.host,
                                        downloadItem.sizeBytes?.fileSizeToString(),
                                    ).joinToString(" · ")
                                )
                            },
                            leadingContent = {
                                Icon(
                                    imageVector = HugeIcons.Download01,
                                    contentDescription = null,
                                )
                            },
                        )
                    }
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

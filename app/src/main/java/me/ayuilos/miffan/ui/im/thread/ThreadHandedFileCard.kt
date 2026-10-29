package me.ayuilos.miffan.ui.im.thread

import android.content.ClipData
import android.webkit.MimeTypeMap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.PartnerSpaces
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.pages.extensions.workspace.openWorkspaceFileExternally
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.compose.koinInject

/**
 * A file the partner gave from its mini box, in the reply that gave it: what it is and where it landed on the
 * owner's computer, with a way to read it on the phone (fetched from the mini box's Shared folder, where the
 * original stays) and to copy the path.
 */
@Composable
internal fun ThreadHandedFileCard(file: HandedFile, partner: String) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val repository: WorkspaceRepository = koinInject()
    var opening by remember(file.toolCallId) { mutableStateOf(false) }
    val copied = stringResource(R.string.im_space_handoff_copied)
    val failed = stringResource(R.string.im_space_files_failed)
    val colors = MaterialTheme.colorScheme
    val where = if (file.computer.isBlank()) file.folder else stringResource(R.string.im_computer_handoff_where, file.computer, file.folder)
    val view = file.sharedPath?.takeIf { file.workspaceId != null }
    Surface(shape = RoundedCornerShape(16.dp), color = colors.surfaceContainerLowest, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(HugeIcons.ArrowRight01, null, Modifier.size(14.dp), tint = colors.tertiary)
                Text(if (partner.isBlank()) stringResource(R.string.im_computer_handoff_title_any) else stringResource(R.string.im_computer_handoff_title, partner),
                    style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, color = colors.tertiary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val extension = file.name.substringAfterLast('.', "").take(4).uppercase().takeIf { file.count == 1 && it.isNotEmpty() }
                Surface(color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        if (extension != null) Text(extension, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        else Icon(if (file.count > 1) HugeIcons.Folder01 else HugeIcons.File01, null, Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (file.count > 1) pluralStringResource(R.plurals.im_computer_handoff_count, file.count, file.count) + " · " + where else where,
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (view != null) Button(onClick = {
                    scope.launch {
                        opening = true
                        runCatching {
                            val local = withContext(Dispatchers.IO) {
                                val directory = File(context.cacheDir, "space_files/${UUID.randomUUID()}").apply { mkdirs() }
                                File(directory, view.substringAfterLast('/')).also { target ->
                                    target.outputStream().use {
                                        repository.exportFile(file.workspaceId!!, WorkspaceStorageArea.FILES, "${PartnerSpaces.SHARED_RELATIVE}/$view", it)
                                    }
                                }
                            }
                            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(local.extension.lowercase()) ?: "application/octet-stream"
                            context.openWorkspaceFileExternally(local, mime).getOrThrow()
                        }.onFailure {
                            if (it is CancellationException) throw it
                            toaster.show(failed, type = ToastType.Error)
                        }
                        opening = false
                    }
                }, Modifier.weight(1f).height(40.dp), enabled = !opening,
                    colors = ButtonDefaults.filledTonalButtonColors()) {
                    Text(stringResource(R.string.im_computer_handoff_view), maxLines = 1)
                }
                Button(onClick = {
                    scope.launch {
                        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Miffan", file.hostPath)))
                        toaster.show(copied, type = ToastType.Success)
                    }
                }, Modifier.weight(1f).height(40.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.surfaceContainerHigh, contentColor = colors.onSurface)) {
                    Text(stringResource(R.string.im_computer_handoff_copy), maxLines = 1)
                }
            }
        }
    }
}

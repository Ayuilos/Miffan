package me.ayuilos.miffan.ui.components.message

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.ai.tools.WorkspaceArtifact
import me.ayuilos.miffan.data.ai.tools.workspaceArtifacts
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.pages.extensions.workspace.exportArtifactToCache
import me.ayuilos.miffan.ui.pages.extensions.workspace.parentDirectory
import me.ayuilos.miffan.ui.pages.extensions.workspace.shareWorkspaceFile
import me.ayuilos.miffan.utils.fileSizeToString
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.File02
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.FileView
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Share08
import org.koin.compose.koinInject

private const val DEFAULT_VISIBLE_COUNT = 3

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun EditedFilesList(
    parts: List<UIMessagePart>,
    assistant: Assistant?,
) {
    val fallbackWorkspaceId = assistant?.workspaceId?.toString()
    val artifacts = remember(parts, fallbackWorkspaceId) {
        parts.filterIsInstance<UIMessagePart.Tool>()
            .flatMap { it.workspaceArtifacts(fallbackWorkspaceId) }
            .distinctBy { "${it.workspaceId}:${it.scopeId}:${it.path}" }
    }
    if (artifacts.isEmpty()) return

    val context = LocalContext.current
    val navController = LocalNavController.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val workspaceRepository: WorkspaceRepository = koinInject()

    var selectedArtifact by remember { mutableStateOf<WorkspaceArtifact?>(null) }
    var exportArtifact by remember { mutableStateOf<WorkspaceArtifact?>(null) }
    var expanded by remember { mutableStateOf(false) }
    val visibleFiles = if (expanded) artifacts else artifacts.take(DEFAULT_VISIBLE_COUNT)
    val hasMore = artifacts.size > DEFAULT_VISIBLE_COUNT

    fun openArtifact(artifact: WorkspaceArtifact) {
        navController.navigate(
            Screen.WorkspaceFilePreview(
                id = artifact.workspaceId,
                path = artifact.path,
                scopeId = artifact.scopeId,
            )
        )
    }

    fun reportFailure(error: Throwable) {
        toaster.show(error.message ?: "Unable to access workspace file", type = ToastType.Error)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*"),
    ) { uri ->
        val artifact = exportArtifact.also { exportArtifact = null }
            ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    workspaceRepository.exportRootfsArtifact(
                        artifact.workspaceId,
                        artifact.path,
                        output,
                        artifact.scopeId,
                    )
                } ?: error("Unable to open export destination")
            }.onFailure(::reportFailure)
        }
    }

    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        visibleFiles.forEach { artifact ->
            Surface(
                onClick = { openArtifact(artifact) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.tertiaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(start = 10.dp, end = 2.dp, top = 2.dp, bottom = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = HugeIcons.File02,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Column(modifier = Modifier.widthIn(max = 200.dp)) {
                        Text(
                            text = artifact.name,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        artifact.sizeBytes?.let { size ->
                            Text(
                                text = size.fileSizeToString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.65f),
                            )
                        }
                    }
                    IconButton(
                        onClick = { selectedArtifact = artifact },
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = HugeIcons.MoreVertical,
                            contentDescription = "File actions",
                            modifier = Modifier.size(16.dp),
                        )
                    }
                }
            }
        }
        if (hasMore && !expanded) {
            Surface(
                onClick = { expanded = true },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Text(
                    text = "+${artifacts.size - DEFAULT_VISIBLE_COUNT}",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                )
            }
        }
    }

    selectedArtifact?.let { artifact ->
        ModalBottomSheet(
            onDismissRequest = { selectedArtifact = null },
            sheetState = rememberBottomSheetState(
                initialValue = SheetValue.Hidden,
                enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = artifact.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = artifact.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                ArtifactActionCard(
                    icon = HugeIcons.FileView,
                    label = "Preview",
                    onClick = {
                        selectedArtifact = null
                        openArtifact(artifact)
                    },
                )
                ArtifactActionCard(
                    icon = HugeIcons.Folder01,
                    label = "Locate in workspace",
                    onClick = {
                        selectedArtifact = null
                        val location = artifact.location()
                        navController.navigate(
                            Screen.WorkspaceDetail(
                                id = artifact.workspaceId,
                                area = location.area.name,
                                path = artifact.parentDirectory(),
                                openFiles = true,
                                scopeId = artifact.scopeId,
                            )
                        )
                    },
                )
                ArtifactActionCard(
                    icon = HugeIcons.FileImport,
                    label = stringResource(R.string.common_export),
                    onClick = {
                        selectedArtifact = null
                        exportArtifact = artifact
                        exportLauncher.launch(artifact.name)
                    },
                )
                ArtifactActionCard(
                    icon = HugeIcons.Share08,
                    label = stringResource(R.string.common_share),
                    onClick = {
                        selectedArtifact = null
                        scope.launch {
                            runCatching {
                                workspaceRepository.exportArtifactToCache(context, artifact)
                            }.onSuccess { file ->
                                context.shareWorkspaceFile(file, artifact.mimeType)
                                    .onFailure(::reportFailure)
                            }.onFailure(::reportFailure)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ArtifactActionCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, shape = MaterialTheme.shapes.medium) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(4.dp))
            Text(text = label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

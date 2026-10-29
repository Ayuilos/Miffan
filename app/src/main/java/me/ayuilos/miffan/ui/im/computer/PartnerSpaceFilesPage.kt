package me.ayuilos.miffan.ui.im.computer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import android.text.format.DateUtils
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.PartnerSpaces
import me.ayuilos.miffan.data.ai.computer.SpaceFileDirection
import me.ayuilos.miffan.data.model.Avatar
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.im.ImEmpty
import me.ayuilos.miffan.ui.im.ImPageBar
import me.ayuilos.miffan.ui.im.ImPartnerVM
import me.ayuilos.miffan.ui.im.imChatTime
import me.ayuilos.miffan.ui.im.thread.threadAssistantName
import me.ayuilos.miffan.ui.pages.extensions.workspace.openWorkspaceFileExternally
import me.ayuilos.miffan.ui.pages.extensions.workspace.shareWorkspaceFile
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Delete02
import me.rerere.hugeicons.stroke.Download04
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.Exchange01
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.FolderAdd
import me.rerere.hugeicons.stroke.FolderOpen
import me.rerere.hugeicons.stroke.SmartPhone01
import me.rerere.hugeicons.stroke.View
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Share08
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import android.content.ClipData
import android.content.ClipboardManager
import kotlin.uuid.Uuid

/**
 * The Shared folder on a partner's own computer, from the phone: what the partner left there to open,
 * save or share, and files to give it. What the owner adds here the partner hears about on its next turn,
 * like files dropped in the handoff folder on the host.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PartnerSpaceFilesPage(assistantId: Uuid) {
    val partnerVm: ImPartnerVM = koinViewModel(key = assistantId.toString(), parameters = { parametersOf(assistantId) })
    val partner by partnerVm.assistant.collectAsStateWithLifecycle()
    val computer by rememberPartnerComputer(assistantId)
    val workspaceId = computer?.workspaceId?.takeIf { computer?.space == true }
    val name = threadAssistantName(partner)
    val repository: WorkspaceRepository = koinInject()
    val spaces: PartnerSpaces = koinInject()
    val context = LocalContext.current
    val vm: SpaceFilesVM? = workspaceId?.let { id ->
        viewModel(key = "space-files-$id") { SpaceFilesVM(context.applicationContext as Application, id, repository, spaces) }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val failed = stringResource(R.string.im_space_files_failed)
    val tooLarge = stringResource(R.string.im_space_files_too_large)
    val uploaded = stringResource(R.string.im_space_files_uploaded, name)
    val saved = stringResource(R.string.im_space_files_saved)
    val deleted = stringResource(R.string.im_space_files_deleted)
    val copied = stringResource(R.string.im_space_handoff_copied)
    LaunchedEffect(vm) {
        vm?.messages?.collect { message ->
            snackbar.showSnackbar(when (message) {
                SpaceFilesMessage.FAILED -> failed
                SpaceFilesMessage.TOO_LARGE -> tooLarge
                SpaceFilesMessage.UPLOADED -> uploaded
                SpaceFilesMessage.SAVED -> saved
                SpaceFilesMessage.DELETED -> deleted
            })
        }
    }
    LaunchedEffect(vm) {
        vm?.fetched?.collect { ready ->
            val opened = if (ready.share) context.shareWorkspaceFile(ready.file, ready.mime) else context.openWorkspaceFileExternally(ready.file, ready.mime)
            if (opened.isFailure) snackbar.showSnackbar(failed)
        }
    }
    val state = vm?.state?.collectAsStateWithLifecycle()?.value
    val notes by remember(spaces, workspaceId) { workspaceId?.let { spaces.fileNotes(it) } ?: flowOf(emptyList()) }
        .collectAsStateWithLifecycle(emptyList())
    val directions = remember(notes) { notes.associate { it.path to it.direction } }
    val folder = state?.folder.orEmpty()
    BackHandler(enabled = folder.isNotEmpty()) { vm?.open(folder.substringBeforeLast('/', "")) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm?.upload(uris)
    }
    var saving by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri ->
        val entry = saving
        saving = null
        if (uri != null && entry != null) vm?.save(entry, uri)
    }
    var acting by remember { mutableStateOf<WorkspaceFileEntry?>(null) }
    var deleting by remember { mutableStateOf<WorkspaceFileEntry?>(null) }

    Scaffold(
        topBar = {
            ImPageBar(if (folder.isEmpty()) stringResource(R.string.im_space_files_page_title) else folder.substringAfterLast('/'),
                onBack = if (folder.isNotEmpty()) ({ vm?.open(folder.substringBeforeLast('/', "")) }) else null)
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            if (state != null && state.error == null) Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background)
                .navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = state.upload == null, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Icon(HugeIcons.Add01, null, Modifier.size(19.dp))
                    Text(stringResource(R.string.im_space_files_add), Modifier.padding(start = 8.dp))
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                vm == null || state == null -> ImEmpty(stringResource(R.string.im_p5_missing_partner))
                state.error != null -> Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.im_space_files_unreachable), textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    FilledTonalButton(onClick = { vm.open(folder) }) { Text(stringResource(R.string.im_space_files_retry)) }
                }
                state.entries == null -> Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    CircularProgressIndicator()
                }
                else -> {
                    val groups = remember(state.entries) { groupSpaceFiles(state.entries, System.currentTimeMillis()) }
                    val upload = state.upload
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (folder.isEmpty()) item("about") {
                            SpaceFilesIntro(name, partner?.avatar, computer?.name, computer?.spaceHandoff, onCopy = { path ->
                                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Miffan", path))
                                scope.launch { snackbar.showSnackbar(copied) }
                            })
                        }
                        if (state.entries.isEmpty() && upload == null) item("empty") { SpaceFilesEmpty(name) }
                        if (groups.today.isNotEmpty() || upload != null) item("today") {
                            SpaceFileGroup(stringResource(R.string.im_space_files_today)) {
                                upload?.let { SpaceUploadRow(it) }
                                groups.today.forEach { entry ->
                                    SpaceFileEntryRow(entry, name, directions[vm.shared(entry.path)], today = true, busy = state.opening == entry.path,
                                        onOpen = { if (entry.isDirectory) vm.open(vm.shared(entry.path)) else vm.view(entry) }, onMore = { acting = entry })
                                }
                            }
                        }
                        if (groups.earlier.isNotEmpty()) item("earlier") {
                            SpaceFileGroup(stringResource(R.string.im_space_files_earlier)) {
                                groups.earlier.forEach { entry ->
                                    SpaceFileEntryRow(entry, name, directions[vm.shared(entry.path)], today = false, busy = state.opening == entry.path,
                                        onOpen = { if (entry.isDirectory) vm.open(vm.shared(entry.path)) else vm.view(entry) }, onMore = { acting = entry })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    acting?.let { entry ->
        ModalBottomSheet(onDismissRequest = { acting = null }) {
            Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 20.dp)) {
                Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 10.dp)) {
                    Text(entry.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val direction = directions[vm?.shared(entry.path)]
                    Text(spaceFileCaption(direction, name, spaceFileDetail(context, entry, System.currentTimeMillis())),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SpaceSheetAction(if (entry.isDirectory) HugeIcons.FolderOpen else HugeIcons.View, stringResource(R.string.im_space_files_open)) {
                    acting = null
                    if (entry.isDirectory) vm?.open(vm.shared(entry.path)) else vm?.view(entry)
                }
                if (!entry.isDirectory) {
                    SpaceSheetAction(HugeIcons.Download04, stringResource(R.string.im_space_files_save)) { acting = null; saving = entry; save.launch(entry.name) }
                    SpaceSheetAction(HugeIcons.Share08, stringResource(R.string.im_space_files_share)) { acting = null; vm?.share(entry) }
                }
                SpaceSheetAction(HugeIcons.Delete02, stringResource(R.string.im_space_files_delete_here), MaterialTheme.colorScheme.error) {
                    acting = null; deleting = entry
                }
            }
        }
    }
    deleting?.let { entry ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.im_space_files_delete_title, entry.name)) },
            text = { Text(stringResource(R.string.im_space_files_delete_body, name)) },
            confirmButton = {
                TextButton(onClick = { deleting = null; vm?.delete(entry) }) {
                    Text(stringResource(R.string.im_space_files_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.common_cancel)) } })
    }
}

/** What goes today and what is older; newest first. */
internal data class SpaceFileGroups(val today: List<WorkspaceFileEntry>, val earlier: List<WorkspaceFileEntry>)

internal fun groupSpaceFiles(entries: List<WorkspaceFileEntry>, now: Long, zone: ZoneId = ZoneId.systemDefault()): SpaceFileGroups {
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val (here, before) = entries.sortedByDescending { it.updatedAt }.partition { Instant.ofEpochMilli(it.updatedAt).atZone(zone).toLocalDate() >= today }
    return SpaceFileGroups(here, before)
}

/** "27 minutes ago"; "Just now" within the first minute. */
@Composable
internal fun spaceTimeAgo(at: Long): String {
    val now = System.currentTimeMillis()
    return if (now - at < DateUtils.MINUTE_IN_MILLIS) stringResource(R.string.im_space_time_now)
        else DateUtils.getRelativeTimeSpanString(at, now, DateUtils.MINUTE_IN_MILLIS).toString()
}

/** Who a file came from (when known), in the partner's or the owner's colour, then [detail]. */
@Composable
internal fun spaceFileCaption(direction: SpaceFileDirection?, partner: String, detail: String): AnnotatedString {
    direction ?: return AnnotatedString(detail)
    val colors = MaterialTheme.colorScheme
    return buildAnnotatedString {
        withStyle(SpanStyle(color = if (direction == SpaceFileDirection.FROM_PARTNER) colors.tertiary else colors.primary, fontWeight = FontWeight.Medium)) {
            append(stringResource(if (direction == SpaceFileDirection.FROM_PARTNER) R.string.im_space_from_partner else R.string.im_space_to_partner, partner))
        }
        if (detail.isNotEmpty()) append(" · $detail")
    }
}

/** A file's type as a tile: its extension, or a folder mark. */
@Composable
internal fun SpaceFileTile(name: String, directory: Boolean) {
    val colors = MaterialTheme.colorScheme
    val ext = if (directory) "" else name.substringAfterLast('.', "").take(4).uppercase()
    Surface(color = if (directory) colors.tertiaryContainer else colors.secondaryContainer,
        contentColor = if (directory) colors.onTertiaryContainer else colors.onSecondaryContainer,
        shape = RoundedCornerShape(10.dp), modifier = Modifier.size(44.dp)) {
        Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
            if (ext.isNotEmpty()) Text(ext, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
            else Icon(if (directory) HugeIcons.Folder01 else HugeIcons.File01, null, Modifier.size(22.dp))
        }
    }
}

private fun spaceFileDetail(context: android.content.Context, entry: WorkspaceFileEntry, now: Long): String {
    val time = DateUtils.getRelativeTimeSpanString(entry.updatedAt, now, DateUtils.MINUTE_IN_MILLIS).toString()
    return if (entry.isDirectory) time else "${Formatter.formatShortFileSize(context, entry.sizeBytes)} · $time"
}

@Composable
private fun SpaceFilesIntro(partner: String, avatar: Avatar?, host: String?, handoff: String?, onCopy: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = colors.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp, bottom = 12.dp)) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(color = colors.primaryContainer, contentColor = colors.onPrimaryContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.size(34.dp)) {
                        Box(contentAlignment = androidx.compose.ui.Alignment.Center) { Icon(HugeIcons.SmartPhone01, null, Modifier.size(18.dp)) }
                    }
                    Icon(HugeIcons.Exchange01, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    avatar?.let { AssistantAvatar(partner, it, Modifier.size(34.dp)) }
                }
                Text(stringResource(R.string.im_space_files_intro, partner), style = MaterialTheme.typography.bodyMedium)
            }
            if (host != null && handoff != null) Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surfaceContainerHigh)
                    .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(HugeIcons.Folder01, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.im_space_files_on_host, host), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("~/$handoff", style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { onCopy("~/$handoff") }) {
                    Icon(HugeIcons.Copy01, stringResource(R.string.im_space_files_copy_path), Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SpaceFilesEmpty(partner: String) {
    Column(Modifier.fillMaxWidth().padding(start = 28.dp, end = 28.dp, top = 40.dp), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(22.dp), modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
                Icon(HugeIcons.FolderAdd, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(stringResource(R.string.im_space_files_empty), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.im_space_files_empty_hint, partner), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SpaceFileGroup(title: String, rows: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 4.dp), content = rows)
        }
    }
}

@Composable
private fun SpaceFileEntryRow(
    entry: WorkspaceFileEntry,
    partner: String,
    direction: SpaceFileDirection?,
    today: Boolean,
    busy: Boolean,
    onOpen: () -> Unit,
    onMore: () -> Unit,
) {
    val context = LocalContext.current
    val time = if (today) spaceTimeAgo(entry.updatedAt) else imChatTime(Instant.ofEpochMilli(entry.updatedAt))
    val detail = if (entry.isDirectory) time else "${Formatter.formatShortFileSize(context, entry.sizeBytes)} · $time"
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).heightIn(min = 52.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SpaceFileTile(entry.name, entry.isDirectory)
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(spaceFileCaption(direction, partner, detail), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (busy) Box(Modifier.size(48.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else IconButton(onClick = onMore) {
            Icon(HugeIcons.MoreVertical, stringResource(R.string.im_space_files_actions, entry.name), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** A file on its way up: its true share when the phone said how big it is, else just that it is going. */
@Composable
private fun SpaceUploadRow(upload: SpaceFilesState.Upload) {
    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SpaceFileTile(upload.name, directory = false)
        Column(Modifier.weight(1f)) {
            Text(upload.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val percent = upload.percent
                if (percent != null) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.weight(1f))
                else LinearProgressIndicator(Modifier.weight(1f))
                Text(listOfNotNull(percent?.let { "$it%" }, upload.count.takeIf { it > 1 }?.let { "${upload.index}/$it" }).joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SpaceSheetAction(icon: ImageVector, text: String, color: Color = Color.Unspecified, onClick: () -> Unit) {
    val tint = color.takeOrElse { MaterialTheme.colorScheme.onSurface }
    Row(Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
        Text(text, style = MaterialTheme.typography.bodyLarge, color = tint)
    }
}

internal enum class SpaceFilesMessage { FAILED, TOO_LARGE, UPLOADED, SAVED, DELETED }

internal data class SpaceFilesState(
    /** Relative to the Shared folder; empty at its top. */
    val folder: String = "",
    /** Null while loading. */
    val entries: List<WorkspaceFileEntry>? = null,
    val error: Throwable? = null,
    val upload: Upload? = null,
    /** The file being fetched to open or share. */
    val opening: String? = null,
) {
    /** [bytes] of [total] read from the phone so far; [total] is null when the phone did not say how big the file is. */
    data class Upload(val name: String, val index: Int, val count: Int, val bytes: Long = 0, val total: Long? = null) {
        val percent: Int? get() = total?.takeIf { it > 0 }?.let { (bytes * 100 / it).toInt().coerceIn(0, 100) }
    }
}

internal class SpaceFilesVM(
    private val app: Application,
    private val workspaceId: String,
    private val repository: WorkspaceRepository,
    private val spaces: PartnerSpaces,
) : ViewModel() {
    private val _state = MutableStateFlow(SpaceFilesState())
    val state = _state.asStateFlow()
    val messages = kotlinx.coroutines.flow.MutableSharedFlow<SpaceFilesMessage>(extraBufferCapacity = 4)

    init { open("") }

    /** [folder] relative to the Shared folder. */
    fun open(folder: String) {
        _state.update { it.copy(folder = folder, entries = null, error = null) }
        viewModelScope.launch { load(folder) }
    }

    private suspend fun load(folder: String) {
        val result = runCatching { list(folder) }.recoverCatching { error ->
            // A Space that never used its Shared folder has none yet.
            if (folder.isNotEmpty()) throw error
            repository.runInSpace(workspaceId, "mkdir -p ${PartnerSpaces.SHARED_DIR}", 15_000)
            list(folder)
        }
        if (_state.value.folder != folder) return
        result.onSuccess { list -> _state.update { it.copy(entries = list, error = null) } }
            .onFailure { error -> if (error is CancellationException) throw error; _state.update { it.copy(error = error) } }
    }

    private suspend fun list(folder: String) =
        repository.listFiles(workspaceId, WorkspaceStorageArea.FILES, absolute(folder))

    private fun absolute(folder: String) =
        if (folder.isEmpty()) PartnerSpaces.SHARED_RELATIVE else "${PartnerSpaces.SHARED_RELATIVE}/$folder"

    /** A workspace path relative to the Shared folder, as folders are opened and the partner is told about files. */
    fun shared(path: String) = path.removePrefix("${PartnerSpaces.SHARED_RELATIVE}/")

    fun upload(uris: List<Uri>) {
        if (_state.value.upload != null) return
        val folder = _state.value.folder
        viewModelScope.launch {
            val taken = _state.value.entries.orEmpty().map { it.name }.toMutableSet()
            val added = mutableListOf<String>()
            try {
                uris.forEachIndexed { index, uri ->
                    val (displayName, size) = withContext(Dispatchers.IO) { describe(uri) }
                    if (size != null && size > PartnerSpaces.PHONE_UPLOAD_MAX_BYTES) {
                        messages.tryEmit(SpaceFilesMessage.TOO_LARGE)
                        return@forEachIndexed
                    }
                    val name = uniqueName(displayName, taken)
                    _state.update { it.copy(upload = SpaceFilesState.Upload(name, index + 1, uris.size, total = size)) }
                    val entry = withContext(Dispatchers.IO) {
                        requireNotNull(app.contentResolver.openInputStream(uri)) { "Cannot read $uri" }.use { input ->
                            repository.importFile(workspaceId, WorkspaceStorageArea.FILES, absolute(folder), name, CountingInputStream(input) { read ->
                                // Only a whole percent changes what is shown.
                                _state.update { state ->
                                    val upload = state.upload?.copy(bytes = read)
                                    if (upload == null || upload.percent == state.upload.percent) state else state.copy(upload = upload)
                                }
                            }, maxBytes = PartnerSpaces.PHONE_UPLOAD_MAX_BYTES)
                        }
                    }
                    taken += name
                    added += shared(entry.path)
                }
                if (added.isNotEmpty()) messages.tryEmit(SpaceFilesMessage.UPLOADED)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                messages.tryEmit(SpaceFilesMessage.FAILED)
            } finally {
                spaces.noteArrivals(workspaceId, added)
                _state.update { it.copy(upload = null) }
                if (_state.value.folder == folder) load(folder)
            }
        }
    }

    fun save(entry: WorkspaceFileEntry, target: Uri) {
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    requireNotNull(app.contentResolver.openOutputStream(target)) { "Cannot write $target" }.use { output ->
                        repository.exportFile(workspaceId, WorkspaceStorageArea.FILES, entry.path, output)
                    }
                }
            }.onSuccess { messages.tryEmit(SpaceFilesMessage.SAVED) }
                .onFailure { if (it is CancellationException) throw it; messages.tryEmit(SpaceFilesMessage.FAILED) }
        }
    }

    /** A file copied into the app's cache for another app to open or share. */
    data class Fetched(val file: File, val mime: String, val share: Boolean)
    val fetched = kotlinx.coroutines.flow.MutableSharedFlow<Fetched>(extraBufferCapacity = 1)

    fun view(entry: WorkspaceFileEntry) = fetch(entry, share = false)

    fun share(entry: WorkspaceFileEntry) = fetch(entry, share = true)

    private fun fetch(entry: WorkspaceFileEntry, share: Boolean) {
        if (_state.value.opening != null) return
        _state.update { it.copy(opening = entry.path) }
        viewModelScope.launch {
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(entry.name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream"
            runCatching {
                val file = withContext(Dispatchers.IO) {
                    val directory = File(app.cacheDir, "space_files/${Uuid.random()}").apply { mkdirs() }
                    File(directory, entry.name).also { file ->
                        file.outputStream().use { repository.exportFile(workspaceId, WorkspaceStorageArea.FILES, entry.path, it) }
                    }
                }
                fetched.emit(Fetched(file, mime, share))
            }.onFailure { if (it is CancellationException) throw it; messages.tryEmit(SpaceFilesMessage.FAILED) }
            _state.update { it.copy(opening = null) }
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching { repository.deleteFile(workspaceId, WorkspaceStorageArea.FILES, entry.path, recursive = true) }
                .onSuccess { spaces.forgetFile(workspaceId, shared(entry.path)); messages.tryEmit(SpaceFilesMessage.DELETED) }
                .onFailure { if (it is CancellationException) throw it; messages.tryEmit(SpaceFilesMessage.FAILED) }
            load(_state.value.folder)
        }
    }

    private fun describe(uri: Uri): Pair<String, Long?> {
        var name: String? = null
        var size: Long? = null
        app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let { name = cursor.getString(it) }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { size = cursor.getLong(it) }
            }
        }
        return spaceUploadName(name ?: uri.lastPathSegment) to size
    }
}

/** A file name the Space accepts: no path, no control characters, never empty. */
internal fun spaceUploadName(name: String?): String =
    name.orEmpty().substringAfterLast('/').map { if (it.code < 32 || it == '\\') '_' else it }.joinToString("")
        .trim().takeUnless { it.isEmpty() || it == "." || it == ".." } ?: "file"

/** [name], or the same name with " (2)", " (3)"… before the extension when [taken] has it (as the helper names clashes). */
internal fun uniqueName(name: String, taken: Set<String>): String {
    if (name !in taken) return name
    val (stem, ext) = when {
        Regex("^.+\\.tar\\.[^.]+$").matches(name) -> name.substringBefore(".tar.") to ".tar." + name.substringAfter(".tar.")
        name.lastIndexOf('.') > 0 -> name.substringBeforeLast('.') to "." + name.substringAfterLast('.')
        else -> name to ""
    }
    return generateSequence(2) { it + 1 }.map { "$stem ($it)$ext" }.first { it !in taken }
}

/** Reports how many bytes have been read so far, to show an upload's true progress. */
private class CountingInputStream(input: InputStream, private val onRead: (Long) -> Unit) : FilterInputStream(input) {
    private var total = 0L

    override fun read(): Int = super.read().also { if (it >= 0) count(1) }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count(it.toLong()) }

    private fun count(n: Long) {
        total += n
        onRead(total)
    }
}

package me.ayuilos.miffan.ui.im.computer

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.text.format.Formatter
import android.webkit.MimeTypeMap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.computer.PartnerSpaces
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.im.ImCardDivider
import me.ayuilos.miffan.ui.im.ImCardGroup
import me.ayuilos.miffan.ui.im.ImEmpty
import me.ayuilos.miffan.ui.im.ImListRow
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
import me.rerere.hugeicons.stroke.File01
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Share08
import me.rerere.workspace.WorkspaceFileEntry
import me.rerere.workspace.WorkspaceStorageArea
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * The Shared folder on a partner's own computer, from the phone: what the partner left there to open,
 * save or share, and files to give it. What the owner adds here the partner hears about on its next turn,
 * like files dropped in the handoff folder on the host.
 */
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
    val failed = stringResource(R.string.im_space_files_failed)
    val tooLarge = stringResource(R.string.im_space_files_too_large)
    LaunchedEffect(vm) {
        vm?.messages?.collect { message ->
            snackbar.showSnackbar(when (message) { SpaceFilesMessage.FAILED -> failed; SpaceFilesMessage.TOO_LARGE -> tooLarge })
        }
    }
    LaunchedEffect(vm) {
        vm?.fetched?.collect { ready ->
            val opened = if (ready.share) context.shareWorkspaceFile(ready.file, ready.mime) else context.openWorkspaceFileExternally(ready.file, ready.mime)
            if (opened.isFailure) snackbar.showSnackbar(failed)
        }
    }
    val state = vm?.state?.collectAsStateWithLifecycle()?.value
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
    var deleting by remember { mutableStateOf<WorkspaceFileEntry?>(null) }

    Scaffold(
        topBar = {
            ImPageBar(if (folder.isEmpty()) stringResource(R.string.im_space_files_title, name) else folder.substringAfterLast('/'),
                onBack = if (folder.isNotEmpty()) ({ vm?.open(folder.substringBeforeLast('/', "")) }) else null)
        },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (state != null && state.error == null) ExtendedFloatingActionButton(
                onClick = { if (state.upload == null) pick.launch(arrayOf("*/*")) },
                icon = { Icon(HugeIcons.Add01, null) },
                text = { Text(stringResource(R.string.im_space_files_add)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state?.upload?.let { upload ->
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.im_space_files_uploading, upload.name, upload.index, upload.count),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
                    if (folder.isEmpty()) item("about") {
                        Text(stringResource(R.string.im_space_files_about, name), Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (state.entries.isEmpty()) item("empty") { ImEmpty(stringResource(R.string.im_space_files_empty)) }
                    else item("list") {
                        ImCardGroup {
                            state.entries.forEachIndexed { index, entry ->
                                if (index > 0) ImCardDivider()
                                SpaceFileRow(entry, busy = state.opening == entry.path,
                                    onOpen = { if (entry.isDirectory) vm.open(vm.shared(entry.path)) else vm.view(entry) },
                                    onSave = { saving = entry; save.launch(entry.name) },
                                    onShare = { vm.share(entry) },
                                    onDelete = { deleting = entry })
                            }
                        }
                    }
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

@Composable
private fun SpaceFileRow(
    entry: WorkspaceFileEntry,
    busy: Boolean,
    onOpen: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val time = imChatTime(java.time.Instant.ofEpochMilli(entry.updatedAt))
    ImListRow(
        icon = if (entry.isDirectory) HugeIcons.Folder01 else HugeIcons.File01,
        title = entry.name,
        onClick = onOpen,
        supporting = if (entry.isDirectory) time else "${Formatter.formatShortFileSize(context, entry.sizeBytes)} · $time",
        trailing = {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Box {
                IconButton(onClick = { menu = true }) {
                    Icon(HugeIcons.MoreVertical, stringResource(R.string.im_space_files_more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (!entry.isDirectory) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.im_space_files_save)) }, leadingIcon = { Icon(HugeIcons.Download04, null) },
                            onClick = { menu = false; onSave() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.im_space_files_share)) }, leadingIcon = { Icon(HugeIcons.Share08, null) },
                            onClick = { menu = false; onShare() })
                    }
                    DropdownMenuItem(text = { Text(stringResource(R.string.im_space_files_delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { Icon(HugeIcons.Delete02, null, tint = MaterialTheme.colorScheme.error) },
                        onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}

internal enum class SpaceFilesMessage { FAILED, TOO_LARGE }

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
    data class Upload(val name: String, val index: Int, val count: Int)
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
        _state.value = _state.value.copy(folder = folder, entries = null, error = null)
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
        result.onSuccess { _state.value = _state.value.copy(entries = it, error = null) }
            .onFailure { if (it is CancellationException) throw it; _state.value = _state.value.copy(error = it) }
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
                    _state.value = _state.value.copy(upload = SpaceFilesState.Upload(name, index + 1, uris.size))
                    val entry = withContext(Dispatchers.IO) {
                        requireNotNull(app.contentResolver.openInputStream(uri)) { "Cannot read $uri" }.use { input ->
                            repository.importFile(workspaceId, WorkspaceStorageArea.FILES, absolute(folder), name, input,
                                maxBytes = PartnerSpaces.PHONE_UPLOAD_MAX_BYTES)
                        }
                    }
                    taken += name
                    added += shared(entry.path)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                messages.tryEmit(SpaceFilesMessage.FAILED)
            } finally {
                spaces.noteArrivals(workspaceId, added)
                _state.value = _state.value.copy(upload = null)
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
            }.onFailure { if (it is CancellationException) throw it; messages.tryEmit(SpaceFilesMessage.FAILED) }
        }
    }

    /** A file copied into the app's cache for another app to open or share. */
    data class Fetched(val file: File, val mime: String, val share: Boolean)
    val fetched = kotlinx.coroutines.flow.MutableSharedFlow<Fetched>(extraBufferCapacity = 1)

    fun view(entry: WorkspaceFileEntry) = fetch(entry, share = false)

    fun share(entry: WorkspaceFileEntry) = fetch(entry, share = true)

    private fun fetch(entry: WorkspaceFileEntry, share: Boolean) {
        if (_state.value.opening != null) return
        _state.value = _state.value.copy(opening = entry.path)
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
            _state.value = _state.value.copy(opening = null)
        }
    }

    fun delete(entry: WorkspaceFileEntry) {
        viewModelScope.launch {
            runCatching { repository.deleteFile(workspaceId, WorkspaceStorageArea.FILES, entry.path, recursive = true) }
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

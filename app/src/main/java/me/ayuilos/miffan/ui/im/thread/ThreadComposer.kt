package me.ayuilos.miffan.ui.im.thread

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import dev.chrisbanes.haze.HazeState
import me.ayuilos.miffan.ui.components.ui.GlassIconButton
import me.ayuilos.miffan.ui.components.ui.GlassSurface
import me.rerere.hugeicons.stroke.Mic01
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.ui.components.ai.MediaFileInputRow
import me.ayuilos.miffan.ui.components.ai.useCropLauncher
import me.ayuilos.miffan.ui.components.ui.permission.PermissionCamera
import me.ayuilos.miffan.ui.components.ui.permission.PermissionManager
import me.ayuilos.miffan.ui.components.ui.permission.PermissionRecordAudio
import me.ayuilos.miffan.ui.components.ui.permission.rememberPermissionState
import me.ayuilos.miffan.ui.context.LocalASRState
import me.ayuilos.miffan.ui.context.LocalSettings
import me.ayuilos.miffan.ui.hooks.ChatInputState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Camera01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.Image02
import me.rerere.hugeicons.stroke.StopCircle
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

@Composable
internal fun ThreadComposer(
    input: String,
    onInput: (String) -> Unit,
    topicLabel: String?,
    loaded: Boolean,
    generating: Boolean,
    onSend: (List<UIMessagePart>) -> Unit,
    onStop: () -> Unit,
    onError: (String) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings = LocalSettings.current
    val files: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val attachments = remember { ChatInputState() }
    val asr = LocalASRState.current
    val asrState by asr.state.collectAsStateWithLifecycle()
    val microphone = rememberPermissionState(PermissionRecordAudio)
    val camera = rememberPermissionState(PermissionCamera)
    PermissionManager(permissionState = microphone)
    PermissionManager(permissionState = camera)
    var panel by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var baseText by remember { mutableStateOf("") }
    var ownsRecording by remember { mutableStateOf(false) }
    val latestInput by rememberUpdatedState(onInput)
    val latestError by rememberUpdatedState(onError)
    val addError = stringResource(R.string.im_thread_attachment_error)
    val unavailable = stringResource(R.string.im_thread_voice_unavailable)

    DisposableEffect(asr) {
        onDispose { if (ownsRecording) asr.stop() }
    }
    LaunchedEffect(asrState.errorMessage) {
        asrState.errorMessage?.takeIf(String::isNotBlank)?.let { latestError(it) }
    }
    fun importUris(uris: List<Uri>, images: Boolean, cleanup: () -> Unit = {}) {
        if (uris.isEmpty()) return
        importing = true
        panel = false
        scope.launch {
            try {
                val parts = withContext(Dispatchers.IO) {
                    uris.flatMap { source ->
                        val name = files.getFileNameFromUri(source).orEmpty()
                        val mime = files.getFileMimeType(source) ?: "application/octet-stream"
                        files.createChatFilesByContents(listOf(source)).map { saved ->
                            if (images) UIMessagePart.Image(saved.toString())
                            else UIMessagePart.Document(saved.toString(), name.ifBlank { saved.lastPathSegment.orEmpty() }, mime)
                        }
                    }
                }
                if (parts.size != uris.size) latestError(addError)
                attachments.messageContent = attachments.messageContent + parts
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                latestError(addError)
            } finally { importing = false; cleanup() }
        }
    }
    val (_, crop) = useCropLauncher(
        onCroppedImageReady = {
            // The crop launcher deletes its output immediately after this callback.
            val saved = files.createChatFilesByContents(listOf(it))
            if (saved.isEmpty()) onError(addError) else attachments.addImages(saved)
            panel = false
        },
        onCleanup = { cameraPath?.let { File(it).delete() }; cameraPath = null },
    )
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.size == 1 && !settings.displaySetting.skipCropImage) crop(uris.first()) else importUris(uris, true)
    }
    val documents = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importUris(it, false) }
    fun sendDraft() {
        if (!loaded || importing || asrState.isRecording || (input.isBlank() && attachments.messageContent.isEmpty())) return
        val parts = buildList { if (input.isNotBlank()) add(UIMessagePart.Text(input)); addAll(attachments.messageContent) }
        onSend(parts); onInput(""); attachments.clearInput(); panel = false
    }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        cameraPath?.let { path ->
            val file = File(path)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            if (success && !settings.displaySetting.skipCropImage) crop(uri)
            else {
                if (success) importUris(listOf(uri), true) { file.delete(); cameraPath = null }
                else { file.delete(); cameraPath = null }
            }
        }
    }
    fun toggleVoice() {
        if (asrState.isRecording) { asr.stop(); ownsRecording = false }
        else if (!asrState.isAvailable) onError(unavailable)
        else if (!microphone.allRequiredPermissionsGranted) microphone.requestPermissions()
        else {
            panel = false; keyboard?.hide(); focus.clearFocus()
            baseText = input
            ownsRecording = true
            asr.start { transcript -> latestInput(listOf(baseText, transcript).filter(String::isNotBlank).joinToString("\n")) }
        }
    }
    val cardShape = RoundedCornerShape(28.dp)
    Column(modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (asrState.isRecording) GlassSurface(hazeState, cardShape, Modifier.fillMaxWidth()) { ThreadVoiceRecording(asrState.amplitudes) }
        if (attachments.messageContent.isNotEmpty()) GlassSurface(hazeState, cardShape, Modifier.fillMaxWidth()) {
            Box(Modifier.padding(8.dp)) { MediaFileInputRow(attachments) }
        }
        if (importing) GlassSurface(hazeState, CircleShape) {
            Text(stringResource(R.string.im_thread_loading), Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.labelMedium)
        }
        if (panel) GlassSurface(hazeState, cardShape, Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(vertical = 20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                ThreadAttachmentAction(HugeIcons.Image02, stringResource(R.string.im_thread_photo)) { photos.launch("image/*") }
                ThreadAttachmentAction(HugeIcons.Camera01, stringResource(R.string.im_thread_camera)) {
                    if (camera.allRequiredPermissionsGranted) {
                        val file = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
                        cameraPath = file.absolutePath
                        capture.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
                    } else camera.requestPermissions()
                }
                ThreadAttachmentAction(HugeIcons.Files02, stringResource(R.string.im_thread_file)) { documents.launch(arrayOf("*/*")) }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassIconButton(hazeState, if (panel) HugeIcons.Cancel01 else HugeIcons.Add01, stringResource(R.string.im_thread_attachments),
                onClick = { focus.clearFocus(); keyboard?.hide(); panel = !panel }, enabled = loaded && !importing && !asrState.isRecording)
            GlassSurface(hazeState, RoundedCornerShape(24.dp), Modifier.weight(1f).heightIn(min = 48.dp)) {
                Row(Modifier.padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (asrState.isRecording) {
                        Text(stringResource(R.string.im_thread_voice_stop), Modifier.weight(1f).padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else BasicTextField(value = input, onValueChange = onInput,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp).onFocusChanged { if (it.isFocused) panel = false },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                        decorationBox = { field ->
                            Box {
                                if (input.isEmpty()) Text(if (topicLabel != null) stringResource(R.string.im_thread_in_topic, topicLabel) else stringResource(R.string.im_thread_message),
                                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                field()
                            }
                        })
                    IconButton(onClick = { toggleVoice() }, enabled = loaded && !importing) {
                        Icon(if (asrState.isRecording) HugeIcons.StopCircle else HugeIcons.Mic01,
                            stringResource(if (asrState.isRecording) R.string.im_thread_voice_stop else R.string.im_thread_voice))
                    }
                    // Messages can be sent while replies are still arriving (parallel topics), so a draft
                    // always shows Send; Stop appears only when there is nothing to send.
                    val hasDraft = input.isNotBlank() || attachments.messageContent.isNotEmpty()
                    val stops = generating && !hasDraft
                    if (generating || hasDraft) FilledIconButton(
                        onClick = {
                            if (stops) onStop() else sendDraft()
                        }, enabled = loaded && (stops || (!importing && !asrState.isRecording)),
                    ) { Icon(if (stops) HugeIcons.StopCircle else HugeIcons.ArrowUp02, stringResource(if (stops) R.string.im_thread_stop else R.string.im_thread_send)) }
                }
            }
        }
    }
}

@Composable
private fun ThreadAttachmentAction(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalIconButton(onClick = onClick, modifier = Modifier.size(64.dp)) { Icon(icon, label, Modifier.size(28.dp)) }
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun ThreadVoiceRecording(amplitudes: List<Float>) {
    var seconds by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); seconds++ } }
    val color = MaterialTheme.colorScheme.primary
    val locale = LocalConfiguration.current.locales[0]
    Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(64.dp)) {
            val bars = amplitudes.takeLast(32).ifEmpty { List(32) { 0f } }
            bars.forEachIndexed { index, amplitude ->
                val x = size.width * (index + .5f) / bars.size
                val half = (4.dp.toPx() + amplitude.coerceIn(0f, 1f) * size.height * .4f)
                drawLine(color, Offset(x, size.height / 2 - half), Offset(x, size.height / 2 + half), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text(String.format(locale, "%d:%02d", seconds / 60, seconds % 60), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.im_thread_voice_stop), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

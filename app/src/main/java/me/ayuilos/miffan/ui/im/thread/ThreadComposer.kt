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
import me.ayuilos.miffan.ui.components.ui.glass
import me.ayuilos.miffan.ui.components.ui.GlassShadow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
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
    onVoiceUnavailable: () -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
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
    var menu by rememberSaveable { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    var baseText by remember { mutableStateOf("") }
    var ownsRecording by remember { mutableStateOf(false) }
    val latestInput by rememberUpdatedState(onInput)
    val latestError by rememberUpdatedState(onError)
    val addError = stringResource(R.string.im_thread_attachment_error)

    DisposableEffect(asr) {
        onDispose { if (ownsRecording) asr.stop() }
    }
    LaunchedEffect(asrState.errorMessage) {
        asrState.errorMessage?.takeIf(String::isNotBlank)?.let { latestError(it) }
    }
    fun importUris(uris: List<Uri>, images: Boolean, cleanup: () -> Unit = {}) {
        if (uris.isEmpty()) return
        importing = true
        menu = false
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
            menu = false
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
        onSend(parts); onInput(""); attachments.clearInput(); menu = false
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
        else if (!asrState.isAvailable) onVoiceUnavailable()
        else if (!microphone.allRequiredPermissionsGranted) microphone.requestPermissions()
        else {
            menu = false; keyboard?.hide(); focus.clearFocus()
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
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!compact) Box {
                GlassIconButton(hazeState, if (menu) HugeIcons.Cancel01 else HugeIcons.Add01, stringResource(R.string.im_thread_attachments),
                    onClick = { focus.clearFocus(); keyboard?.hide(); menu = !menu }, enabled = loaded && !importing && !asrState.isRecording)
                ThreadAttachmentMenu(expanded = menu, onDismiss = { menu = false }, hazeState = hazeState) {
                    ThreadAttachmentMenuItem(HugeIcons.Image02, stringResource(R.string.im_thread_photo)) { menu = false; photos.launch("image/*") }
                    ThreadAttachmentMenuItem(HugeIcons.Camera01, stringResource(R.string.im_thread_camera)) {
                        menu = false
                        if (camera.allRequiredPermissionsGranted) {
                            val file = context.cacheDir.resolve("camera_${Uuid.random()}.jpg")
                            cameraPath = file.absolutePath
                            capture.launch(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
                        } else camera.requestPermissions()
                    }
                    ThreadAttachmentMenuItem(HugeIcons.Files02, stringResource(R.string.im_thread_file)) { menu = false; documents.launch(arrayOf("*/*")) }
                }
            }
            GlassSurface(hazeState, RoundedCornerShape(24.dp), Modifier.weight(1f).heightIn(min = 48.dp)) {
                Row(Modifier.padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (asrState.isRecording) {
                        Text(stringResource(R.string.im_thread_voice_stop), Modifier.weight(1f).padding(vertical = 12.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else BasicTextField(value = input, onValueChange = onInput,
                        modifier = Modifier.weight(1f).padding(vertical = 12.dp).onFocusChanged { if (it.isFocused) menu = false },
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), maxLines = if (compact) 2 else 5,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { sendDraft() }),
                        decorationBox = { field ->
                            Box {
                                if (input.isEmpty()) Text(if (topicLabel != null) stringResource(R.string.im_thread_in_topic, topicLabel) else stringResource(R.string.im_thread_message),
                                    style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                field()
                            }
                        })
                    if (!compact) IconButton(onClick = { toggleVoice() }, enabled = loaded && !importing) {
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

/** Frosted menu floating above the plus button; it takes no room in the composer, so the list never jumps. */
@Composable
private fun ThreadAttachmentMenu(expanded: Boolean, onDismiss: () -> Unit, hazeState: HazeState, content: @Composable ColumnScope.() -> Unit) {
    val shown = remember { MutableTransitionState(false) }
    shown.targetState = expanded
    if (!shown.currentState && !shown.targetState) return
    val density = LocalDensity.current
    val gap = with(density) { 8.dp.roundToPx() }
    // The popup window is only as large as its content, so it carries room for the shadow around the menu;
    // that room may hang off the screen edge, hence no clipping.
    val room = with(density) { MENU_SHADOW_ROOM.roundToPx() }
    val position = remember(gap, room) {
        object : PopupPositionProvider {
            override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize) =
                IntOffset(if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left - room else anchorBounds.right + room - popupContentSize.width,
                    anchorBounds.top - gap - popupContentSize.height + room)
        }
    }
    val shape = RoundedCornerShape(24.dp)
    val origin = if (LocalLayoutDirection.current == LayoutDirection.Ltr) TransformOrigin(0f, 1f) else TransformOrigin(1f, 1f)
    Popup(popupPositionProvider = position, onDismissRequest = onDismiss, properties = PopupProperties(focusable = true, clippingEnabled = false)) {
        AnimatedVisibility(shown, enter = fadeIn() + scaleIn(initialScale = .8f, transformOrigin = origin),
            exit = fadeOut() + scaleOut(targetScale = .8f, transformOrigin = origin)) {
            // A tap in the shadow room is a tap outside the menu.
            Box(Modifier.clickable(interactionSource = null, indication = null, onClick = onDismiss).padding(MENU_SHADOW_ROOM)) {
                GlassShadow(shape, Modifier.matchParentSize())
                Column(Modifier.width(IntrinsicSize.Max).widthIn(min = 180.dp).glass(hazeState, shape).padding(vertical = 8.dp),
                    content = content)
            }
        }
    }
}

private val MENU_SHADOW_ROOM = 48.dp

@Composable
private fun ThreadAttachmentMenuItem(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
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

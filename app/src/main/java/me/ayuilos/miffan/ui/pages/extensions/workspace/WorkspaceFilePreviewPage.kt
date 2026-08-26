package me.ayuilos.miffan.ui.pages.extensions.workspace

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.ai.tools.WorkspaceArtifact
import me.ayuilos.miffan.data.ai.tools.workspaceMimeType
import me.ayuilos.miffan.data.repository.WorkspaceRepository
import me.ayuilos.miffan.ui.components.nav.BackButton
import me.ayuilos.miffan.ui.components.richtext.MarkdownBlock
import me.ayuilos.miffan.ui.components.richtext.ZoomableAsyncImage
import me.ayuilos.miffan.ui.components.table.DataTable
import me.ayuilos.miffan.ui.components.webview.WebView
import me.ayuilos.miffan.ui.components.webview.rememberWebViewState
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.theme.CustomColors
import me.rerere.document.DocxParser
import me.rerere.document.EpubParser
import me.rerere.document.PptxParser
import me.rerere.highlight.CodeHighlightText
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FileEdit
import me.rerere.hugeicons.stroke.FileImport
import me.rerere.hugeicons.stroke.Folder01
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.hugeicons.stroke.Share08
import org.koin.compose.koinInject
import java.io.File
import kotlin.math.roundToInt

@Composable
fun WorkspaceFilePreviewPage(
    id: String,
    path: String,
) {
    val repository = koinInject<WorkspaceRepository>()
    val navController = LocalNavController.current
    val context = LocalContext.current
    val toaster = LocalToaster.current
    val scope = rememberCoroutineScope()
    val artifact = remember(id, path) {
        val name = path.substringAfterLast('/').ifBlank { "file" }
        WorkspaceArtifact(
            workspaceId = id,
            path = path,
            name = name,
            mimeType = workspaceMimeType(name),
        )
    }
    val kind = remember(artifact) { detectWorkspacePreviewKind(artifact.name, artifact.mimeType) }
    var preview by remember(id, path) { mutableStateOf<WorkspacePreviewContent?>(null) }
    var loadError by remember(id, path) { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    suspend fun cachedFile(): File = repository.exportArtifactToCache(context, artifact)

    fun reportFailure(error: Throwable) {
        toaster.show(error.message ?: "Unable to open file", type = ToastType.Error)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(artifact.mimeType),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    repository.exportRootfsArtifact(artifact.workspaceId, artifact.path, output)
                } ?: error("Unable to open export destination")
            }.onFailure(::reportFailure)
        }
    }

    LaunchedEffect(id, path, kind) {
        preview = null
        loadError = null
        runCatching {
            withContext(Dispatchers.IO) {
                when (kind) {
                    WorkspacePreviewKind.TEXT,
                    WorkspacePreviewKind.MARKDOWN,
                    WorkspacePreviewKind.JSON,
                    WorkspacePreviewKind.DELIMITED_TEXT,
                    WorkspacePreviewKind.HTML,
                        -> WorkspacePreviewContent.Text(
                            repository.readRootfsTextForPreview(id, path)
                        )

                    WorkspacePreviewKind.DOCUMENT_TEXT -> {
                        val file = repository.exportArtifactToCache(context, artifact)
                        val text = when (file.extension.lowercase()) {
                            "docx" -> DocxParser.parse(file)
                            "pptx" -> PptxParser.parse(file)
                            "epub" -> EpubParser.parse(file)
                            else -> error("Unsupported document type")
                        }
                        WorkspacePreviewContent.DocumentText(file, text)
                    }

                    WorkspacePreviewKind.PDF -> {
                        val file = repository.exportArtifactToCache(context, artifact)
                        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                            PdfRenderer(descriptor).use { renderer ->
                                require(renderer.pageCount > 0) { "PDF has no pages" }
                            }
                        }
                        WorkspacePreviewContent.File(file)
                    }

                    WorkspacePreviewKind.IMAGE,
                    WorkspacePreviewKind.EXTERNAL,
                        -> WorkspacePreviewContent.File(repository.exportArtifactToCache(context, artifact))
                }
            }
        }.onSuccess { preview = it }
            .onFailure { loadError = it.message ?: "Unable to preview file" }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = artifact.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { BackButton() },
                actions = {
                    IconButton(
                        onClick = {
                            val location = artifact.location()
                            navController.navigate(
                                Screen.WorkspaceDetail(
                                    id = artifact.workspaceId,
                                    area = location.area.name,
                                    path = artifact.parentDirectory(),
                                    openFiles = true,
                                )
                            )
                        }
                    ) {
                        Icon(HugeIcons.Folder01, contentDescription = "Locate in workspace")
                    }
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(HugeIcons.MoreVertical, contentDescription = "More")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        if (artifact.location().area == me.rerere.workspace.WorkspaceStorageArea.FILES &&
                            kind in EDITABLE_PREVIEW_KINDS
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                leadingIcon = { Icon(HugeIcons.FileEdit, contentDescription = null) },
                                onClick = {
                                    menuExpanded = false
                                    val location = artifact.location()
                                    navController.navigate(
                                        Screen.WorkspaceFileEditor(
                                            id = artifact.workspaceId,
                                            area = location.area.name,
                                            path = location.relativePath,
                                        )
                                    )
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text("Open externally") },
                            leadingIcon = { Icon(HugeIcons.FileImport, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                scope.launch {
                                    runCatching { cachedFile() }
                                        .onSuccess { file ->
                                            context.openWorkspaceFileExternally(file, artifact.mimeType)
                                                .onFailure(::reportFailure)
                                        }
                                        .onFailure(::reportFailure)
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Export") },
                            leadingIcon = { Icon(HugeIcons.FileImport, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                exportLauncher.launch(artifact.name)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Share") },
                            leadingIcon = { Icon(HugeIcons.Share08, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                scope.launch {
                                    runCatching { cachedFile() }
                                        .onSuccess { file ->
                                            context.shareWorkspaceFile(file, artifact.mimeType)
                                                .onFailure(::reportFailure)
                                        }
                                        .onFailure(::reportFailure)
                                }
                            },
                        )
                    }
                },
                colors = CustomColors.topBarColors,
            )
        },
        containerColor = CustomColors.topBarColors.containerColor,
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when {
                loadError != null -> PreviewError(
                    message = loadError.orEmpty(),
                    modifier = Modifier.align(Alignment.Center),
                )
                preview == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                else -> WorkspacePreviewBody(
                    kind = kind,
                    fileName = artifact.name,
                    content = requireNotNull(preview),
                    onOpenExternally = { file ->
                        context.openWorkspaceFileExternally(file, artifact.mimeType)
                            .onFailure(::reportFailure)
                    },
                )
            }
        }
    }
}

@Composable
private fun WorkspacePreviewBody(
    kind: WorkspacePreviewKind,
    fileName: String,
    content: WorkspacePreviewContent,
    onOpenExternally: (File) -> Unit,
) {
    when (kind) {
        WorkspacePreviewKind.TEXT -> SourcePreview(
            code = (content as WorkspacePreviewContent.Text).value,
            language = workspaceCodeLanguage(fileName),
        )
        WorkspacePreviewKind.JSON -> SourcePreview(
            code = prettyJson((content as WorkspacePreviewContent.Text).value),
            language = "json",
        )
        WorkspacePreviewKind.MARKDOWN -> MarkdownPreview((content as WorkspacePreviewContent.Text).value)
        WorkspacePreviewKind.DELIMITED_TEXT -> DelimitedPreview((content as WorkspacePreviewContent.Text).value)
        WorkspacePreviewKind.HTML -> SafeHtmlPreview((content as WorkspacePreviewContent.Text).value)
        WorkspacePreviewKind.IMAGE -> WorkspaceImagePreview((content as WorkspacePreviewContent.File).file)
        WorkspacePreviewKind.PDF -> PdfFilePreview((content as WorkspacePreviewContent.File).file)
        WorkspacePreviewKind.DOCUMENT_TEXT -> {
            val document = content as WorkspacePreviewContent.DocumentText
            DocumentTextPreview(document.text) { onOpenExternally(document.file) }
        }
        WorkspacePreviewKind.EXTERNAL -> ExternalFilePreview(
            file = (content as WorkspacePreviewContent.File).file,
            onOpenExternally = onOpenExternally,
        )
    }
}

@Composable
private fun SourcePreview(code: String, language: String) {
    SelectionContainer {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            CodeHighlightText(
                code = code,
                language = language,
                modifier = Modifier.fillMaxWidth(),
                fontSize = 13.sp,
                lineHeight = 19.sp,
            )
        }
    }
}

@Composable
private fun MarkdownPreview(markdown: String) {
    var sourceMode by remember(markdown) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        PreviewModeBar(
            primary = "Preview",
            secondary = "Source",
            secondarySelected = sourceMode,
            onChange = { sourceMode = it },
        )
        if (sourceMode) {
            Box(modifier = Modifier.weight(1f)) {
                SourcePreview(code = markdown, language = "markdown")
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                MarkdownBlock(content = markdown)
            }
        }
    }
}

@Composable
private fun SafeHtmlPreview(html: String) {
    var sourceMode by remember(html) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxSize()) {
        PreviewModeBar(
            primary = "Preview",
            secondary = "Source",
            secondarySelected = sourceMode,
            onChange = { sourceMode = it },
        )
        if (sourceMode) {
            Box(modifier = Modifier.weight(1f)) {
                SourcePreview(code = html, language = "html")
            }
        } else {
            val state = rememberWebViewState(
                data = html,
                baseUrl = "https://workspace-preview.invalid/",
                mimeType = "text/html",
                settings = {
                    javaScriptEnabled = false
                    allowFileAccess = false
                    allowContentAccess = false
                    domStorageEnabled = false
                },
                allowExternalRequests = false,
                initialJavaScriptEnabled = false,
            )
            WebView(state = state, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun PreviewModeBar(
    primary: String,
    secondary: String,
    secondarySelected: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        TextButton(onClick = { onChange(false) }, enabled = secondarySelected) { Text(primary) }
        TextButton(onClick = { onChange(true) }, enabled = !secondarySelected) { Text(secondary) }
    }
}

@Composable
private fun DelimitedPreview(text: String) {
    val delimiter = if (text.substringBefore('\n').count { it == '\t' } >
        text.substringBefore('\n').count { it == ',' }
    ) '\t' else ','
    val table = remember(text, delimiter) { parseDelimitedText(text, delimiter) }
    val headers: List<@Composable () -> Unit> = table.headers.map { value ->
        @Composable {
            Text(
                text = value,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val rows: List<List<@Composable () -> Unit>> = table.rows.map { row ->
        row.map { value ->
            @Composable {
                Text(
                    text = value,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val columnCount = maxOf(table.headers.size, table.rows.maxOfOrNull { it.size } ?: 0)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (table.truncated) {
            Text(
                text = "Preview truncated",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        DataTable(
            headers = headers,
            rows = rows,
            modifier = Modifier.fillMaxWidth(),
            zebraStriping = true,
            stretchToFillWidth = false,
            columnMaxWidths = List(columnCount) { 280.dp },
        )
    }
}

@Composable
private fun WorkspaceImagePreview(file: File) {
    ZoomableAsyncImage(
        model = file.absolutePath,
        contentDescription = file.name,
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentScale = ContentScale.Fit,
    )
}

@Composable
private fun DocumentTextPreview(text: String, onOpenExternally: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onOpenExternally) { Text("Open original") }
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            MarkdownBlock(content = text)
        }
    }
}

@Composable
private fun ExternalFilePreview(file: File, onOpenExternally: (File) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(file.name, style = MaterialTheme.typography.titleMedium)
        Text(
            text = "This file type is previewed by another app.",
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onOpenExternally(file) }) { Text("Open externally") }
    }
}

@Composable
private fun PdfFilePreview(file: File) {
    val descriptor = remember(file) {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }
    val renderer = remember(descriptor) { PdfRenderer(descriptor) }
    DisposableEffect(renderer, descriptor) {
        onDispose {
            runCatching { renderer.close() }
            runCatching { descriptor.close() }
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(renderer.pageCount) { pageIndex ->
            PdfPage(renderer = renderer, pageIndex = pageIndex)
        }
    }
}

@Composable
private fun PdfPage(renderer: PdfRenderer, pageIndex: Int) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .background(androidx.compose.ui.graphics.Color.White, MaterialTheme.shapes.small),
    ) {
        val density = LocalDensity.current
        val targetWidth = with(density) { maxWidth.roundToPx() }.coerceIn(1, MAX_PDF_RENDER_WIDTH)
        val bitmap by produceState<Bitmap?>(
            initialValue = null,
            renderer,
            pageIndex,
            targetWidth,
        ) {
            value = withContext(Dispatchers.IO) {
                synchronized(renderer) {
                    val page = renderer.openPage(pageIndex)
                    try {
                        val targetHeight = (targetWidth.toFloat() / page.width * page.height)
                            .roundToInt()
                            .coerceIn(1, MAX_PDF_RENDER_HEIGHT)
                        Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { output ->
                            output.eraseColor(Color.WHITE)
                            page.render(output, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    } finally {
                        page.close()
                    }
                }
            }
        }
        DisposableEffect(bitmap) {
            onDispose { bitmap?.takeUnless(Bitmap::isRecycled)?.recycle() }
        }
        if (bitmap == null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.Image(
                    bitmap = requireNotNull(bitmap).asImageBitmap(),
                    contentDescription = "Page ${pageIndex + 1}",
                    modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.FillWidth,
                )
                Text(
                    text = "${pageIndex + 1}",
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PreviewError(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Unable to preview file", style = MaterialTheme.typography.titleMedium)
        Text(message, color = MaterialTheme.colorScheme.error)
    }
}

private sealed interface WorkspacePreviewContent {
    data class Text(val value: String) : WorkspacePreviewContent
    data class File(val file: java.io.File) : WorkspacePreviewContent
    data class DocumentText(val file: java.io.File, val text: String) : WorkspacePreviewContent
}

private val PRETTY_JSON = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    isLenient = true
}

private fun prettyJson(value: String): String = runCatching {
    PRETTY_JSON.encodeToString(JsonElement.serializer(), PRETTY_JSON.parseToJsonElement(value))
}.getOrDefault(value)

private val EDITABLE_PREVIEW_KINDS = setOf(
    WorkspacePreviewKind.TEXT,
    WorkspacePreviewKind.MARKDOWN,
    WorkspacePreviewKind.JSON,
    WorkspacePreviewKind.DELIMITED_TEXT,
    WorkspacePreviewKind.HTML,
)

private const val MAX_PDF_RENDER_WIDTH = 1600
private const val MAX_PDF_RENDER_HEIGHT = 4096

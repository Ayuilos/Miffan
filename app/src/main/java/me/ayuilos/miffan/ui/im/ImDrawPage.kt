package me.ayuilos.miffan.ui.im

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import coil3.compose.AsyncImage
import com.dokar.sonner.ToastType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.data.files.FileUtils
import me.ayuilos.miffan.data.files.FilesManager
import me.ayuilos.miffan.ui.components.ui.ImagePreviewDialog
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.pages.imggen.GeneratedImage
import me.ayuilos.miffan.ui.pages.imggen.ImgGenVM
import me.ayuilos.miffan.utils.ImageUtils
import me.rerere.ai.ui.ImageGenSize
import me.rerere.common.android.appTempFolder
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowUp02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.PencilEdit01
import org.koin.androidx.compose.koinViewModel
import org.koin.compose.koinInject
import java.io.File
import kotlin.uuid.Uuid

private val DRAW_COUNTS = listOf(1, 2, 4)
private val DRAW_SHAPES = listOf(
    ImageGenSize.AUTO to R.string.im_draw_shape_auto,
    ImageGenSize.SQUARE_1024 to R.string.im_draw_shape_square,
    ImageGenSize.PORTRAIT_1536 to R.string.im_draw_shape_portrait,
    ImageGenSize.LANDSCAPE_1536 to R.string.im_draw_shape_landscape,
)
private val DRAW_EXAMPLES = listOf(R.string.im_draw_example_1, R.string.im_draw_example_2, R.string.im_draw_example_3, R.string.im_draw_example_4)

/**
 * Drawing from a sentence, in easy chat's words: the request and its pictures like a short chat, a few
 * plain choices above the input, and every earlier drawing one tap away in the album.
 */
@Composable
fun ImDrawPage(gallery: Boolean, vm: ImgGenVM = koinViewModel()) {
    val nav = LocalNavController.current
    val toaster = LocalToaster.current
    val settings by vm.settingsStore.settingsFlow.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var album by rememberSaveable { mutableStateOf(gallery) }
    LaunchedEffect(error) {
        error?.let { toaster.show(message = it, type = ToastType.Error); vm.clearError() }
    }
    val ready = settings.findModelById(settings.imageGenerationModelId) != null
    Scaffold(topBar = {
        ImPageBar(stringResource(if (album) R.string.im_discover_drawings else R.string.im_discover_image_gen)) {
            FilledTonalButton(onClick = { album = !album }, Modifier.padding(end = 8.dp)) {
                Text(stringResource(if (album) R.string.im_draw_go_draw else R.string.im_discover_album))
            }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            if (!ready && !album) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.im_draw_no_model), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { nav.navigate(Screen.ImAiService) }) { Text(stringResource(R.string.im_draw_pick_model)) }
                }
            }
            if (album) ImDrawAlbum(vm, Modifier.weight(1f)) else {
                ImDrawSession(vm, Modifier.weight(1f))
                ImDrawComposer(vm, ready)
            }
        }
    }
}

/** This visit's request and its pictures, or ideas to start from. */
@Composable
private fun ImDrawSession(vm: ImgGenVM, modifier: Modifier) {
    val images by vm.currentGeneratedImages.collectAsStateWithLifecycle()
    val generating by vm.isGenerating.collectAsStateWithLifecycle()
    val count by vm.numberOfImages.collectAsStateWithLifecycle()
    var preview by remember { mutableStateOf<String?>(null) }
    if (images.isEmpty() && !generating) {
        Column(modifier.fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(24.dp), modifier = Modifier.size(72.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(HugeIcons.PencilEdit01, null, Modifier.size(32.dp)) }
            }
            Text(stringResource(R.string.im_draw_empty_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.im_draw_empty_desc), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            @OptIn(ExperimentalLayoutApi::class)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                DRAW_EXAMPLES.forEach { example ->
                    val text = stringResource(example)
                    SuggestionChip(onClick = { vm.updatePrompt(text) }, label = { Text(text) })
                }
            }
        }
        return
    }
    LazyVerticalGrid(GridCells.Fixed(2), modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        images.firstOrNull()?.prompt?.let { prompt ->
            item(span = { GridItemSpan(maxLineSpan) }, key = "prompt") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(18.dp, 18.dp, 6.dp, 18.dp)) {
                        Text(prompt, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                    }
                }
            }
        }
        if (generating) items(count) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp), modifier = Modifier.aspectRatio(1f)) {
                Box(contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp)) }
            }
        } else items(images, key = { "${it.id}:${it.filePath}" }) { image ->
            ImDrawing(image, onOpen = { preview = image.filePath })
        }
    }
    preview?.let { ImagePreviewDialog(images = listOf(it), onDismissRequest = { preview = null }) }
}

/** One picture with a way to keep it in the phone's gallery. */
@Composable
private fun ImDrawing(image: GeneratedImage, onOpen: () -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AsyncImage(File(image.filePath), image.prompt, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).clickable(onClick = onOpen))
        FilledTonalButton(modifier = Modifier.fillMaxWidth(), onClick = {
            scope.launch {
                try {
                    filesManager.saveMessageImage(context, "file://${image.filePath}")
                    toaster.show(resources.getString(R.string.imggen_page_image_saved_success), type = ToastType.Success)
                } catch (e: Exception) {
                    toaster.show(resources.getString(R.string.imggen_page_save_failed, e.message), type = ToastType.Error)
                }
            }
        }) {
            Icon(HugeIcons.FloppyDisk, null, Modifier.size(16.dp))
            Text(stringResource(R.string.imggen_page_save), Modifier.padding(start = 6.dp))
        }
    }
}

/** How many, what shape, pictures to start from, and the request itself. */
@Composable
private fun ImDrawComposer(vm: ImgGenVM, ready: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prompt by vm.prompt.collectAsStateWithLifecycle()
    val count by vm.numberOfImages.collectAsStateWithLifecycle()
    val size by vm.size.collectAsStateWithLifecycle()
    val references by vm.referenceImages.collectAsStateWithLifecycle()
    val generating by vm.isGenerating.collectAsStateWithLifecycle()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            // Same preparation as the full drawing page: a bounded PNG in the temp folder.
            val paths = uris.mapNotNull { uri ->
                withContext(Dispatchers.IO) {
                    runCatching {
                        val bitmap = ImageUtils.loadOptimizedBitmap(context, uri, maxSize = 2048) ?: error("Failed to decode image")
                        val bytes = FileUtils.compressBitmapToPng(bitmap)
                        bitmap.recycle()
                        File(context.appTempFolder, "imggen_ref_${Uuid.random()}.png").apply { writeBytes(bytes) }.absolutePath
                    }.getOrNull()
                }
            }
            vm.addReferenceImages(paths)
        }
    }
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (references.isNotEmpty()) LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(references, key = { it }) { path ->
                Box {
                    AsyncImage(File(path), null, Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop)
                    Surface(onClick = { vm.removeReferenceImage(path) }, shape = CircleShape, color = MaterialTheme.colorScheme.inverseSurface,
                        contentColor = MaterialTheme.colorScheme.inverseOnSurface, modifier = Modifier.align(Alignment.TopEnd).size(20.dp)) {
                        Icon(HugeIcons.Cancel01, stringResource(R.string.delete), Modifier.padding(3.dp))
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssistChip(onClick = { vm.updateNumberOfImages(DRAW_COUNTS[(DRAW_COUNTS.indexOf(count) + 1) % DRAW_COUNTS.size]) },
                label = { Text(pluralStringResource(R.plurals.im_draw_count, count, count)) })
            val shape = DRAW_SHAPES.indexOfFirst { it.first.value == size }.coerceAtLeast(0)
            AssistChip(onClick = { vm.updateSize(DRAW_SHAPES[(shape + 1) % DRAW_SHAPES.size].first.value) },
                label = { Text(stringResource(DRAW_SHAPES[shape].second)) })
            AssistChip(onClick = { picker.launch("image/*") }, label = { Text(stringResource(R.string.im_draw_reference)) })
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(prompt, vm::updatePrompt, Modifier.weight(1f), maxLines = 4, shape = RoundedCornerShape(24.dp),
                placeholder = { Text(stringResource(R.string.im_draw_hint)) })
            val canSend = ready && prompt.isNotBlank()
            FilledIconButton(
                onClick = { if (generating) vm.cancelGeneration() else if (references.isEmpty()) vm.generateImage() else vm.editImage() },
                enabled = generating || canSend,
                modifier = Modifier.size(52.dp),
                colors = if (generating) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer) else IconButtonDefaults.filledIconButtonColors(),
            ) {
                Icon(if (generating) HugeIcons.Cancel01 else HugeIcons.ArrowUp02,
                    stringResource(if (generating) R.string.im_draw_stop else R.string.imggen_page_generate_image))
            }
        }
    }
}

/** Every drawing so far; tap to look closer, long press to keep or delete it. */
@Composable
private fun ImDrawAlbum(vm: ImgGenVM, modifier: Modifier) {
    val drawings = vm.generatedImages.collectAsLazyPagingItems()
    val context = LocalContext.current
    val resources = LocalResources.current
    val toaster = LocalToaster.current
    val filesManager: FilesManager = koinInject()
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<String?>(null) }
    if (drawings.itemCount == 0) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ImEmpty(stringResource(R.string.imggen_page_no_generated_images)) }
        return
    }
    LazyVerticalGrid(GridCells.Fixed(3), modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(drawings.itemCount, key = drawings.itemKey { it.id }) { index ->
            val image = drawings[index] ?: return@items
            val menu = rememberPressMenu()
            Box {
                AsyncImage(File(image.filePath), image.prompt, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp))
                        .imLongPress(menu::open).clickable { preview = image.filePath })
                ImPressMenu(menu) {
                    ImPressMenuItem(HugeIcons.FloppyDisk, stringResource(R.string.imggen_page_save)) {
                        menu.close()
                        scope.launch {
                            try {
                                filesManager.saveMessageImage(context, "file://${image.filePath}")
                                toaster.show(resources.getString(R.string.imggen_page_image_saved_success), type = ToastType.Success)
                            } catch (e: Exception) {
                                toaster.show(resources.getString(R.string.imggen_page_save_failed, e.message), type = ToastType.Error)
                            }
                        }
                    }
                    ImPressMenuItem(HugeIcons.Delete01, stringResource(R.string.imggen_page_delete), destructive = true) {
                        menu.close(); vm.deleteImage(image)
                    }
                }
            }
        }
    }
    preview?.let { ImagePreviewDialog(images = listOf(it), onDismissRequest = { preview = null }) }
}

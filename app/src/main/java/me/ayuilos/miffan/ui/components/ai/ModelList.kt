package me.ayuilos.miffan.ui.components.ai

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastAny
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.fastForEach
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.DragDropHorizontal
import me.rerere.hugeicons.stroke.Favourite
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Files02
import me.rerere.hugeicons.stroke.MusicNote03
import me.rerere.hugeicons.stroke.Search01
import me.rerere.hugeicons.stroke.Text
import me.rerere.hugeicons.stroke.Tools
import me.rerere.hugeicons.stroke.Video01
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.data.datastore.findProvider
import me.ayuilos.miffan.ui.components.ui.AutoAIIcon
import me.ayuilos.miffan.ui.components.ui.Tag
import me.ayuilos.miffan.ui.components.ui.TagType
import me.ayuilos.miffan.ui.components.ui.icons.HeartIcon
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.ui.theme.extendColors
import me.ayuilos.miffan.utils.toDp
import org.koin.compose.koinInject
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import kotlin.uuid.Uuid

class ModelListState internal constructor(
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    type: ModelType,
    textOnly: Boolean = false,
) {
    var modelId by mutableStateOf(modelId)
        private set

    var providers by mutableStateOf(providers)
        private set

    var type by mutableStateOf(type)
        private set

    /** Leaves out chat models that also draw, for jobs that only want text back (translating, for one). */
    var textOnly by mutableStateOf(textOnly)
        private set

    var visible by mutableStateOf(false)
        private set

    val currentModel: Model?
        get() = modelId?.let { providers.findModelById(it) }

    val filteredProviders: List<ProviderSetting>
        get() = providers.fastFilter { provider ->
            provider.enabled && provider.models.fastAny { model -> model.listedFor(type, textOnly) }
        }

    fun open() {
        visible = true
    }

    fun close() {
        visible = false
    }

    internal fun update(
        modelId: Uuid?,
        providers: List<ProviderSetting>,
        type: ModelType,
        textOnly: Boolean,
    ) {
        this.modelId = modelId
        this.providers = providers
        this.type = type
        this.textOnly = textOnly
    }
}

@Composable
fun rememberModelListState(
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    type: ModelType,
    textOnly: Boolean = false,
): ModelListState {
    return remember {
        ModelListState(
            modelId = modelId,
            providers = providers,
            type = type,
            textOnly = textOnly,
        )
    }.also {
        it.update(
            modelId = modelId,
            providers = providers,
            type = type,
            textOnly = textOnly,
        )
    }
}

/** Whether [this] belongs in a picker of [type] models; [textOnly] also drops chat models that output images. */
internal fun Model.listedFor(type: ModelType, textOnly: Boolean): Boolean =
    this.type == type && !(textOnly && Modality.IMAGE in outputModalities)

@Composable
fun ModelSelector(
    modelId: Uuid?,
    providers: List<ProviderSetting>,
    type: ModelType,
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    onClear: (() -> Unit)? = null,
    defaultModelId: Uuid? = null,
    textOnly: Boolean = false,
    onSelect: (Model) -> Unit
) {
    val state = rememberModelListState(
        modelId = modelId ?: defaultModelId,
        providers = providers,
        type = type,
        textOnly = textOnly,
    )

    ModelSelectorButton(
        state = state,
        modifier = modifier,
        onlyIcon = onlyIcon,
        allowClear = onClear != null,
        onClear = { onClear?.invoke() },
        isDefault = modelId == null && defaultModelId != null,
    )

    ModelListSheet(
        state = state,
        onSelect = onSelect,
    )
}

@Composable
internal fun ModelSelectorButton(
    state: ModelListState,
    modifier: Modifier = Modifier,
    onlyIcon: Boolean = false,
    allowClear: Boolean = false,
    onClear: () -> Unit = {},
    isDefault: Boolean = false,
) {
    val model = state.currentModel

    if (!onlyIcon) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = {
                    state.open()
                },
                modifier = modifier
            ) {
                model?.modelId?.let {
                    AutoAIIcon(
                        it, Modifier
                            .padding(end = 4.dp)
                            .size(36.dp),
                        color = Color.Transparent
                    )
                }
                Text(
                    text = when {
                        model == null -> stringResource(R.string.model_list_select_model)
                        isDefault -> stringResource(R.string.im_p5_model_default, model.displayName)
                        else -> model.displayName
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (allowClear) {
                IconButton(
                    onClick = onClear,
                ) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = "Clear"
                    )
                }
            }
        }
    } else {
        IconButton(
            onClick = {
                state.open()
            },
        ) {
            if (model != null) {
                AutoAIIcon(
                    modifier = Modifier.size(36.dp),
                    name = model.modelId,
                    color = Color.Transparent
                )
            } else {
                Icon(
                    imageVector = HugeIcons.Brain02,
                    contentDescription = stringResource(R.string.setting_model_page_chat_model),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

@Composable
fun ModelListSheet(
    state: ModelListState,
    onSelect: (Model) -> Unit,
) {
    if (!state.visible) return

    val coroutineScope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    fun dismiss() {
        coroutineScope.launch {
            sheetState.hide()
            state.close()
        }
    }

    ModalBottomSheet(
        onDismissRequest = {
            state.close()
        },
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxHeight(0.8f)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            ModelList(
                currentModel = state.modelId,
                providers = state.filteredProviders,
                modelType = state.type,
                textOnly = state.textOnly,
                onSelect = {
                    onSelect(it)
                    dismiss()
                },
                onDismiss = {
                    dismiss()
                }
            )
        }
    }
}

@Composable
private fun ColumnScope.ModelList(
    currentModel: Uuid? = null,
    providers: List<ProviderSetting>,
    modelType: ModelType,
    textOnly: Boolean = false,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    val settingsStore = koinInject<SettingsStore>()
    val settings = settingsStore.settingsFlow
        .collectAsStateWithLifecycle()

    // 拖动排序时先改这份本地顺序，松手再写回设置。设置是异步回到界面的，拖到边缘自动滚动时拖拽库每帧都会按
    // 旧布局再算一次移动，直接改设置会让顺序被反复改回去，看起来就是来回抽搐
    var draggingFavoriteOrder by remember { mutableStateOf<List<Uuid>?>(null) }
    val favoriteModels = (draggingFavoriteOrder ?: settings.value.favoriteModels).mapNotNull { modelId ->
        val model = settings.value.providers.findModelById(modelId) ?: return@mapNotNull null
        if (!model.listedFor(modelType, textOnly)) return@mapNotNull null
        val provider = model.findProvider(providers = settings.value.providers, checkOverwrite = false) ?: return@mapNotNull null
        model to provider
    }

    var searchKeywords by remember { mutableStateOf("") }

    val typeFilteredModelsByProvider = remember(providers, modelType, textOnly) {
        providers.associate { provider ->
            provider.id to provider.models.fastFilter { it.listedFor(modelType, textOnly) }
        }
    }

    val searchFilteredModelsByProvider = remember(providers, modelType, textOnly, searchKeywords) {
        providers.associate { provider ->
            provider.id to provider.models.fastFilter {
                it.listedFor(modelType, textOnly) && it.displayName.contains(searchKeywords, true)
            }
        }
    }

    // 计算当前选中模型的位置
    val selectedModelPosition = remember(currentModel, favoriteModels, providers, typeFilteredModelsByProvider) {
        if (currentModel == null) return@remember 0

        var position = 0

        // 跳过无providers提示
        if (providers.isEmpty()) {
            position += 1
        }

        // 检查是否在收藏列表中
        val favoriteIndex = favoriteModels.indexOfFirst { it.first.id == currentModel }
        if (favoriteIndex >= 0) {
            if (favoriteModels.isNotEmpty()) {
                position += 1 // favorite header
            }
            position += favoriteIndex
            return@remember position
        }

        // 跳过收藏列表
        if (favoriteModels.isNotEmpty()) {
            position += 1 // favorite header
            position += favoriteModels.size
        }

        // 在providers中查找
        for (provider in providers) {
            position += 1 // provider header
            val models = typeFilteredModelsByProvider[provider.id].orEmpty()
            val modelIndex = models.indexOfFirst { it.id == currentModel }
            if (modelIndex >= 0) {
                position += modelIndex
                return@remember position
            }
            position += models.size
        }

        0
    }

    val lazyListState = rememberLazyListState(
        initialFirstVisibleItemIndex = selectedModelPosition
    )
    val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
        // 列表里只显示当前类型的收藏，下标和完整的收藏列表对不上，按 key 找到对应的模型再移动
        val ids = (draggingFavoriteOrder ?: settings.value.favoriteModels).toMutableList()
        val fromPos = ids.indexOfFirst { "favorite:$it" == from.key }
        val toPos = ids.indexOfFirst { "favorite:$it" == to.key }
        if (fromPos >= 0 && toPos >= 0) {
            ids.add(toPos, ids.removeAt(fromPos))
            draggingFavoriteOrder = ids
        }
    }
    val haptic = LocalHapticFeedback.current

    val providerPositions = remember(providers, favoriteModels, searchFilteredModelsByProvider) {
        var currentIndex = 0
        if (providers.isEmpty()) {
            currentIndex = 1 // no providers item
        }
        if (favoriteModels.isNotEmpty()) {
            currentIndex += 1 // favorite header
            currentIndex += favoriteModels.size // favorite models
        }

        providers.map { provider ->
            val position = currentIndex
            currentIndex += 1 // provider header
            currentIndex += searchFilteredModelsByProvider[provider.id].orEmpty().size
            provider.id to position
        }.toMap()
    }

    Surface(
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    ) {
        OutlinedTextField(
            value = searchKeywords,
            onValueChange = { searchKeywords = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    text = stringResource(R.string.model_list_search_placeholder),
                )
            },
            shape = RoundedCornerShape(50),
            colors = TextFieldDefaults.colors(
                unfocusedIndicatorColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            leadingIcon = {
                Icon(HugeIcons.Search01, null)
            },
            maxLines = 1,
        )
    }

    LazyColumn(
        state = lazyListState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(8.dp),
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
    ) {
        if (providers.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.model_list_no_providers),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.extendColors.gray6,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        if (favoriteModels.isNotEmpty()) {
            stickyHeader {
                Text(
                    text = stringResource(R.string.model_list_favorite),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(bottom = 4.dp, top = 8.dp)
                )
            }

            items(
                items = favoriteModels,
                key = { "favorite:" + it.first.id.toString() }
            ) { (model, provider) ->
                ReorderableItem(
                    state = reorderableState,
                    key = "favorite:" + model.id.toString()
                ) { isDragging ->
                    ModelItem(
                        model = model,
                        onSelect = onSelect,
                        modifier = Modifier
                            .scale(if (isDragging) 0.95f else 1f)
                            .animateItem(),
                        providerSetting = provider,
                        select = model.id == currentModel,
                        onDismiss = {
                            onDismiss()
                        },
                        tail = {
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        settingsStore.update { settings ->
                                            settings.copy(
                                                favoriteModels = settings.favoriteModels.filter { it != model.id }
                                            )
                                        }
                                    }
                                }
                            ) {
                                Icon(
                                    HeartIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        },
                        dragHandle = {
                            Icon(
                                imageVector = HugeIcons.DragDropHorizontal,
                                contentDescription = null,
                                modifier = Modifier.longPressDraggableHandle(
                                    onDragStarted = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptic.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                        val order = draggingFavoriteOrder ?: return@longPressDraggableHandle
                                        coroutineScope.launch {
                                            settingsStore.update { it.copy(favoriteModels = order) }
                                            // 写盘时界面已经收到新设置，这时再撤掉本地顺序不会闪回旧顺序。
                                            // 写盘期间可能已经开始下一次拖动，那时的本地顺序要留给下一次松手保存
                                            if (draggingFavoriteOrder === order) draggingFavoriteOrder = null
                                        }
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }

        providers.fastForEach { providerSetting ->
            stickyHeader(key = "header:${providerSetting.id}") {
                Row(
                    modifier = Modifier
                        .padding(horizontal = 8.dp)
                        .padding(bottom = 4.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = providerSetting.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    ProviderBalanceText(
                        providerSetting = providerSetting,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            items(
                items = searchFilteredModelsByProvider[providerSetting.id].orEmpty(),
                key = { it.id }
            ) { model ->
                val favorite = settings.value.favoriteModels.contains(model.id)
                ModelItem(
                    model = model,
                    onSelect = onSelect,
                    modifier = Modifier.animateItem(),
                    providerSetting = providerSetting,
                    select = currentModel == model.id,
                    onDismiss = {
                        onDismiss()
                    },
                    tail = {
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    settingsStore.update { settings ->
                                        if (favorite) {
                                            settings.copy(
                                                favoriteModels = settings.favoriteModels.filter { it != model.id }
                                            )

                                        } else {
                                            settings.copy(
                                                favoriteModels = settings.favoriteModels + model.id
                                            )
                                        }
                                    }
                                }
                            }
                        ) {
                            if (favorite) {
                                Icon(
                                    HeartIcon,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            } else {
                                Icon(
                                    imageVector = HugeIcons.Favourite,
                                    contentDescription = null,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    // 供应商Badge行
    val providerBadgeListState = rememberLazyListState()
    LaunchedEffect(lazyListState) {
        // 当LazyColumn滚动时，LazyRow也跟随滚动
        snapshotFlow { lazyListState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .debounce(100) // 防抖处理
            .collect { index ->
                if (index > 0) {
                    val currentProvider = providerPositions.entries.findLast {
                        index > it.value
                    }
                    val index = providers.indexOfFirst { it.id == currentProvider?.key }
                    if (index >= 0) {
                        providerBadgeListState.animateScrollToItem(index)
                    } else {
                        providerBadgeListState.requestScrollToItem(0)
                    }
                } else {
                    providerBadgeListState.requestScrollToItem(0)
                }
            }
    }
    if (providers.isNotEmpty()) {
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp),
            state = providerBadgeListState
        ) {
            items(providers) { provider ->
                AssistChip(
                    onClick = {
                        val position = providerPositions[provider.id] ?: 0
                        coroutineScope.launch {
                            lazyListState.animateScrollToItem(position)
                        }
                    },
                    label = {
                        Text(provider.name)
                    },
                    leadingIcon = {
                        AutoAIIcon(name = provider.name, modifier = Modifier.size(16.dp))
                    },
                )
            }
        }
    }
}

@Composable
private fun ModelItem(
    model: Model,
    providerSetting: ProviderSetting,
    select: Boolean,
    onSelect: (Model) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tail: @Composable RowScope.() -> Unit = {},
    dragHandle: @Composable (RowScope.() -> Unit)? = null
) {
    val navController = LocalNavController.current
    val interactionSource = remember { MutableInteractionSource() }
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (select) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (select) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp, horizontal = 16.dp)
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .combinedClickable(
                        enabled = true,
                        onLongClick = {
                            onDismiss()
                            navController.navigate(
                                Screen.SettingProviderDetail(
                                    providerSetting.id.toString()
                                )
                            )
                        },
                        onClick = { onSelect(model) },
                        interactionSource = interactionSource,
                        indication = LocalIndication.current
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    AutoAIIcon(
                        name = model.modelId,
                        modifier = Modifier
                            .padding(4.dp)
                            .size(32.dp)
                    )
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = model.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    FlowRow(
                        modifier = Modifier
                            .fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        ModelTypeTag(model = model)

                        ModelModalityTag(model = model)

                        ModelAbilityTag(model = model)
                    }
                }
                tail()
            }
            dragHandle?.let { it() }
        }
    }
}

@Composable
fun ModelTypeTag(model: Model) {
    Tag(
        type = TagType.INFO
    ) {
        Text(
            text = stringResource(
                when (model.type) {
                    ModelType.CHAT -> R.string.setting_provider_page_chat_model
                    ModelType.EMBEDDING -> R.string.setting_provider_page_embedding_model
                    ModelType.IMAGE -> R.string.setting_provider_page_image_model
                }
            )
        )
    }
}

@Composable
fun ModelModalityTag(model: Model) {
    Tag(
        type = TagType.SUCCESS
    ) {
        model.inputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> HugeIcons.Text
                    Modality.IMAGE -> HugeIcons.Image03
                    Modality.AUDIO -> HugeIcons.MusicNote03
                    Modality.VIDEO -> HugeIcons.Video01
                    Modality.FILE -> HugeIcons.Files02
                },
                contentDescription = null,
                modifier = Modifier
                    .size(LocalTextStyle.current.lineHeight.toDp())
                    .padding(1.dp)
            )
        }
        Icon(
            imageVector = HugeIcons.ArrowRight01,
            contentDescription = null,
            modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp())
        )
        model.outputModalities.fastForEach { modality ->
            Icon(
                imageVector = when (modality) {
                    Modality.TEXT -> HugeIcons.Text
                    Modality.IMAGE -> HugeIcons.Image03
                    Modality.AUDIO -> HugeIcons.MusicNote03
                    Modality.VIDEO -> HugeIcons.Video01
                    Modality.FILE -> HugeIcons.Files02
                },
                contentDescription = null,
                modifier = Modifier
                    .size(LocalTextStyle.current.lineHeight.toDp())
                    .padding(1.dp)
            )
        }
    }
}

@Composable
fun ModelAbilityTag(model: Model) {
    model.abilities.fastForEach { ability ->
        when (ability) {
            ModelAbility.TOOL -> {
                Tag(
                    type = TagType.WARNING
                ) {
                    Icon(
                        imageVector = HugeIcons.Tools,
                        contentDescription = null,
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp())
                    )
                }
            }

            ModelAbility.REASONING -> {
                Tag(
                    type = TagType.INFO
                ) {
                    Icon(
                        painter = painterResource(R.drawable.deepthink),
                        contentDescription = null,
                        modifier = Modifier.size(LocalTextStyle.current.lineHeight.toDp()),
                    )
                }
            }
        }
    }
}

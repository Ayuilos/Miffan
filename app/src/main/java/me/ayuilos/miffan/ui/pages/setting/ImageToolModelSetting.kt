package me.ayuilos.miffan.ui.pages.setting

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterAuthService
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterAuthState
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterImageModel
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterImageModelService
import me.ayuilos.miffan.data.ai.openrouter.openRouterApiKeyOrNull
import me.ayuilos.miffan.data.ai.openrouter.withOpenRouterImageModel
import me.ayuilos.miffan.data.ai.tools.canOutputImage
import me.ayuilos.miffan.data.ai.tools.imageToolModelOrNull
import me.ayuilos.miffan.data.ai.tools.subscriptionImageOptions
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.data.datastore.OPENROUTER_PROVIDER_ID
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.ui.components.ui.AutoAIIcon
import me.ayuilos.miffan.ui.components.ui.CardGroup
import me.ayuilos.miffan.ui.components.ui.Tag
import me.ayuilos.miffan.ui.components.ui.TagType
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Image03
import me.rerere.hugeicons.stroke.Tick02
import org.koin.compose.koinInject
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.uuid.Uuid

/** Settings row for the model behind the agents' `generate_image` tool. */
@Composable
internal fun ImageToolModelSettingItem(
    settings: Settings,
    onUpdate: (Settings) -> Unit,
) {
    var sheetVisible by remember { mutableStateOf(false) }
    val current = settings.imageToolModelOrNull()

    Column {
        CardGroup(title = { Text("画图") }) {
            item(
                onClick = { sheetVisible = true },
                leadingContent = { Icon(HugeIcons.Image03, null) },
                headlineContent = { Text("画图模型") },
                trailingContent = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = current?.displayName ?: "未开启",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (current != null) {
                            IconButton(
                                onClick = { onUpdate(settings.copy(imageToolModelId = null)) },
                                modifier = Modifier.size(20.dp),
                            ) {
                                Icon(HugeIcons.Cancel01, contentDescription = "关闭画图", modifier = Modifier.size(14.dp))
                            }
                        } else {
                            Icon(HugeIcons.ArrowRight01, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    }
                },
            )
        }
        Text(
            text = "选好后，所有助手和伙伴都能在聊天里帮你画图、改图，费用按所选模型计算。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
        )
    }

    if (sheetVisible) {
        ImageToolModelSheet(
            settings = settings,
            onUpdate = onUpdate,
            onDismiss = { sheetVisible = false },
        )
    }
}

internal sealed interface CatalogState {
    data object Loading : CatalogState
    data class Ready(val models: List<OpenRouterImageModel>) : CatalogState
    data class Failed(val message: String) : CatalogState
}

@Composable
internal fun ImageToolModelSheet(
    settings: Settings,
    onUpdate: (Settings) -> Unit,
    onDismiss: () -> Unit,
    easy: Boolean = false,
) {
    val nav = LocalNavController.current
    val scope = rememberCoroutineScope()
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    val catalogService = koinInject<OpenRouterImageModelService>()
    val authService = koinInject<OpenRouterAuthService>()
    val authState by authService.state.collectAsStateWithLifecycle()
    val apiKey = settings.openRouterApiKeyOrNull()
    var query by remember { mutableStateOf("") }
    var reload by remember { mutableIntStateOf(0) }
    // An OpenRouter model picked before the account was connected; applied once the key arrives.
    var pendingPick by remember { mutableStateOf<OpenRouterImageModel?>(null) }

    fun close() {
        scope.launch {
            sheetState.hide()
            onDismiss()
        }
    }

    fun pick(model: OpenRouterImageModel) {
        val result = settings.withOpenRouterImageModel(model)
        if (result == null) {
            pendingPick = model
            authService.startAuthorization()
        } else {
            onUpdate(result.first)
            close()
        }
    }

    LaunchedEffect(apiKey, pendingPick) {
        val pending = pendingPick ?: return@LaunchedEffect
        if (apiKey == null) return@LaunchedEffect
        settings.withOpenRouterImageModel(pending)?.let {
            pendingPick = null
            onUpdate(it.first)
            close()
        }
    }

    val catalog by produceState<CatalogState>(CatalogState.Loading, apiKey, reload) {
        value = CatalogState.Loading
        value = catalogService.list(apiKey, forceRefresh = reload > 0).fold(
            onSuccess = { CatalogState.Ready(it) },
            onFailure = { CatalogState.Failed(it.message ?: "加载失败") },
        )
    }

    val selectedId = settings.imageToolModelOrNull()?.id
    val subscription = settings.subscriptionImageOptions()
    val subscriptionModelIds = subscription.map { it.model.id }.toSet()
    val added: List<Pair<ProviderSetting, Model>> = settings.providers.flatMap { provider ->
        provider.models.filter { it.canOutputImage() || it.id in subscriptionModelIds }.map { provider to it }
    }
    val addedOpenRouterIds = added
        .filter { (provider, _) -> provider.id == OPENROUTER_PROVIDER_ID }
        .map { (_, model) -> model.modelId }
        .toSet()
    val q = query.trim()
    fun matches(vararg texts: String) = q.isEmpty() || texts.any { it.contains(q, ignoreCase = true) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        if (easy) EasyImageModelContent(
            settings = settings,
            subscription = subscription,
            others = added.filter { (provider, model) ->
                provider.id != OPENROUTER_PROVIDER_ID && model.id !in subscriptionModelIds
            },
            catalog = (catalog as? CatalogState.Ready)?.models,
            catalogFailed = catalog is CatalogState.Failed,
            openRouterConnected = apiKey != null,
            connecting = authState == OpenRouterAuthState.Authorizing,
            connectError = (authState as? OpenRouterAuthState.Error)?.message,
            pendingPickId = pendingPick?.id,
            selectedId = selectedId,
            onPickModel = { model ->
                onUpdate(settings.copy(imageToolModelId = model.id))
                close()
            },
            onPickCatalog = ::pick,
            onConnectOpenRouter = { authService.startAuthorization() },
            onRetry = { reload++ },
            onOpenProviders = {
                close()
                nav.navigate(Screen.SettingProvider)
            },
            onTurnOff = if (selectedId != null) {
                {
                    onUpdate(settings.copy(imageToolModelId = null))
                    close()
                }
            } else null,
        ) else Column(
            modifier = Modifier
                .fillMaxHeight(0.85f)
                .padding(horizontal = 16.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("选择画图模型", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索模型") },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth(),
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val addedShown = added.filter { (provider, model) ->
                    matches(model.displayName, model.modelId, provider.name)
                }
                if (addedShown.isNotEmpty()) {
                    item { SectionLabel("已添加") }
                    items(addedShown, key = { "added-${it.second.id}" }) { (provider, model) ->
                        ImageModelRow(
                            iconName = model.modelId,
                            title = model.displayName,
                            subtitle = provider.name,
                            canEdit = Modality.IMAGE in model.inputModalities,
                            price = null,
                            selected = model.id == selectedId,
                            onClick = {
                                onUpdate(settings.copy(imageToolModelId = model.id))
                                close()
                            },
                        )
                    }
                }

                item { SectionLabel("OpenRouter") }
                if (apiKey == null) {
                    item {
                        ConnectOpenRouterCard(
                            authorizing = authState == OpenRouterAuthState.Authorizing,
                            error = (authState as? OpenRouterAuthState.Error)?.message,
                            onConnect = { authService.startAuthorization() },
                        )
                    }
                }
                when (val state = catalog) {
                    CatalogState.Loading -> item {
                        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }

                    is CatalogState.Failed -> item {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        ) {
                            Text(
                                text = "模型列表没加载出来",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { reload++ }) { Text("重试") }
                        }
                    }

                    is CatalogState.Ready -> {
                        val shown = state.models.filter {
                            it.id !in addedOpenRouterIds && matches(it.name, it.id)
                        }
                        if (shown.isEmpty()) {
                            item {
                                Text(
                                    text = if (q.isEmpty()) "暂时没有可用的画图模型" else "没有找到“$q”",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                )
                            }
                        }
                        items(shown, key = { "or-${it.id}" }) { model ->
                            ImageModelRow(
                                iconName = model.id,
                                title = model.name,
                                subtitle = model.id,
                                canEdit = Modality.IMAGE in model.inputModalities,
                                price = model.priceLabel(),
                                selected = false,
                                loading = pendingPick?.id == model.id,
                                onClick = { pick(model) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, start = 4.dp),
    )
}

@Composable
private fun ConnectOpenRouterCard(
    authorizing: Boolean,
    error: String?,
    onConnect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("连接 OpenRouter 后，就能直接用下面的模型画图", style = MaterialTheme.typography.bodyMedium)
            if (error != null) {
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Button(onClick = onConnect, enabled = !authorizing) {
                Text(if (authorizing) "正在连接…" else "连接 OpenRouter")
            }
        }
    }
}

@Composable
internal fun ImageModelRow(
    iconName: String,
    title: String,
    subtitle: String,
    canEdit: Boolean,
    price: String?,
    selected: Boolean,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AutoAIIcon(name = iconName, modifier = Modifier.size(32.dp), loading = loading)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (canEdit || price != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (canEdit) Tag(type = TagType.INFO) { Text("可改图") }
                        if (price != null) Tag { Text(price) }
                    }
                }
            }
            if (selected) {
                Icon(HugeIcons.Tick02, contentDescription = "当前使用", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/**
 * Free only when OpenRouter lists prices and every one is zero: image-only models keep prompt and completion at 0
 * and bill through image_output / image_token, so those two alone say nothing.
 */
internal fun OpenRouterImageModel.isFree(): Boolean {
    val prices = pricing.values.map { it.toBigDecimalOrNull() }
    return prices.isNotEmpty() && prices.all { it != null && it.signum() == 0 }
}

/** "免费", "价格不固定" for routers (negative prices), else the image output price, e.g. "$30/M 图像输出". */
internal fun OpenRouterImageModel.priceLabel(): String? {
    if (isFree()) return "免费"
    if (pricing.values.any { (it.toBigDecimalOrNull()?.signum() ?: 0) < 0 }) return "价格不固定"
    val (key, perToken) = listOf("image_output", "image_token", "completion").firstNotNullOfOrNull { key ->
        pricing[key]?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { key to it }
    } ?: return "按用量计费"
    val perMillion = perToken.multiply(BigDecimal(1_000_000)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros()
    return "$${perMillion.toPlainString()}/M ${if (key == "completion") "输出" else "图像输出"}"
}

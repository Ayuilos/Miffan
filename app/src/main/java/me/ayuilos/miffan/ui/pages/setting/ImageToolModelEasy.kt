package me.ayuilos.miffan.ui.pages.setting

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterImageModel
import me.ayuilos.miffan.data.ai.tools.SubscriptionImageOption
import me.ayuilos.miffan.data.ai.tools.imageToolModelOrNull
import me.ayuilos.miffan.data.datastore.OPENROUTER_PROVIDER_ID
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.findProvider
import me.ayuilos.miffan.ui.components.ui.AutoAIIcon
import me.rerere.ai.provider.Modality
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Tick02
import kotlin.uuid.Uuid

private const val PAID_PREVIEW_COUNT = 3

private sealed interface Recommendation {
    data class Subscription(val option: SubscriptionImageOption) : Recommendation
    data class Catalog(val model: OpenRouterImageModel) : Recommendation
}

private fun OpenRouterImageModel.isFree(): Boolean = priceLabel() == "免费"

private fun OpenRouterImageModel.editLabel(): String =
    if (Modality.IMAGE in inputModalities) "可改图" else "只画新图"

/** Gemini's image models are the cheap, capable default on OpenRouter; otherwise the newest one. */
private fun List<OpenRouterImageModel>.recommended(): OpenRouterImageModel? =
    firstOrNull { "gemini" in it.id && "image" in it.id && !it.isFree() } ?: firstOrNull { !it.isFree() } ?: firstOrNull()

/**
 * Easy chat's picker: grouped by what it costs the user, one recommendation on top, jargon behind "details".
 * With nothing that can draw connected, it offers the services that can instead.
 */
@Composable
internal fun EasyImageModelContent(
    settings: Settings,
    subscription: List<SubscriptionImageOption>,
    others: List<Pair<ProviderSetting, Model>>,
    catalog: List<OpenRouterImageModel>?,
    catalogFailed: Boolean,
    openRouterConnected: Boolean,
    connecting: Boolean,
    connectError: String?,
    pendingPickId: String?,
    selectedId: Uuid?,
    onPickModel: (Model) -> Unit,
    onPickCatalog: (OpenRouterImageModel) -> Unit,
    onConnectOpenRouter: () -> Unit,
    onRetry: () -> Unit,
    onOpenProviders: () -> Unit,
    onTurnOff: (() -> Unit)?,
) {
    if (subscription.isEmpty() && others.isEmpty() && !openRouterConnected) {
        NoDrawingService(connecting, connectError, onConnectOpenRouter, onOpenProviders)
        return
    }

    var details by remember { mutableStateOf(false) }
    var showAllPaid by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    val selectedModel = settings.imageToolModelOrNull()
    val selectedOpenRouterId = selectedModel
        ?.takeIf { it.findProvider(settings.providers)?.id == OPENROUTER_PROVIDER_ID }
        ?.modelId
    val recommendation: Recommendation? = subscription.firstOrNull()?.let { Recommendation.Subscription(it) }
        ?: catalog?.recommended()?.let { Recommendation.Catalog(it) }
    val recommendedCatalogId = (recommendation as? Recommendation.Catalog)?.model?.id
    val restSubscription = subscription.drop(if (recommendation is Recommendation.Subscription) 1 else 0)
    val free = catalog.orEmpty().filter { it.isFree() && it.id != recommendedCatalogId }
    val paid = catalog.orEmpty().filter { !it.isFree() && it.id != recommendedCatalogId }
    val q = query.trim()
    val paidShown = if (showAllPaid) {
        paid.filter { q.isEmpty() || it.name.contains(q, true) || it.id.contains(q, true) }
    } else paid.take(PAID_PREVIEW_COUNT)

    Column(
        modifier = Modifier
            .fillMaxHeight(0.85f)
            .padding(horizontal = 16.dp)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("选一个画图模型", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "选好后所有伙伴都能画图，以后可以在「我 → AI 服务」里换",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (recommendation) {
                is Recommendation.Subscription -> item("rec") {
                    RecommendationCard(
                        iconName = "openai",
                        title = "GPT Image",
                        line = "用你的 ChatGPT 订阅 · 不额外花钱 · 可改图",
                        detail = recommendation.option.model.modelId.takeIf { details },
                        inUse = recommendation.option.model.id == selectedId,
                        loading = false,
                        onUse = { onPickModel(recommendation.option.model) },
                    )
                }

                is Recommendation.Catalog -> item("rec") {
                    RecommendationCard(
                        iconName = recommendation.model.id,
                        title = recommendation.model.name,
                        line = "OpenRouter · 按用量付费 · ${recommendation.model.editLabel()}",
                        detail = listOfNotNull(recommendation.model.id, recommendation.model.priceLabel())
                            .joinToString(" · ").takeIf { details },
                        inUse = recommendation.model.id == selectedOpenRouterId,
                        loading = pendingPickId == recommendation.model.id,
                        onUse = { onPickCatalog(recommendation.model) },
                    )
                }

                null -> Unit
            }

            if (restSubscription.isNotEmpty()) {
                item("sub-label") { GroupLabel("订阅内，不额外花钱") }
                items(restSubscription, key = { "sub-${it.model.id}" }) { option ->
                    EasyRow(
                        iconName = "openai",
                        title = "GPT Image",
                        line = option.providerName,
                        detail = option.model.modelId.takeIf { details },
                        selected = option.model.id == selectedId,
                        onClick = { onPickModel(option.model) },
                    )
                }
            }

            if (free.isNotEmpty()) {
                item("free-label") { GroupLabel("免费") }
                items(free, key = { "free-${it.id}" }) { model ->
                    CatalogRow(model, details, model.id == selectedOpenRouterId, pendingPickId == model.id) { onPickCatalog(model) }
                }
            }

            item("paid-label") {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    GroupLabel("按用量付费 · OpenRouter")
                    if (!openRouterConnected) {
                        Text(
                            text = if (connecting) "正在连接 OpenRouter…" else "选择时会先连接 OpenRouter",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                    connectError?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
            when {
                catalog != null -> {
                    if (showAllPaid) item("search") {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("搜索模型") },
                            singleLine = true,
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    items(paidShown, key = { "paid-${it.id}" }) { model ->
                        CatalogRow(model, details, model.id == selectedOpenRouterId, pendingPickId == model.id) { onPickCatalog(model) }
                    }
                    if (!showAllPaid && paid.size > PAID_PREVIEW_COUNT) item("all") {
                        TextButton(onClick = { showAllPaid = true }, modifier = Modifier.fillMaxWidth()) {
                            Text("查看全部 OpenRouter 画图模型（${paid.size}）", modifier = Modifier.weight(1f))
                            Icon(HugeIcons.ArrowRight01, null, Modifier.size(16.dp))
                        }
                    }
                }

                catalogFailed -> item("failed") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = "OpenRouter 的模型没加载出来",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
                        )
                        TextButton(onClick = onRetry) { Text("重试") }
                    }
                }

                else -> item("loading") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                }
            }

            if (others.isNotEmpty()) {
                item("others-label") { GroupLabel("你添加的") }
                items(others, key = { "other-${it.second.id}" }) { (provider, model) ->
                    EasyRow(
                        iconName = model.modelId,
                        title = model.displayName.ifBlank { model.modelId },
                        line = provider.name,
                        detail = model.modelId.takeIf { details },
                        selected = model.id == selectedId,
                        onClick = { onPickModel(model) },
                    )
                }
            }
        }
        Row(
            modifier = Modifier.align(Alignment.CenterHorizontally),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = { details = !details }) {
                Text(if (details) "隐藏模型细节" else "显示模型 ID 和计价细节")
            }
            onTurnOff?.let { turnOff ->
                TextButton(onClick = turnOff) {
                    Text("关闭画图", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun NoDrawingService(
    connecting: Boolean,
    connectError: String?,
    onConnectOpenRouter: () -> Unit,
    onOpenProviders: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("先连一个能画图的服务", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "你现在用的服务还不能画图。连上下面任意一个，回来就能直接选。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ServiceCard("openai", "用 ChatGPT 订阅登录", "有 Plus / Pro 就能画，不额外花钱", loading = false, onClick = onOpenProviders)
        ServiceCard(
            iconName = "openrouter",
            title = "连接 OpenRouter",
            line = if (connecting) "正在连接…" else "几十种画图模型，按用量付费",
            loading = connecting,
            onClick = onConnectOpenRouter,
        )
        connectError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = onOpenProviders, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("我已有别的服务，手动添加模型")
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
    )
}

@Composable
private fun RecommendationCard(
    iconName: String,
    title: String,
    line: String,
    detail: String?,
    inUse: Boolean,
    loading: Boolean,
    onUse: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AutoAIIcon(name = iconName, modifier = Modifier.size(44.dp), loading = loading)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false))
                        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.primary) {
                            Text("推荐", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
                        }
                    }
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    detail?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .7f))
                    }
                }
            }
            Button(onClick = onUse, enabled = !inUse && !loading, modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp)) {
                Text(if (inUse) "正在使用" else "用这个")
            }
        }
    }
}

@Composable
private fun CatalogRow(model: OpenRouterImageModel, details: Boolean, selected: Boolean, loading: Boolean, onClick: () -> Unit) {
    EasyRow(
        iconName = model.id,
        title = model.name,
        line = model.editLabel(),
        detail = listOfNotNull(model.id, model.priceLabel()).joinToString(" · ").takeIf { details },
        selected = selected,
        loading = loading,
        onClick = onClick,
    )
}

@Composable
private fun EasyRow(
    iconName: String,
    title: String,
    line: String,
    detail: String?,
    selected: Boolean,
    loading: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(12.dp).heightIn(min = 40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AutoAIIcon(name = iconName, modifier = Modifier.size(36.dp), loading = loading)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                detail?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected) Icon(HugeIcons.Tick02, contentDescription = "当前使用", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ServiceCard(iconName: String, title: String, line: String, loading: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = !loading,
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AutoAIIcon(name = iconName, modifier = Modifier.size(44.dp), loading = loading)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(HugeIcons.ArrowRight01, null, Modifier.size(18.dp))
        }
    }
}

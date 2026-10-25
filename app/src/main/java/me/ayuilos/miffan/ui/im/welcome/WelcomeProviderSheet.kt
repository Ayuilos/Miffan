package me.ayuilos.miffan.ui.im.welcome

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.datastore.OPENROUTER_PROVIDER_ID
import me.ayuilos.miffan.data.onboarding.onboardingHint
import me.ayuilos.miffan.data.onboarding.onboardingProviderOrder
import me.ayuilos.miffan.ui.im.ImTag
import me.ayuilos.miffan.ui.components.ui.AutoAIIcon
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.Search01

/** Every service the app knows, then one typed in by hand ([onPick] gets null for that). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WelcomeProviderSheet(providers: List<ProviderSetting>, onDismiss: () -> Unit, onPick: (ProviderSetting?) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val rows = onboardingProviderOrder(providers).map { provider ->
        provider to (onboardingHint(provider.id)?.let { stringResource(it.description) } ?: provider.address())
    }
    val shown = query.trim().lowercase().let { q ->
        if (q.isEmpty()) rows else rows.filter { (provider, line) -> q in provider.name.lowercase() || q in line.lowercase() }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.onboarding_sheet_title), Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(26.dp),
                leadingIcon = { Icon(HugeIcons.Search01, null, Modifier.size(20.dp)) },
                placeholder = { Text(stringResource(R.string.onboarding_sheet_search)) })
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(shown, key = { it.first.id.toString() }) { (provider, line) ->
                    ServiceRow(provider.name, line, oneTap = provider.id == OPENROUTER_PROVIDER_ID, onClick = { onPick(provider) }) {
                        AutoAIIcon(provider.name, Modifier.size(40.dp))
                    }
                }
                item("custom") {
                    ServiceRow(stringResource(R.string.onboarding_custom_name), stringResource(R.string.onboarding_custom_desc), oneTap = false,
                        onClick = { onPick(null) }) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(40.dp)) {
                            Box(contentAlignment = Alignment.Center) { Icon(HugeIcons.Add01, null, Modifier.size(20.dp)) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ServiceRow(name: String, line: String, oneTap: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        icon()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (oneTap) ImTag(stringResource(R.string.onboarding_one_tap))
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun ProviderSetting.address(): String = when (this) {
    is ProviderSetting.OpenAI -> baseUrl
    is ProviderSetting.Google -> baseUrl
    is ProviderSetting.Claude -> baseUrl
}

package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.context.LocalNavController
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ImPageBar(title: String, actions: @Composable RowScope.() -> Unit = {}) {
    val nav = LocalNavController.current
    TopAppBar(title = { Text(title) }, navigationIcon = {
        IconButton(onClick = nav::popBackStack) { Icon(HugeIcons.ArrowLeft01, stringResource(R.string.back)) }
    }, actions = actions)
}

@Composable
internal fun ImEmpty(text: String) {
    Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 64.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ImSettingRow(title: String, onClick: () -> Unit, supporting: String? = null, destructive: Boolean = false) {
    Surface(onClick = onClick, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                supporting?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

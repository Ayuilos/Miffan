package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.data.thread.TimelineItem
import me.ayuilos.miffan.data.thread.previewText
import me.ayuilos.miffan.ui.components.nav.BackButton
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * Placeholder timeline used while the designed IM thread UI is built (P2). It exercises the
 * [AgentThreadVM] contract end to end and is replaced wholesale by the designed page.
 */
@Composable
fun AgentThreadPage(assistantId: Uuid, vm: AgentThreadVM = koinViewModel { parametersOf(assistantId) }) {
    val assistant by vm.assistant.collectAsStateWithLifecycle()
    val timeline by vm.timeline.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(assistant?.name.orEmpty()) }, navigationIcon = { BackButton() })
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f))
                Button(onClick = { vm.sendText(input); input = "" }) { Text("Send") }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = padding,
            reverseLayout = true,
        ) {
            items(timeline.asReversed(), key = { it.key }) { item ->
                val text = when (item) {
                    is TimelineItem.DateSeparator -> "— ${item.at} —"
                    is TimelineItem.Message -> buildString {
                        item.quote?.let { append("↩ ${it.preview}\n") }
                        append("[${item.message.role}] ${item.message.previewText()}")
                    }
                    is TimelineItem.Notice -> "· ${item.notice.summary}"
                    is TimelineItem.Typing -> "…"
                }
                Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
            }
        }
    }
}

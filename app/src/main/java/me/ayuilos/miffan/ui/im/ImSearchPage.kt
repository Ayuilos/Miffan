package me.ayuilos.miffan.ui.im

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.ayuilos.miffan.R
import me.ayuilos.miffan.Screen
import me.ayuilos.miffan.ui.components.ui.AssistantAvatar
import me.ayuilos.miffan.ui.context.LocalNavController
import me.ayuilos.miffan.utils.toLocalString
import org.koin.androidx.compose.koinViewModel
import java.time.ZoneId

@Composable
fun ImSearchPage(vm: ImSearchVM = koinViewModel()) {
    val nav = LocalNavController.current
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val ready = results.query == query.trim()
    val highlight = MaterialTheme.colorScheme.tertiaryContainer
    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_p5_search_hint)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(query, { vm.query.value = it }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                singleLine = true, placeholder = { Text(stringResource(R.string.im_p5_search_hint)) },
                trailingIcon = { if (query.isNotEmpty()) TextButton(onClick = { vm.query.value = "" }) { Text(stringResource(R.string.im_p5_clear)) } })
            LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.weight(1f)) {
                if (query.isNotBlank() && ready) {
                    if (results.partners.isNotEmpty()) {
                        item("partners") { Text(stringResource(R.string.im_tab_partners), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium) }
                        items(results.partners, key = { "partner-${it.id}" }) { partner ->
                            Row(Modifier.fillMaxWidth().clickable { nav.navigate(Screen.PartnerProfile(partner.id.toString())) }.padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AssistantAvatar(partner.name, partner.avatar, Modifier.size(48.dp), onClick = { nav.navigate(Screen.PartnerProfile(partner.id.toString())) })
                                Text(imHighlight(partner.name, query.trim(), highlight), style = MaterialTheme.typography.titleMedium)
                            }
                            HorizontalDivider()
                        }
                    }
                    if (results.messages.isNotEmpty()) {
                        item("messages") { Text(stringResource(R.string.im_p5_search_records, results.messages.size), Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleMedium) }
                        items(results.messages, key = { "message-${it.result.messageId}" }) { hit ->
                            val open = { nav.navigate(Screen.Thread(hit.partner.id.toString(), hit.result.messageId)) }
                            Row(Modifier.fillMaxWidth().clickable(onClick = open).padding(vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AssistantAvatar(hit.partner.name, hit.partner.avatar, Modifier.size(48.dp), onClick = open)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row {
                                        Text(hit.partner.name.ifBlank { stringResource(R.string.assistant_page_default_assistant) }, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(hit.result.updateAt.atZone(ZoneId.systemDefault()).toLocalDate().toLocalString(includeYear = false), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Text(imSnippet(hit.result.snippet, highlight), maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                        }
                    }
                    if (results.failed) item("error") { ImEmpty(stringResource(R.string.im_p5_failed)) }
                    else if (results.messages.isEmpty() && results.partners.isEmpty()) item("empty") { ImEmpty(stringResource(R.string.im_p5_search_empty)) }
                }
            }
        }
    }
}

private fun imHighlight(text: String, query: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    if (query.isNotBlank()) {
        var start = text.indexOf(query, ignoreCase = true)
        while (start >= 0) {
            addStyle(SpanStyle(background = color), start, start + query.length)
            start = text.indexOf(query, start + query.length, ignoreCase = true)
        }
    }
}

/** The FTS API returns its own token boundaries, including segmented Chinese matches. */
private fun imSnippet(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    Regex("\\[(.*?)]").findAll(text).forEach { match ->
        append(text.substring(cursor, match.range.first))
        withStyle(SpanStyle(background = color)) { append(match.groupValues[1]) }
        cursor = match.range.last + 1
    }
    append(text.substring(cursor))
}

package me.ayuilos.miffan.ui.im

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.Role
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowDown01
import java.util.Locale
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dokar.sonner.ToastType
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.datastore.findModelById
import me.ayuilos.miffan.ui.components.ai.ModelListSheet
import me.ayuilos.miffan.ui.components.ai.rememberModelListState
import me.ayuilos.miffan.ui.context.LocalTTSState
import me.ayuilos.miffan.ui.context.LocalToaster
import me.ayuilos.miffan.ui.pages.translator.TranslatorLocales
import me.ayuilos.miffan.ui.pages.translator.TranslatorVM
import me.ayuilos.miffan.ui.pages.translator.getLanguageDisplayName
import me.rerere.ai.provider.ModelType
import org.koin.androidx.compose.koinViewModel

/** Translating a passage: pick the language to read it in, paste, and get the result to copy or hear. */
@Composable
fun ImTranslatePage(vm: TranslatorVM = koinViewModel()) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val input by vm.inputText.collectAsStateWithLifecycle()
    val result by vm.translatedText.collectAsStateWithLifecycle()
    val target by vm.targetLanguage.collectAsStateWithLifecycle()
    val translating by vm.translating.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val toaster = LocalToaster.current
    val tts = LocalTTSState.current
    val canSpeak by tts.isAvailable.collectAsState()
    val speaking by tts.isSpeaking.collectAsState()
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.im_translate_copied)
    LaunchedEffect(Unit) {
        vm.errorFlow.collect { toaster.show(it.message ?: it.javaClass.simpleName, type = ToastType.Error) }
    }
    val model = settings.findModelById(settings.translateModeId)
    val models = rememberModelListState(model?.id, settings.providers, ModelType.CHAT, textOnly = true)
    var choosing by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { ImPageBar(stringResource(R.string.im_discover_translate)) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (model == null) Surface(color = MaterialTheme.colorScheme.tertiaryContainer, contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 14.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.im_translate_no_model), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = models::open) { Text(stringResource(R.string.im_draw_pick_model)) }
                }
            }
            ImCardGroup {
                Column(Modifier.padding(start = 4.dp, end = 12.dp, bottom = 8.dp)) {
                    TextField(input, vm::updateInputText, Modifier.fillMaxWidth().heightIn(min = 140.dp),
                        placeholder = { Text(stringResource(R.string.im_translate_hint)) },
                        textStyle = MaterialTheme.typography.bodyLarge,
                        colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                    Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            scope.launch {
                                clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                                    ?.let(vm::updateInputText)
                            }
                        }) { Text(stringResource(R.string.im_translate_paste)) }
                        if (input.isNotEmpty()) TextButton(onClick = { vm.updateInputText("") }) { Text(stringResource(R.string.im_translate_clear)) }
                    }
                }
            }
            // Which language to read it in comes after what to translate, next to the button that starts it.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(onClick = { choosing = true }, shape = RoundedCornerShape(26.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                    Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.im_translate_into), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(getLanguageDisplayName(target), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Icon(HugeIcons.ArrowDown01, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Button(onClick = { if (translating) vm.cancelTranslation() else vm.translate() }, enabled = translating || (input.isNotBlank() && model != null),
                    modifier = Modifier.heightIn(min = 52.dp)) {
                    Text(stringResource(if (translating) R.string.im_draw_stop else R.string.im_discover_translate))
                }
            }
            if (result.isNotBlank()) Surface(color = MaterialTheme.colorScheme.secondaryContainer, contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(getLanguageDisplayName(target), style = MaterialTheme.typography.labelLarge)
                    SelectionContainer { Text(result, style = MaterialTheme.typography.bodyLarge) }
                    if (!translating) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("", result)))
                                toaster.show(copied, type = ToastType.Success)
                            }
                        }) { Text(stringResource(R.string.im_thread_copy)) }
                        if (canSpeak) FilledTonalButton(onClick = { if (speaking) tts.stop() else tts.speak(result) }) {
                            Text(stringResource(if (speaking) R.string.im_thread_stop_reading else R.string.im_thread_read_aloud))
                        }
                    }
                }
            }
        }
    }
    ModelListSheet(models) { picked -> vm.updateTranslationModel(picked.id) }
    if (choosing) ImLanguageSheet(target, onPick = { vm.updateTargetLanguage(it); choosing = false }, onDismiss = { choosing = false })
}

/** Every language the translator offers, one tap each; the current one is marked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImLanguageSheet(current: Locale, onPick: (Locale) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(stringResource(R.string.im_translate_pick_language), style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp))
        LazyColumn(Modifier.selectableGroup(), contentPadding = PaddingValues(bottom = 24.dp)) {
            items(TranslatorLocales, key = { it.toLanguageTag() }) { locale ->
                val selected = locale == current
                Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).selectable(selected, role = Role.RadioButton, onClick = { onPick(locale) })
                    .padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(getLanguageDisplayName(locale), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    RadioButton(selected, onClick = null)
                }
            }
        }
    }
}

package me.ayuilos.miffan.ui.pages.translator

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.findModelById
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Brain02
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.hugeicons.stroke.Copy01
import me.rerere.hugeicons.stroke.LanguageCircle
import org.koin.androidx.compose.koinViewModel
import kotlin.uuid.Uuid

@Composable
fun ProcessTextTranslatorWindow(
    selectedText: String,
    onClose: () -> Unit,
    vm: TranslatorVM = koinViewModel(),
) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val inputText by vm.inputText.collectAsStateWithLifecycle()
    val translatedText by vm.translatedText.collectAsStateWithLifecycle()
    val targetLanguage by vm.targetLanguage.collectAsStateWithLifecycle()
    val translating by vm.translating.collectAsStateWithLifecycle()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var translationStarted by rememberSaveable(selectedText) { mutableStateOf(false) }

    LaunchedEffect(selectedText, settings.init) {
        vm.initializeInputText(selectedText)
        if (!settings.init && !translationStarted) {
            translationStarted = true
            vm.translate()
        }
    }

    LaunchedEffect(Unit) {
        vm.errorFlow.collect { error ->
            errorMessage = error.message ?: error::class.java.simpleName
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth(),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier
                    .heightIn(max = 640.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = HugeIcons.LanguageCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = stringResource(R.string.process_text_translate_label),
                        modifier = Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.update_card_close),
                        )
                    }
                }

                TranslationModelButton(
                    settings = settings,
                    onModelSelected = { modelId ->
                        errorMessage = null
                        vm.updateTranslationModel(modelId, retranslate = true)
                    },
                )

                Text(
                    text = stringResource(R.string.process_text_selected_text),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = inputText,
                    onValueChange = vm::updateInputText,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 220.dp),
                    minLines = 4,
                    maxLines = 8,
                    textStyle = MaterialTheme.typography.bodyMedium,
                )

                if (translating) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    HorizontalDivider()
                }

                Text(
                    text = stringResource(R.string.translation_text),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .heightIn(min = 88.dp, max = 280.dp),
                ) {
                    when {
                        errorMessage != null -> {
                            Text(
                                text = errorMessage.orEmpty(),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .verticalScroll(rememberScrollState()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }

                        translatedText.isBlank() -> {
                            Text(
                                text = if (translating) {
                                    stringResource(R.string.translating)
                                } else {
                                    stringResource(R.string.translator_page_result_placeholder)
                                },
                                modifier = Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        else -> {
                            SelectionContainer {
                                Text(
                                    text = translatedText,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .verticalScroll(rememberScrollState()),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TargetLanguageButton(
                        targetLanguage = targetLanguage,
                        onLanguageSelected = { language ->
                            errorMessage = null
                            vm.updateTargetLanguage(language)
                            vm.translate()
                        },
                    )

                    Spacer(Modifier.weight(1f))

                    if (translatedText.isNotBlank()) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    clipboard.setClipEntry(
                                        ClipEntry(
                                            ClipData.newPlainText(null, translatedText)
                                        )
                                    )
                                }
                            }
                        ) {
                            Icon(
                                imageVector = HugeIcons.Copy01,
                                contentDescription = stringResource(R.string.copy),
                            )
                        }
                    }

                    FilledTonalButton(
                        onClick = {
                            if (translating) {
                                vm.cancelTranslation()
                            } else {
                                errorMessage = null
                                vm.translate()
                            }
                        },
                    ) {
                        Text(
                            if (translating) {
                                stringResource(R.string.translator_page_cancel)
                            } else {
                                stringResource(R.string.translator_page_translate)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslationModelButton(
    settings: Settings,
    onModelSelected: (Uuid) -> Unit,
) {
    var pickerVisible by remember { mutableStateOf(false) }
    val modelOptions = remember(settings.providers) {
        settings.providers
            .filter(ProviderSetting::enabled)
            .flatMap { provider ->
                provider.models
                    .filter { it.type == ModelType.CHAT }
                    .map { model -> provider to model }
            }
    }
    val currentModel = settings.providers.findModelById(settings.translateModeId)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.setting_model_page_translate_model),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        TextButton(
            onClick = { pickerVisible = true },
            modifier = Modifier.widthIn(max = 280.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Brain02,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = currentModel?.displayName
                    ?: stringResource(R.string.model_list_select_model),
                modifier = Modifier.padding(start = 6.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (pickerVisible) {
        TranslationModelPickerDialog(
            modelOptions = modelOptions,
            selectedModelId = settings.translateModeId,
            onDismiss = { pickerVisible = false },
            onModelSelected = { modelId ->
                pickerVisible = false
                onModelSelected(modelId)
            },
        )
    }
}

@Composable
private fun TranslationModelPickerDialog(
    modelOptions: List<Pair<ProviderSetting, Model>>,
    selectedModelId: Uuid,
    onDismiss: () -> Unit,
    onModelSelected: (Uuid) -> Unit,
) {
    var searchQuery by remember { mutableStateOf("") }
    val filteredOptions = remember(modelOptions, searchQuery) {
        if (searchQuery.isBlank()) {
            modelOptions
        } else {
            modelOptions.filter { (provider, model) ->
                provider.name.contains(searchQuery, ignoreCase = true) ||
                    model.displayName.contains(searchQuery, ignoreCase = true) ||
                    model.modelId.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .heightIn(max = 600.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.setting_model_page_translate_model),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = HugeIcons.Cancel01,
                            contentDescription = stringResource(R.string.update_card_close),
                        )
                    }
                }

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.model_list_search_placeholder)) },
                    singleLine = true,
                )

                if (filteredOptions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.model_list_no_providers),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 420.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(
                            items = filteredOptions,
                            key = { (provider, model) -> "${provider.id}:${model.id}" },
                        ) { (provider, model) ->
                            ListItem(
                                supportingContent = {
                                    Text(
                                        text = "${provider.name} · ${model.modelId}",
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                },
                                leadingContent = {
                                    RadioButton(
                                        selected = model.id == selectedModelId,
                                        onClick = null,
                                    )
                                },
                                modifier = Modifier.clickable {
                                    onModelSelected(model.id)
                                },
                            ) {
                                Text(
                                    text = model.displayName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetLanguageButton(
    targetLanguage: java.util.Locale,
    onLanguageSelected: (java.util.Locale) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        TextButton(onClick = { expanded = true }) {
            Text(getLanguageDisplayName(targetLanguage))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            TranslatorLocales.forEach { language ->
                DropdownMenuItem(
                    text = { Text(getLanguageDisplayName(language)) },
                    onClick = {
                        expanded = false
                        onLanguageSelected(language)
                    },
                )
            }
        }
    }
}

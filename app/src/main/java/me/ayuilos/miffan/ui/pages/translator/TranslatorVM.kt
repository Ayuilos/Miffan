package me.ayuilos.miffan.ui.pages.translator

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ayuilos.miffan.data.ai.TranslationHandler
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import java.util.Locale
import kotlin.uuid.Uuid

private const val TAG = "TranslatorVM"

class TranslatorVM(
    private val settingsStore: SettingsStore,
    private val translationHandler: TranslationHandler,
) : ViewModel() {
    val settings: StateFlow<Settings> = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    // 翻译状态
    private val _translating = MutableStateFlow(false)
    val translating: StateFlow<Boolean> = _translating

    // 输入文本
    private val _inputText = MutableStateFlow("")
    val inputText: StateFlow<String> = _inputText

    // 翻译结果
    private val _translatedText = MutableStateFlow("")
    val translatedText: StateFlow<String> = _translatedText

    // 翻译目标语言
    private val _targetLanguage = MutableStateFlow(Locale.SIMPLIFIED_CHINESE)
    val targetLanguage: StateFlow<Locale> = _targetLanguage

    // 错误流
    val errorFlow = MutableSharedFlow<Throwable>()

    // 当前任务
    private var currentJob: Job? = null

    private var inputInitialized = false

    fun updateSettings(settings: Settings) {
        viewModelScope.launch {
            settingsStore.update(settings)
        }
    }

    fun updateInputText(text: String) {
        _inputText.value = text
    }

    fun initializeInputText(text: String) {
        if (inputInitialized) return

        inputInitialized = true
        _inputText.value = text
    }

    fun updateTargetLanguage(language: Locale) {
        _targetLanguage.value = language
    }

    fun translate() {
        translate(settings.value)
    }

    fun updateTranslationModel(modelId: Uuid, retranslate: Boolean = false) {
        val updatedSettings = settings.value.copy(translateModeId = modelId)
        if (updatedSettings.init) return

        currentJob?.cancel()
        _translating.value = false

        viewModelScope.launch {
            runCatching {
                settingsStore.update(updatedSettings)
            }.onSuccess {
                if (retranslate) {
                    translate(updatedSettings)
                }
            }.onFailure {
                errorFlow.emit(it)
            }
        }
    }

    private fun translate(translationSettings: Settings) {
        val inputText = _inputText.value
        if (inputText.isBlank()) return

        // 取消当前任务
        currentJob?.cancel()

        // 设置翻译中状态
        _translating.value = true
        _translatedText.value = ""

        currentJob = viewModelScope.launch {
            runCatching {
                translationHandler.translateText(
                    settings = translationSettings,
                    sourceText = inputText,
                    targetLanguage = targetLanguage.value
                ) { translatedText ->
                    // Update translation in real-time
                    _translatedText.value = translatedText
                }.collect { /* Final translation already handled in onStreamUpdate */ }
            }.onFailure {
                it.printStackTrace()
                errorFlow.emit(it)
            }

            _translating.value = false
        }
    }

    fun cancelTranslation() {
        currentJob?.cancel()
        _translating.value = false
    }
}

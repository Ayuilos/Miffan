package me.ayuilos.miffan.data.ai.openrouter

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.data.ai.mcp.launchOAuthAuthorization
import me.ayuilos.miffan.data.datastore.OPENROUTER_FREE_MODEL_ID
import me.ayuilos.miffan.data.datastore.OPENROUTER_PROVIDER_ID
import me.ayuilos.miffan.data.datastore.Settings
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ModelAbility
import me.rerere.ai.provider.ModelType
import me.rerere.ai.provider.OpenAIAuthType
import me.rerere.ai.provider.ProviderSetting
import me.rerere.common.http.await
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID

private const val TAG = "OpenRouterAuth"
private const val OPENROUTER_AUTH_URL = "https://openrouter.ai/auth"
private const val OPENROUTER_KEY_EXCHANGE_URL = "https://openrouter.ai/api/v1/auth/keys"
private const val OPENROUTER_KEY_INFO_URL = "https://openrouter.ai/api/v1/key"
private const val OPENROUTER_BASE_URL = "https://openrouter.ai/api/v1"
private const val OPENROUTER_CALLBACK_URL = "https://miffan.ayuilos.me/openrouter-callback.html"
private const val OPENROUTER_FREE_MODEL = "openrouter/free"
private val OPENROUTER_FREE_MODEL_ABILITIES = listOf(
    ModelAbility.TOOL,
    ModelAbility.REASONING,
)
private const val PENDING_PREFERENCES = "openrouter_oauth_pending"
private const val PENDING_STATE = "state"
private const val PENDING_VERIFIER = "verifier"
private const val PENDING_STARTED_AT = "started_at"
private const val AUTHORIZATION_TIMEOUT_MS = 10 * 60 * 1000L
private val JSON_MEDIA_TYPE = "application/json".toMediaType()

sealed interface OpenRouterAuthState {
    data object Idle : OpenRouterAuthState
    data object Authorizing : OpenRouterAuthState
    data object Connected : OpenRouterAuthState
    data class Error(val message: String) : OpenRouterAuthState
}

sealed interface OpenRouterSavedKeyState {
    data object Unchecked : OpenRouterSavedKeyState
    data object Missing : OpenRouterSavedKeyState
    data object Checking : OpenRouterSavedKeyState
    data object Restoring : OpenRouterSavedKeyState
    data class Valid(val expiresAt: Instant?) : OpenRouterSavedKeyState
    data object Invalid : OpenRouterSavedKeyState
    data object CheckFailed : OpenRouterSavedKeyState
}

internal sealed interface OpenRouterKeyValidationResult {
    data class Valid(val expiresAt: Instant?) : OpenRouterKeyValidationResult
    data object Invalid : OpenRouterKeyValidationResult
    data object Unavailable : OpenRouterKeyValidationResult
}

internal data class OpenRouterPkce(
    val verifier: String,
    val challenge: String,
)

/** Owns the OpenRouter PKCE flow and persists the resulting user-controlled API key. */
class OpenRouterAuthService(
    context: Context,
    private val httpClient: OkHttpClient,
    private val settingsStore: SettingsStore,
    private val appScope: AppScope,
) {
    private val applicationContext = context.applicationContext
    private val pending: SharedPreferences = applicationContext.getSharedPreferences(
        PENDING_PREFERENCES,
        Context.MODE_PRIVATE,
    )
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<OpenRouterAuthState>(OpenRouterAuthState.Idle)
    val state: StateFlow<OpenRouterAuthState> = _state.asStateFlow()
    private val _savedKeyState = MutableStateFlow<OpenRouterSavedKeyState>(
        OpenRouterSavedKeyState.Unchecked
    )
    val savedKeyState: StateFlow<OpenRouterSavedKeyState> = _savedKeyState.asStateFlow()
    private var savedKeyJob: Job? = null

    fun checkExistingKey() {
        savedKeyJob?.cancel()
        savedKeyJob = appScope.launch {
            if (_state.value != OpenRouterAuthState.Authorizing) {
                _state.value = OpenRouterAuthState.Idle
            }
            val settings = settingsStore.settingsFlow.first { current -> !current.init }
            val apiKey = settings.openRouterApiKeyOrNull()
            if (apiKey == null) {
                _savedKeyState.value = OpenRouterSavedKeyState.Missing
                return@launch
            }

            _savedKeyState.value = OpenRouterSavedKeyState.Checking
            applySavedKeyValidation(validateOpenRouterKeySafely(apiKey))
        }
    }

    fun restoreFreeModel() {
        savedKeyJob?.cancel()
        savedKeyJob = appScope.launch {
            val settings = settingsStore.settingsFlow.first { current -> !current.init }
            val apiKey = settings.openRouterApiKeyOrNull()
            if (apiKey == null) {
                _savedKeyState.value = OpenRouterSavedKeyState.Missing
                return@launch
            }

            _savedKeyState.value = OpenRouterSavedKeyState.Restoring
            when (val validation = validateOpenRouterKeySafely(apiKey)) {
                is OpenRouterKeyValidationResult.Valid -> {
                    val restored = settingsStore.updateIfCurrent(settings) { current ->
                        current.withOpenRouterConnection(apiKey)
                    }
                    if (restored) {
                        _savedKeyState.value = OpenRouterSavedKeyState.Valid(validation.expiresAt)
                        _state.value = OpenRouterAuthState.Connected
                    } else {
                        checkExistingKey()
                    }
                }
                OpenRouterKeyValidationResult.Invalid -> {
                    _savedKeyState.value = OpenRouterSavedKeyState.Invalid
                }
                OpenRouterKeyValidationResult.Unavailable -> {
                    _savedKeyState.value = OpenRouterSavedKeyState.CheckFailed
                }
            }
        }
    }

    private suspend fun validateOpenRouterKeySafely(apiKey: String): OpenRouterKeyValidationResult =
        runCatching { validateOpenRouterKey(apiKey) }
            .onFailure { cause -> Log.w(TAG, "Unable to validate saved OpenRouter key", cause) }
            .getOrDefault(OpenRouterKeyValidationResult.Unavailable)

    private suspend fun validateOpenRouterKey(apiKey: String): OpenRouterKeyValidationResult =
        withContext(Dispatchers.IO) {
            httpClient.newCall(buildOpenRouterKeyValidationRequest(apiKey)).await().use { response ->
                parseOpenRouterKeyValidation(
                    code = response.code,
                    body = response.body.string(),
                )
            }
        }

    private fun applySavedKeyValidation(result: OpenRouterKeyValidationResult) {
        _savedKeyState.value = when (result) {
            is OpenRouterKeyValidationResult.Valid -> {
                OpenRouterSavedKeyState.Valid(result.expiresAt)
            }
            OpenRouterKeyValidationResult.Invalid -> OpenRouterSavedKeyState.Invalid
            OpenRouterKeyValidationResult.Unavailable -> OpenRouterSavedKeyState.CheckFailed
        }
    }

    fun startAuthorization(languageTag: String? = null) {
        savedKeyJob?.cancel()
        val state = UUID.randomUUID().toString()
        val pkce = generateOpenRouterPkce()
        pending.edit()
            .putString(PENDING_STATE, state)
            .putString(PENDING_VERIFIER, pkce.verifier)
            .putLong(PENDING_STARTED_AT, System.currentTimeMillis())
            .apply()
        _state.value = OpenRouterAuthState.Authorizing
        launchOAuthAuthorization(
            applicationContext,
            buildOpenRouterAuthorizationUrl(state = state, pkce = pkce),
            acceptLanguage = languageTag,
        )
    }

    fun cancelAuthorization() {
        clearPendingAuthorization()
        _state.value = OpenRouterAuthState.Idle
    }

    fun handleCallback(
        state: String?,
        code: String?,
        error: String?,
        errorDescription: String?,
    ) {
        appScope.launch {
            finishAuthorization(state, code, error, errorDescription)
        }
    }

    private suspend fun finishAuthorization(
        returnedState: String?,
        code: String?,
        error: String?,
        errorDescription: String?,
    ) {
        val expectedState = pending.getString(PENDING_STATE, null)
        val verifier = pending.getString(PENDING_VERIFIER, null)
        val startedAt = pending.getLong(PENDING_STARTED_AT, 0L)
        val age = System.currentTimeMillis() - startedAt
        if (expectedState.isNullOrBlank() || verifier.isNullOrBlank()) {
            _state.value = OpenRouterAuthState.Error("No OpenRouter authorization is waiting to finish.")
            return
        }
        if (returnedState != expectedState) {
            _state.value = OpenRouterAuthState.Error("OpenRouter returned an invalid authorization state. Please try again.")
            return
        }
        if (age !in 0..AUTHORIZATION_TIMEOUT_MS) {
            clearPendingAuthorization()
            _state.value = OpenRouterAuthState.Error("OpenRouter authorization expired. Please try again.")
            return
        }
        if (!error.isNullOrBlank()) {
            clearPendingAuthorization()
            val detail = errorDescription?.takeIf(String::isNotBlank) ?: error
            _state.value = OpenRouterAuthState.Error("OpenRouter authorization failed: $detail")
            return
        }
        if (code.isNullOrBlank()) {
            clearPendingAuthorization()
            _state.value = OpenRouterAuthState.Error("OpenRouter did not return an authorization code.")
            return
        }

        clearPendingAuthorization()
        _state.value = OpenRouterAuthState.Authorizing
        runCatching {
            val key = exchangeCodeForKey(code, verifier)
            // The callback activity can recreate the process before DataStore has emitted its
            // first real value. Waiting here prevents a valid key from being applied to the
            // temporary dummy settings and silently discarded.
            settingsStore.settingsFlow.first { settings -> !settings.init }
            settingsStore.update { settings -> settings.withOpenRouterConnection(key) }
        }.onSuccess {
            _state.value = OpenRouterAuthState.Connected
        }.onFailure { cause ->
            Log.e(TAG, "Unable to finish OpenRouter authorization", cause)
            _state.value = OpenRouterAuthState.Error(
                cause.message ?: "Unable to connect OpenRouter. Please try again."
            )
        }
    }

    private suspend fun exchangeCodeForKey(code: String, verifier: String): String =
        withContext(Dispatchers.IO) {
            val requestBody = json.encodeToString(
                buildJsonObject {
                    put("code", code)
                    put("code_verifier", verifier)
                    put("code_challenge_method", "S256")
                }
            )
            val request = Request.Builder()
                .url(OPENROUTER_KEY_EXCHANGE_URL)
                .header("Accept", "application/json")
                .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            httpClient.newCall(request).await().use { response ->
                val body = response.body.string()
                if (!response.isSuccessful) {
                    error(openRouterHttpError(response.code, body))
                }
                json.decodeFromString<OpenRouterKeyResponse>(body).key
                    .takeIf(String::isNotBlank)
                    ?: error("OpenRouter returned an empty API key.")
            }
        }

    private fun clearPendingAuthorization() {
        pending.edit().clear().apply()
    }

    @Serializable
    private data class OpenRouterKeyResponse(val key: String)
}

internal fun generateOpenRouterPkce(random: SecureRandom = SecureRandom()): OpenRouterPkce {
    val verifierBytes = ByteArray(32).also(random::nextBytes)
    val verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes)
    return OpenRouterPkce(
        verifier = verifier,
        challenge = openRouterPkceChallenge(verifier),
    )
}

internal fun openRouterPkceChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}

internal fun buildOpenRouterAuthorizationUrl(
    state: String,
    pkce: OpenRouterPkce,
): String {
    val callback = OPENROUTER_CALLBACK_URL.toHttpUrl().newBuilder()
        .addQueryParameter("state", state)
        .build()
    return OPENROUTER_AUTH_URL.toHttpUrl().newBuilder()
        .addQueryParameter("callback_url", callback.toString())
        .addQueryParameter("code_challenge", pkce.challenge)
        .addQueryParameter("code_challenge_method", "S256")
        .addQueryParameter("key_label", "Miffan Android")
        .build()
        .toString()
}

internal fun buildOpenRouterKeyValidationRequest(apiKey: String): Request {
    require(apiKey.isNotBlank()) { "OpenRouter API key cannot be blank" }
    return Request.Builder()
        .url(OPENROUTER_KEY_INFO_URL)
        .header("Accept", "application/json")
        .header("Authorization", "Bearer $apiKey")
        .get()
        .build()
}

internal fun parseOpenRouterKeyValidation(
    code: Int,
    body: String,
    now: Instant = Instant.now(),
): OpenRouterKeyValidationResult {
    if (code == 401 || code == 403) return OpenRouterKeyValidationResult.Invalid
    if (code !in 200..299) return OpenRouterKeyValidationResult.Unavailable

    val expiresAt = runCatching {
        val data = Json.parseToJsonElement(body).jsonObject["data"]?.jsonObject
            ?: return OpenRouterKeyValidationResult.Unavailable
        val rawExpiresAt = data["expires_at"]?.jsonPrimitive?.contentOrNull
        rawExpiresAt?.let(Instant::parse)
    }.getOrElse {
        return OpenRouterKeyValidationResult.Unavailable
    }
    return if (expiresAt != null && !expiresAt.isAfter(now)) {
        OpenRouterKeyValidationResult.Invalid
    } else {
        OpenRouterKeyValidationResult.Valid(expiresAt)
    }
}

internal fun Settings.openRouterApiKeyOrNull(): String? = openRouterProvider()
    ?.apiKey
    ?.trim()
    ?.takeIf(String::isNotBlank)

private fun Settings.openRouterProvider(): ProviderSetting.OpenAI? {
    val openRouterProviders = providers
        .filterIsInstance<ProviderSetting.OpenAI>()
        .filter { provider ->
            provider.id == OPENROUTER_PROVIDER_ID ||
                runCatching { provider.baseUrl.toHttpUrl().host == "openrouter.ai" }
                    .getOrDefault(false)
        }
    return openRouterProviders.firstOrNull { it.id == OPENROUTER_PROVIDER_ID }
        ?: openRouterProviders.firstOrNull()
}

internal fun Settings.withOpenRouterConnection(apiKey: String): Settings {
    require(apiKey.isNotBlank()) { "OpenRouter API key cannot be blank" }
    val existing = openRouterProvider()
    val existingFreeModel = existing?.models
        ?.firstOrNull { it.modelId == OPENROUTER_FREE_MODEL }
    val freeModel = existingFreeModel?.copy(
        abilities = (existingFreeModel.abilities + OPENROUTER_FREE_MODEL_ABILITIES).distinct(),
    ) ?: Model(
            id = OPENROUTER_FREE_MODEL_ID,
            modelId = OPENROUTER_FREE_MODEL,
            displayName = "OpenRouter Free",
            type = ModelType.CHAT,
            abilities = OPENROUTER_FREE_MODEL_ABILITIES,
        )
    val connectedProvider = (existing ?: ProviderSetting.OpenAI(
        id = OPENROUTER_PROVIDER_ID,
        name = "OpenRouter",
        baseUrl = OPENROUTER_BASE_URL,
    )).copy(
        enabled = true,
        baseUrl = OPENROUTER_BASE_URL,
        apiKey = apiKey,
        authType = OpenAIAuthType.API_KEY,
        codexCredentials = null,
        useResponseApi = false,
        models = (existing?.models.orEmpty().filterNot { it.modelId == OPENROUTER_FREE_MODEL } + freeModel),
    )
    val updatedProviders = if (existing == null) {
        providers + connectedProvider
    } else {
        providers.map { provider -> if (provider.id == existing.id) connectedProvider else provider }
    }
    return copy(
        providers = updatedProviders,
        chatModelId = freeModel.id,
        enableSuggestion = false,
    )
}

internal fun openRouterHttpError(code: Int, body: String): String {
    val detail = runCatching {
        val root = Json.parseToJsonElement(body).jsonObject
        root["error_description"]?.jsonPrimitive?.contentOrNull
            ?: root["message"]?.jsonPrimitive?.contentOrNull
            ?: root["error"]?.let { value ->
                value.jsonPrimitive.contentOrNull
            }
    }.getOrNull()?.takeIf(String::isNotBlank)
    return buildString {
        append("OpenRouter key exchange failed (HTTP $code)")
        if (detail != null) append(": $detail")
    }
}

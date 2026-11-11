package me.ayuilos.miffan.data.appsettings

import me.rerere.asr.ASRProviderSetting
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

/**
 * Read-aloud services that take a single key and send requests to one fixed host, so a secure card can say exactly
 * where the key goes. Qwen (its address holds the owner's workspace id) and Fish Audio (a voice must be picked from the
 * owner's library first) are set up on the speech settings page.
 */
enum class TtsKeyService(
    val id: String,
    val origin: String,
    val keyUrl: String?,
    val create: () -> TTSProviderSetting,
    /** Voices the partner may pick, with a short description where the id alone says little. */
    val voices: List<Pair<String, String?>>,
) {
    OPENAI("openai", "api.openai.com", "https://platform.openai.com/api-keys", { TTSProviderSetting.OpenAI() }, OPENAI_VOICES),
    GEMINI("gemini", "generativelanguage.googleapis.com", "https://aistudio.google.com/apikey", { TTSProviderSetting.Gemini() }, GEMINI_VOICES),
    MINIMAX("minimax", "api.minimaxi.com", "https://platform.minimaxi.com/user-center/basic-information/interface-key",
        { TTSProviderSetting.MiniMax() }, listOf(
            "female-shaonv" to "young woman", "female-yujie" to "mature woman", "female-chengshu" to "calm woman",
            "female-tianmei" to "sweet woman", "male-qn-qingse" to "shy young man", "male-qn-jingying" to "confident man",
            "male-qn-badao" to "bold man", "male-qn-daxuesheng" to "college student", "audiobook_male_1" to "narrator, male",
            "audiobook_female_1" to "narrator, female", "cartoon_pig" to "cartoon",
        )),
    GROQ("groq", "api.groq.com", "https://console.groq.com/keys", { TTSProviderSetting.Groq() },
        listOf("austin" to null, "natalie" to null, "kailin" to null)),
    XAI("xai", "api.x.ai", "https://console.x.ai", { TTSProviderSetting.XAI() },
        listOf("eve" to null, "ara" to null, "rex" to null, "sal" to null, "leo" to null)),
    MIMO("mimo", "api.xiaomimimo.com", "https://platform.xiaomimimo.com", { TTSProviderSetting.MiMo() }, listOf(
        "mimo_default" to null, "冰糖" to null, "茉莉" to null, "苏打" to null, "白桦" to null,
        "Mia" to null, "Chloe" to null, "Milo" to null, "Dean" to null,
    )),
    ELEVENLABS("elevenlabs", "api.elevenlabs.io", "https://elevenlabs.io/app/settings/api-keys", { TTSProviderSetting.ElevenLabs() }, listOf(
        "JBFqnCBsd6RMkjVDRZzb" to "George, warm British man", "21m00Tcm4TlvDq8ikWAM" to "Rachel, calm American woman",
        "EXAVITQu4vr4xnSDxMaL" to "Sarah, soft young woman", "pNInz6obpgDQGcFmaJgB" to "Adam, deep American man",
        "XB0fDUnXU5powFXDhCwa" to "Charlotte, Swedish-accented woman", "Xb7hH8MSUJpSbSDYk0k2" to "Alice, confident British woman",
        "onwK4e9ZLuTAKqWW03F9" to "Daniel, British newsreader",
    )),
    STEP("step", "api.stepfun.com", "https://platform.stepfun.com/interface-key", { TTSProviderSetting.Step() }, listOf(
        "elegantgentle-female" to "gentle woman", "livelybreezy-female" to "lively woman", "jingdiannvsheng" to "classic woman",
        "tianmeinvsheng" to "sweet woman", "qingchunshaonv" to "young girl", "wenrounvsheng" to "soft woman",
        "cixingnansheng" to "magnetic man", "wenrounansheng" to "soft man", "yuanqinansheng" to "energetic man",
        "boyinnansheng" to "announcer, male", "shenchennanyin" to "deep man",
    )),
    OPENROUTER("openrouter", "openrouter.ai", "https://openrouter.ai/keys", { TTSProviderSetting.OpenRouter() }, GEMINI_VOICES);

    companion object {
        fun of(id: String?): TtsKeyService? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        /** The key service of [setting], only while it still sends requests to that service's own host. */
        fun of(setting: TTSProviderSetting): TtsKeyService? {
            val service = when (setting) {
                is TTSProviderSetting.OpenAI -> OPENAI
                is TTSProviderSetting.Gemini -> GEMINI
                is TTSProviderSetting.MiniMax -> MINIMAX
                is TTSProviderSetting.Groq -> GROQ
                is TTSProviderSetting.XAI -> XAI
                is TTSProviderSetting.MiMo -> MIMO
                is TTSProviderSetting.ElevenLabs -> ELEVENLABS
                is TTSProviderSetting.Step -> STEP
                is TTSProviderSetting.OpenRouter -> OPENROUTER
                is TTSProviderSetting.SystemTTS, is TTSProviderSetting.Qwen, is TTSProviderSetting.FishAudio -> null
            }
            return service?.takeIf { voiceOriginOf(setting.address().orEmpty()) == it.origin }
        }
    }
}

private val GEMINI_VOICES = listOf(
    "Kore" to "firm", "Puck" to "upbeat", "Zephyr" to "bright", "Charon" to "informative", "Fenrir" to "excitable",
    "Leda" to "youthful", "Orus" to "firm", "Aoede" to "breezy", "Callirrhoe" to "easy-going", "Enceladus" to "breathy",
    "Achernar" to "soft", "Sulafat" to "warm",
)

private val OPENAI_VOICES = listOf(
    "alloy" to "neutral", "echo" to "male", "fable" to "storyteller", "onyx" to "deep male", "nova" to "bright female",
    "shimmer" to "soft female", "coral" to "warm female", "sage" to "calm",
)

/** Voice input services that take a single key and send audio to one fixed host. */
enum class AsrKeyService(val id: String, val origin: String, val keyUrl: String?, val create: () -> ASRProviderSetting) {
    OPENAI("openai", "api.openai.com", "https://platform.openai.com/api-keys", { ASRProviderSetting.OpenAIRealtime() }),
    DASHSCOPE("dashscope", "dashscope.aliyuncs.com", "https://bailian.console.aliyun.com/?tab=model#/api-key", { ASRProviderSetting.DashScope() }),
    VOLCENGINE("volcengine", "openspeech.bytedance.com", "https://console.volcengine.com/speech/new/setting/apikeys",
        { ASRProviderSetting.Volcengine() }),
    MIMO("mimo", "api.xiaomimimo.com", "https://platform.xiaomimimo.com", { ASRProviderSetting.MiMo() }),
    STEP("step", "api.stepfun.com", "https://platform.stepfun.com/interface-key", { ASRProviderSetting.Step() });

    companion object {
        fun of(id: String?): AsrKeyService? = entries.firstOrNull { it.id == id?.trim()?.lowercase() }

        fun of(setting: ASRProviderSetting): AsrKeyService? {
            val service = when (setting) {
                is ASRProviderSetting.OpenAIRealtime -> OPENAI
                is ASRProviderSetting.DashScope -> DASHSCOPE
                is ASRProviderSetting.Volcengine -> VOLCENGINE
                is ASRProviderSetting.MiMo -> MIMO
                is ASRProviderSetting.Step -> STEP
            }
            return service.takeIf { voiceOriginOf(setting.address()) == it.origin }
        }
    }
}

/** Where a voice service sends requests, shown like [displayOriginOf]; WebSocket addresses read as their https host. */
fun voiceOriginOf(url: String): String? =
    displayOriginOf(url.trim().replaceFirst(Regex("^wss://", RegexOption.IGNORE_CASE), "https://")
        .replaceFirst(Regex("^ws://", RegexOption.IGNORE_CASE), "http://"))

/** The address requests go to; null for the phone's own engine. */
fun TTSProviderSetting.address(): String? = when (this) {
    is TTSProviderSetting.OpenAI -> baseUrl
    is TTSProviderSetting.OpenRouter -> baseUrl
    is TTSProviderSetting.Gemini -> baseUrl
    is TTSProviderSetting.MiniMax -> baseUrl
    is TTSProviderSetting.Qwen -> baseUrl
    is TTSProviderSetting.Groq -> baseUrl
    is TTSProviderSetting.XAI -> baseUrl
    is TTSProviderSetting.MiMo -> baseUrl
    is TTSProviderSetting.ElevenLabs -> baseUrl
    is TTSProviderSetting.Step -> baseUrl
    is TTSProviderSetting.FishAudio -> baseUrl
    is TTSProviderSetting.SystemTTS -> null
}

fun ASRProviderSetting.address(): String = when (this) {
    is ASRProviderSetting.OpenAIRealtime -> websocketUrl
    is ASRProviderSetting.DashScope -> websocketUrl
    is ASRProviderSetting.Volcengine -> websocketUrl
    is ASRProviderSetting.MiMo -> baseUrl
    is ASRProviderSetting.Step -> baseUrl
}

/** The key, or null for the phone's own engine, which has none. */
fun TTSProviderSetting.ttsApiKey(): String? = when (this) {
    is TTSProviderSetting.OpenAI -> apiKey
    is TTSProviderSetting.OpenRouter -> apiKey
    is TTSProviderSetting.Gemini -> apiKey
    is TTSProviderSetting.MiniMax -> apiKey
    is TTSProviderSetting.Qwen -> apiKey
    is TTSProviderSetting.Groq -> apiKey
    is TTSProviderSetting.XAI -> apiKey
    is TTSProviderSetting.MiMo -> apiKey
    is TTSProviderSetting.ElevenLabs -> apiKey
    is TTSProviderSetting.Step -> apiKey
    is TTSProviderSetting.FishAudio -> apiKey
    is TTSProviderSetting.SystemTTS -> null
}

fun ASRProviderSetting.asrApiKey(): String = when (this) {
    is ASRProviderSetting.OpenAIRealtime -> apiKey
    is ASRProviderSetting.DashScope -> apiKey
    is ASRProviderSetting.Volcengine -> apiKey
    is ASRProviderSetting.MiMo -> apiKey
    is ASRProviderSetting.Step -> apiKey
}

/** [this] with its key passed through [transform]: storing it encrypted, or setting a new one. */
fun TTSProviderSetting.transformTtsSecret(transform: (String) -> String): TTSProviderSetting = when (this) {
    is TTSProviderSetting.OpenAI -> copy(apiKey = transform(apiKey))
    is TTSProviderSetting.OpenRouter -> copy(apiKey = transform(apiKey))
    is TTSProviderSetting.Gemini -> copy(apiKey = transform(apiKey))
    is TTSProviderSetting.MiniMax -> copy(apiKey = transform(apiKey))
    is TTSProviderSetting.Qwen -> copy(apiKey = transform(apiKey))
    is TTSProviderSetting.Groq -> copy(apiKey = transform(apiKey))
    is TTSProviderSetting.XAI -> copy(apiKey = transform(apiKey))
    is TTSProviderSetting.MiMo -> copy(apiKey = transform(apiKey))
    is TTSProviderSetting.ElevenLabs -> copy(apiKey = transform(apiKey))
    is TTSProviderSetting.Step -> copy(apiKey = transform(apiKey))
    is TTSProviderSetting.FishAudio -> copy(apiKey = transform(apiKey))
    is TTSProviderSetting.SystemTTS -> this
}

fun ASRProviderSetting.transformAsrSecret(transform: (String) -> String): ASRProviderSetting = when (this) {
    is ASRProviderSetting.OpenAIRealtime -> copy(apiKey = transform(apiKey))
    is ASRProviderSetting.DashScope -> copy(apiKey = transform(apiKey))
    is ASRProviderSetting.Volcengine -> copy(apiKey = transform(apiKey))
    is ASRProviderSetting.MiMo -> copy(apiKey = transform(apiKey))
    is ASRProviderSetting.Step -> copy(apiKey = transform(apiKey))
}

/** The voice it reads with; null when it has no voice to pick (the phone's own engine, Fish Audio). */
fun TTSProviderSetting.voice(): String? = when (this) {
    is TTSProviderSetting.OpenAI -> voice
    is TTSProviderSetting.OpenRouter -> voice
    is TTSProviderSetting.Gemini -> voiceName
    is TTSProviderSetting.MiniMax -> voiceId
    is TTSProviderSetting.Qwen -> voice
    is TTSProviderSetting.Groq -> voice
    is TTSProviderSetting.XAI -> voiceId
    is TTSProviderSetting.MiMo -> voice
    is TTSProviderSetting.ElevenLabs -> voiceId
    is TTSProviderSetting.Step -> voice
    is TTSProviderSetting.FishAudio, is TTSProviderSetting.SystemTTS -> null
}

fun TTSProviderSetting.withVoice(voice: String): TTSProviderSetting = when (this) {
    is TTSProviderSetting.OpenAI -> copy(voice = voice)
    is TTSProviderSetting.OpenRouter -> copy(voice = voice)
    is TTSProviderSetting.Gemini -> copy(voiceName = voice)
    is TTSProviderSetting.MiniMax -> copy(voiceId = voice)
    is TTSProviderSetting.Qwen -> copy(voice = voice)
    is TTSProviderSetting.Groq -> copy(voice = voice)
    is TTSProviderSetting.XAI -> copy(voiceId = voice)
    is TTSProviderSetting.MiMo -> copy(voice = voice)
    is TTSProviderSetting.ElevenLabs -> copy(voiceId = voice)
    is TTSProviderSetting.Step -> copy(voice = voice)
    is TTSProviderSetting.FishAudio, is TTSProviderSetting.SystemTTS -> this
}

/** The type name the catalog and the tools use. */
fun TTSProviderSetting.typeId(): String = when (this) {
    is TTSProviderSetting.SystemTTS -> "system"
    is TTSProviderSetting.Qwen -> "qwen"
    is TTSProviderSetting.FishAudio -> "fish_audio"
    else -> TtsKeyService.entries.first { it.create()::class == this::class }.id
}

fun ASRProviderSetting.typeId(): String = AsrKeyService.entries.first { it.create()::class == this::class }.id

/** The longest read-aloud model id taken from a partner. */
const val MAX_TTS_MODEL_ID = 100

private val OPENROUTER_MODEL_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._:-]*$")

/** Whether [model] reads as an OpenRouter speech model id such as fish-audio/s2-pro. */
fun validTtsModelId(model: String): Boolean = model.length <= MAX_TTS_MODEL_ID && OPENROUTER_MODEL_ID.matches(model)

/** The model a read-aloud service can be switched between; only OpenRouter routes to many. */
fun TTSProviderSetting.ttsModel(): String? = (this as? TTSProviderSetting.OpenRouter)?.model

/**
 * [setting] on [model]. The voice stays when the new model offers it; otherwise it is cleared, since voices belong to
 * the model (a Gemini voice means nothing to Fish Audio) and a blank one lets the model use its own default.
 */
fun TTSProviderSetting.withTtsModel(model: String): TTSProviderSetting {
    if (this !is TTSProviderSetting.OpenRouter || model == this.model) return this
    val moved = copy(model = model)
    return if (voicesFor(moved).any { it.first == voice }) moved else moved.copy(voice = "")
}

/** Whether [voice] is one [setting] offers; null when there is no list to check it against. */
fun voiceListed(setting: TTSProviderSetting, voice: String): Boolean? =
    voicesFor(setting).takeIf { it.isNotEmpty() }?.any { it.first == voice }

/** The voices offered for [setting]'s type; empty when there is nothing to pick. */
fun voicesFor(setting: TTSProviderSetting): List<Pair<String, String?>> = when {
    // OpenRouter's voices belong to the model; the list is the default Gemini model's, so another model gets none.
    setting is TTSProviderSetting.OpenRouter && !setting.model.trim().startsWith("google/gemini") -> emptyList()
    else -> TtsKeyService.entries.firstOrNull { it.create()::class == setting::class }?.voices.orEmpty()
}

/** A name for a service the owner left unnamed, as the speech settings page shows it. */
fun TTSProviderSetting.displayName(): String = name.ifBlank { if (this is TTSProviderSetting.SystemTTS) "System" else typeId() }

fun ASRProviderSetting.displayName(): String = name.ifBlank { typeId() }

/** A new service of [this] type with [key], under [id]. */
fun TtsKeyService.newSetting(id: Uuid, key: String): TTSProviderSetting =
    create().copyProvider(id = id).transformTtsSecret { key }

fun AsrKeyService.newSetting(id: Uuid, key: String): ASRProviderSetting =
    create().copyProvider(id = id).transformAsrSecret { key }

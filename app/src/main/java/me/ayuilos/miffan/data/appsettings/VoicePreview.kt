package me.ayuilos.miffan.data.appsettings

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.rerere.tts.controller.AudioPlayer
import me.rerere.tts.controller.TtsChunk
import me.rerere.tts.controller.TtsSynthesizer
import me.rerere.tts.model.TTSResponse
import me.rerere.tts.provider.TTSManager
import me.rerere.tts.provider.TTSProviderSetting
import kotlin.uuid.Uuid

private const val SAMPLE_TIMEOUT_MS = 30_000L

/** Reads one short sentence with a read-aloud service: to check a key on its card, and to let the owner hear a voice. */
interface VoiceSampler {
    /** The sample read by [setting], or null when the service could not be reached in time; throws what the service said. */
    suspend fun synthesize(setting: TTSProviderSetting): TTSResponse?

    suspend fun play(response: TTSResponse)
}

class VoicePreviewer(private val context: Context, ttsManager: TTSManager) : VoiceSampler {
    private val synthesizer = TtsSynthesizer(ttsManager)

    override suspend fun synthesize(setting: TTSProviderSetting): TTSResponse? = withTimeoutOrNull(SAMPLE_TIMEOUT_MS) {
        val text = context.getString(R.string.im_voice_sample)
        synthesizer.synthesize(setting, TtsChunk(index = 0, text = text))
    }

    override suspend fun play(response: TTSResponse) = withContext(Dispatchers.Main) {
        val player = AudioPlayer(context)
        try {
            player.play(response)
        } finally {
            withContext(NonCancellable) { player.release() }
        }
    }
}

/**
 * Plays the sample with a read-aloud service as it is saved. The key never leaves the settings: the card only names
 * the service.
 */
suspend fun VoiceSampler.previewSaved(settingsStore: SettingsStore, serviceId: Uuid): Boolean {
    val setting = settingsStore.settingsFlow.value.ttsProviders.firstOrNull { it.id == serviceId } ?: return false
    val response = runCatching { synthesize(setting) }.getOrNull() ?: return false
    play(response)
    return true
}

package me.ayuilos.miffan.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import me.ayuilos.miffan.data.datastore.Settings

/** The app shell. Both modes read and write the same assistants, conversations and settings. */
@Serializable
enum class InterfaceMode {
    /** IM-style shell: one continuous timeline per assistant and bottom tabs. */
    @SerialName("im")
    IM,

    /** The original conversation-centric interface. */
    @SerialName("professional")
    PROFESSIONAL,
}

@Serializable
data class InterfaceModeState(
    val mode: InterfaceMode = InterfaceMode.PROFESSIONAL,
    /** An upgraded installation has not yet chosen between the two shells. */
    val choicePending: Boolean = false,
)

/**
 * Called once when the interface-mode preference is first created. Fresh installations start in
 * the IM shell; existing users keep the professional shell until they explicitly choose.
 */
fun initialInterfaceMode(launchCount: Int, hasSavedProviders: Boolean): InterfaceModeState =
    if (launchCount > 0 || hasSavedProviders) {
        InterfaceModeState(mode = InterfaceMode.PROFESSIONAL, choicePending = true)
    } else {
        InterfaceModeState(mode = InterfaceMode.IM)
    }

val Settings.isImMode: Boolean get() = interfaceMode.mode == InterfaceMode.IM

// Easy chat is one continuous timeline: the partner always recalls earlier chats and knows the time.
// The assistant's own switches are kept, so the professional shell still honors them.

fun Assistant.recentChatsReferenceEnabled(settings: Settings): Boolean = settings.isImMode || enableRecentChatsReference

fun Assistant.timeReminderEnabled(settings: Settings): Boolean = settings.isImMode || enableTimeReminder

fun Assistant.memoryExtractionEnabled(settings: Settings): Boolean =
    enableMemory && (settings.isImMode || autoExtractMemory)

fun Settings.withInterfaceMode(mode: InterfaceMode): Settings = copy(
    interfaceMode = InterfaceModeState(mode = mode, choicePending = false),
)

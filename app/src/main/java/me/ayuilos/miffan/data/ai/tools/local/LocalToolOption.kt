package me.ayuilos.miffan.data.ai.tools.local

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LocalToolOption {
    @Serializable
    @SerialName("javascript_engine")
    data object JavascriptEngine : LocalToolOption()

    @Serializable
    @SerialName("time_info")
    data object TimeInfo : LocalToolOption()

    @Serializable
    @SerialName("clipboard")
    data object Clipboard : LocalToolOption()

    @Serializable
    @SerialName("tts")
    data object Tts : LocalToolOption()

    @Serializable
    @SerialName("ask_user")
    data object AskUser : LocalToolOption()

    @Serializable
    @SerialName("screen_time")
    data object ScreenTime : LocalToolOption()

    @Serializable
    @SerialName("calendar")
    data object Calendar : LocalToolOption()

    @Serializable
    @SerialName("bug_report")
    data object BugReport : LocalToolOption()

    @Serializable
    @SerialName("extension_management")
    data object ExtensionManagement : LocalToolOption()
}

/** What every new partner can do without asking: tell the time, and ask the owner a question on a card. */
val DEFAULT_LOCAL_TOOLS: List<LocalToolOption> = listOf(LocalToolOption.TimeInfo, LocalToolOption.AskUser)

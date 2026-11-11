package me.ayuilos.miffan.data.ai.tools

import me.ayuilos.miffan.data.ai.tools.local.LocalToolOption

const val REQUEST_ABILITY = "request_ability"

/**
 * The abilities easy mode lets the owner switch on per partner, each one local tool. The partner can ask
 * for one in the chat ([REQUEST_ABILITY]); the owner can also flip them on the partner's profile.
 */
enum class PartnerAbility(val id: String, val option: LocalToolOption, val meaning: String) {
    ASK_USER("ask_user", LocalToolOption.AskUser, "ask the owner questions with tappable options"),
    CALENDAR("calendar", LocalToolOption.Calendar, "read the owner's calendar and add events with their approval"),
    CLIPBOARD("clipboard", LocalToolOption.Clipboard, "read and write the clipboard"),
    SCREEN_TIME("screen_time", LocalToolOption.ScreenTime, "read how long the owner used each app"),
    READ_ALOUD("read_aloud", LocalToolOption.Tts, "read text aloud on the phone"),
    APP_SETTINGS("app_settings", LocalToolOption.AppSettings,
        "set up the owner's AI services: read the provider and model setup, connect an AI or web search service or set a secret header (the owner types the key " +
            "into a secure card you never see), and change services, models, partner models and default models (the owner confirms on a card)"),
    ;

    companion object {
        fun of(id: String?): PartnerAbility? = entries.firstOrNull { it.id == id }

        /** The abilities still off for a partner with these local tools. */
        fun offIn(localTools: List<LocalToolOption>): List<PartnerAbility> = entries.filter { it.option !in localTools }
    }
}

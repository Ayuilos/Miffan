package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.*
import me.ayuilos.miffan.data.model.*

/** Shared P2/P3 validation. Callers convert validation failures to tool error JSON. */
internal fun validateProfileAvatar(
    params: JsonObject,
    emojiValues: () -> Set<String>,
    allowPrevious: Boolean = true,
): ProfileAvatarChange {
    fun string(key: String): String? = params[key]?.let {
        require(it is JsonPrimitive && it.isString) { "$key must be a string." }
        it.content
    }
    val emoji = string("avatar_emoji")
    val preset = string("avatar_preset")
    require(emoji == null || preset == null) { "avatar_emoji and avatar_preset are mutually exclusive." }
    require(preset == null || preset in if (allowPrevious) setOf("miffan", "whale_girl", "previous") else setOf("miffan", "whale_girl")) { "Invalid avatar_preset." }
    require(("miffan_kind" !in params && "miffan_palette" !in params) || preset == "miffan") {
        "miffan_kind and miffan_palette require avatar_preset=miffan."
    }
    val kind = params["miffan_kind"]?.let {
        runCatching { Json.decodeFromJsonElement<MiffanKind>(it) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid miffan_kind.")
    }
    val palette = params["miffan_palette"]?.let {
        runCatching { Json.decodeFromJsonElement<MiffanPalette>(it) }.getOrNull()
            ?: throw IllegalArgumentException("Invalid miffan_palette.")
    }
    require(emoji == null || isSingleProfileEmoji(emoji, emojiValues())) { "avatar_emoji must be a single emoji grapheme cluster." }
    return ProfileAvatarChange(emoji, preset, kind, palette)
}

internal data class ProfileAvatarChange(
    val emoji: String?,
    val preset: String?,
    val kind: MiffanKind?,
    val palette: MiffanPalette?,
) {
    fun resolve(current: Avatar, history: List<Avatar> = emptyList()): Avatar? = when (preset) {
        "miffan" -> Avatar.Miffan(
            appearance = current.miffanAppearanceOrDefault().let { it.copy(kind = kind ?: it.kind, palette = palette ?: it.palette) },
            motionProfile = current.characterMotionProfileOrDefault(),
        )
        "whale_girl" -> Avatar.WhaleGirl(current.characterMotionProfileOrDefault())
        "previous" -> history.firstOrNull { it != current }
        else -> emoji?.let { Avatar.Emoji(it) } ?: current
    }
}

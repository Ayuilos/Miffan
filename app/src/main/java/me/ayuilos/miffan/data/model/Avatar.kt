package me.ayuilos.miffan.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class Avatar {
    @Serializable
    @SerialName("dummy")
    data object Dummy : Avatar()

    @Serializable
    @SerialName("emoji")
    data class Emoji(val content: String) : Avatar()

    @Serializable
    @SerialName("image")
    data class Image(val url: String) : Avatar()

    @Serializable
    @SerialName("miffan")
    data class Miffan(
        val appearance: MiffanAppearance = MiffanAppearance(),
    ) : Avatar()
}

@Serializable
data class MiffanAppearance(
    val palette: MiffanPalette = MiffanPalette.CLASSIC,
)

@Serializable
enum class MiffanPalette {
    @SerialName("classic")
    CLASSIC,

    @SerialName("matcha")
    MATCHA,

    @SerialName("sakura")
    SAKURA,

    @SerialName("moonlight")
    MOONLIGHT,

    @SerialName("sea_salt")
    SEA_SALT,

    @SerialName("ink_jade")
    INK_JADE,
}

fun Avatar.isMiffanAvatar(): Boolean = this is Avatar.Miffan || this is Avatar.Dummy

fun Avatar.miffanAppearanceOrDefault(): MiffanAppearance =
    (this as? Avatar.Miffan)?.appearance ?: MiffanAppearance()

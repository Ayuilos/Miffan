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
        val motionProfile: MiffanMotionProfile = MiffanMotionProfile.CURIOUS,
    ) : Avatar()
}

@Serializable
data class MiffanAppearance(
    val palette: MiffanPalette = MiffanPalette.CLASSIC,
    val kind: MiffanKind = MiffanKind.RICE,
    val colorSource: MiffanColorSource = MiffanColorSource.PALETTE,
)

@Serializable
enum class MiffanColorSource {
    @SerialName("palette")
    PALETTE,

    @SerialName("app_theme")
    APP_THEME,
}

/**
 * Curated inhabitants of the Miffan world. A kind changes the readable
 * silhouette and surface language while keeping palette and motion independent.
 */
@Serializable
enum class MiffanKind {
    @SerialName("rice")
    RICE,

    @SerialName("sprout")
    SPROUT,

    @SerialName("dumpling")
    DUMPLING,

    @SerialName("stargazer")
    STARGAZER,
}

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

@Serializable
enum class MiffanMotionProfile {
    @SerialName("lively")
    LIVELY,

    @SerialName("calm")
    CALM,

    @SerialName("curious")
    CURIOUS,
}

fun Avatar.isMiffanAvatar(): Boolean = this is Avatar.Miffan || this is Avatar.Dummy

fun Avatar.miffanAppearanceOrDefault(): MiffanAppearance =
    (this as? Avatar.Miffan)?.appearance ?: MiffanAppearance()

fun Avatar.miffanMotionProfileOrDefault(): MiffanMotionProfile =
    (this as? Avatar.Miffan)?.motionProfile ?: MiffanMotionProfile.CURIOUS

fun Avatar.withMiffanAppearance(appearance: MiffanAppearance): Avatar.Miffan =
    Avatar.Miffan(
        appearance = appearance,
        motionProfile = miffanMotionProfileOrDefault(),
    )

fun Avatar.withMiffanMotionProfile(motionProfile: MiffanMotionProfile): Avatar.Miffan =
    Avatar.Miffan(
        appearance = miffanAppearanceOrDefault(),
        motionProfile = motionProfile,
    )

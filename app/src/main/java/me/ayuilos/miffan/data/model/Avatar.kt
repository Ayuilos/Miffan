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
}

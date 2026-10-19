package me.ayuilos.miffan.data.model

import kotlinx.serialization.Serializable

/** Why an IM segment started; null on legacy segments and the first segment of a thread. */
@Serializable
enum class TopicStart { AUTO, IDLE, OWNER, WELCOME }

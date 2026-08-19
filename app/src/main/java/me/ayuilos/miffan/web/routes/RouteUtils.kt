package me.ayuilos.miffan.web.routes

import kotlin.uuid.Uuid
import me.ayuilos.miffan.web.BadRequestException

internal fun String?.toUuid(name: String = "id"): Uuid {
    if (this == null) throw BadRequestException("Missing $name")
    return runCatching { Uuid.parse(this) }.getOrNull()
        ?: throw BadRequestException("Invalid $name")
}

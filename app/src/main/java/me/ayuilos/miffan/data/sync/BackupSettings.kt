package me.ayuilos.miffan.data.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.data.datastore.Settings

// Keep the legacy backup field even though launch count is no longer part of Settings.
internal fun Json.encodeBackupSettings(settings: Settings, launchCount: Int): String =
    encodeToString(JsonObject(encodeToJsonElement(settings).jsonObject + ("launchCount" to JsonPrimitive(launchCount))))

internal fun Json.backupLaunchCount(settingsJson: String): Int =
    parseToJsonElement(settingsJson).jsonObject["launchCount"]?.jsonPrimitive?.intOrNull ?: 0

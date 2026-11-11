package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.appsettings.CONNECT_PROVIDER_TOOL
import me.ayuilos.miffan.data.appsettings.ConnectTargetResult
import me.ayuilos.miffan.data.appsettings.CustomProviderApi
import me.ayuilos.miffan.data.appsettings.buildAppSettingsCatalog
import me.ayuilos.miffan.data.appsettings.resolveConnectTarget
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart

const val APP_SETTINGS_CATALOG_TOOL = "app_settings_catalog"

/**
 * The app-settings ability: read the AI service setup, and connect a service through a secure key card. Only offered
 * on the owner's own turns. Keys never pass through these tools in either direction: the catalog has no credential,
 * and a connect call is settled by the app from the card ([me.ayuilos.miffan.data.appsettings.ProviderConnectCards]).
 */
internal fun buildAppSettingsTools(settingsStore: SettingsStore): List<Tool> = listOf(
    Tool(
        name = APP_SETTINGS_CATALOG_TOOL,
        description = """
            Read the app's AI service setup: each provider (id, name, api, host, whether a key is set, models), the
            default models, and which model each partner uses. Use it before connecting or changing services.
            API keys are never readable here or anywhere else.
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            listOf(UIMessagePart.Text(buildAppSettingsCatalog(settingsStore.settingsFlow.value).toString()))
        },
    ),
    Tool(
        name = CONNECT_PROVIDER_TOOL,
        description = """
            Connect an AI service for the owner, or replace its key, when the owner asks for it. The app shows the
            owner a secure card where they type the API key: you never see the key. Never ask the owner to send a key
            in the chat, and if they do, do not repeat it; point them to the card instead.
            Use provider_id from app_settings_catalog for a listed service (presets included), or custom for a service
            the owner names by address (OpenAI-compatible gateways use api "openai"). Give exactly one of them.
            The result is status "connected" with the service's chat models, or "saved_unchecked" when the service
            could not be reached; a refusal means the owner cancelled. Connecting adds no models yet.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("provider_id", buildJsonObject {
                        put("type", "string")
                        put("description", "id of a provider from app_settings_catalog")
                    })
                    put("custom", buildJsonObject {
                        put("type", "object")
                        put("description", "A service not in the catalog")
                        put("properties", buildJsonObject {
                            put("name", buildJsonObject {
                                put("type", "string")
                                put("description", "Display name, 1–40 characters")
                            })
                            put("api", buildJsonObject {
                                put("type", "string")
                                put("enum", buildJsonArray { CustomProviderApi.entries.forEach { add(it.id) } })
                            })
                            put("base_url", buildJsonObject {
                                put("type", "string")
                                put("description", "API base URL, e.g. https://example.com/v1; no credentials or query")
                            })
                        })
                        put("required", buildJsonArray { add("name"); add("api"); add("base_url") })
                    })
                    put("reason", buildJsonObject {
                        put("type", "string")
                        put("description", "Why, in the owner's language")
                    })
                },
            )
        },
        // A call the app cannot show a card for fails right away, with the reason, instead of waiting on the owner.
        needsApproval = { input -> resolveConnectTarget(input, settingsStore.settingsFlow.value) is ConnectTargetResult.Ok },
        execute = { input ->
            val error = when (val target = resolveConnectTarget(input, settingsStore.settingsFlow.value)) {
                is ConnectTargetResult.Invalid -> target.reason
                // Only the owner's card can complete a valid call; reaching here means it was not completed there.
                is ConnectTargetResult.Ok -> "The owner did not connect the service on the card."
            }
            listOf(UIMessagePart.Text(buildJsonObject { put("success", false); put("error", error) }.toString()))
        },
    ),
)

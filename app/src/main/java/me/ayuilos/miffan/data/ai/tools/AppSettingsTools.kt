package me.ayuilos.miffan.data.ai.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import me.ayuilos.miffan.data.revision.CurrentToolCall
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.data.appsettings.AppSettingsPlan
import me.ayuilos.miffan.data.appsettings.CHANGE_APP_SETTINGS_TOOL
import me.ayuilos.miffan.data.appsettings.CONNECT_PROVIDER_TOOL
import me.ayuilos.miffan.data.appsettings.DefaultModelRole
import me.ayuilos.miffan.data.appsettings.MAX_CHANGES
import me.ayuilos.miffan.data.appsettings.appSettingsResultJson
import me.ayuilos.miffan.data.appsettings.planAppSettingsChange
import me.ayuilos.miffan.data.revision.RevisionAuthor
import me.ayuilos.miffan.data.revision.RevisionOrigin
import me.rerere.ai.registry.ModelCatalog
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
internal fun buildAppSettingsTools(
    settingsStore: SettingsStore,
    catalog: () -> ModelCatalog = { ModelCatalog.EMPTY },
): List<Tool> = listOf(
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
            could not be reached; a refusal means the owner cancelled. Connecting adds no models: propose them afterwards
            with $CHANGE_APP_SETTINGS_TOOL.
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
    Tool(
        name = CHANGE_APP_SETTINGS_TOOL,
        description = """
            Change the AI service setup when the owner asks: switch services on or off, rename them, move a custom service
            to another address, add, remove or adjust models, choose the model a partner uses, and set the default models.
            The owner confirms on a card listing every change; the changes apply together, in order, or not at all.
            Use ids and model_ids from app_settings_catalog (or the models a connect_provider result listed). Refer to a
            model as {provider_id, model_id}; a model added earlier in the same changes can be used right after.
            Keys are not part of this: use connect_provider. Built-in services keep their address. Moving a custom
            service to another address clears its key. Services cannot be deleted, only switched off.
            When you add models, add the few the owner wants (at most $MAX_CHANGES changes); abilities are filled in
            from the model id, and you only set tools / reasoning / image_input when you know better.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("changes", buildJsonObject {
                        put("type", "array")
                        put("description", "Ordered changes, 1–$MAX_CHANGES")
                        put("items", appSettingsChangeSchema())
                    })
                    put("reason", buildJsonObject {
                        put("type", "string")
                        put("description", "One short line for the card saying what this sets up, in the owner's language")
                    })
                },
                required = listOf("changes", "reason"),
            )
        },
        // A call the app cannot apply fails right away, with the reason, instead of asking the owner.
        needsApproval = { input -> planAppSettingsChange(input, settingsStore.settingsFlow.value, catalog()) is AppSettingsPlan.Ok },
        execute = { input ->
            if (currentCoroutineContext()[CurrentToolCall]?.approved != true) {
                // Invalid calls run without a card and must stay that way, even if the settings changed in between.
                val reason = (planAppSettingsChange(input, settingsStore.settingsFlow.value, catalog()) as? AppSettingsPlan.Invalid)?.reason
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", false)
                    put("error", reason ?: "Nothing was changed: the owner has to confirm on the card. Call the tool again.")
                }.toString()))
            }
            var plan: AppSettingsPlan? = null
            val reason = (input as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull?.take(200)
            // No trigger message: the call's own record in the chat is where this change is read and undone as a whole,
            // rather than a separate "changed its settings" notice that would undo only the partner's part.
            withContext(RevisionOrigin(RevisionAuthor.AGENT, summary = reason)) {
                settingsStore.update { settings ->
                    // Planned again against the settings as they are now: the owner may have changed something since.
                    planAppSettingsChange(input, settings, catalog()).also { plan = it }
                        .let { if (it is AppSettingsPlan.Ok) it.settings else settings }
                }
            }
            val text = when (val result = plan) {
                is AppSettingsPlan.Ok -> appSettingsResultJson(result.lines)
                is AppSettingsPlan.Invalid -> buildJsonObject { put("success", false); put("error", result.reason) }.toString()
                null -> buildJsonObject { put("success", false); put("error", "Nothing was changed.") }.toString()
            }
            listOf(UIMessagePart.Text(text))
        },
    ),
)

private fun appSettingsChangeSchema() = buildJsonObject {
    put("type", "object")
    put("description", "Fields by type: set_provider_enabled = provider_id, enabled; rename_provider = provider_id, name; " +
        "set_provider_address = provider_id, base_url (custom services only; clears the key); " +
        "add_models = provider_id, models [{model_id, name?, tools?, reasoning?, image_input?}]; " +
        "remove_models = provider_id, model_ids; update_model = provider_id, model_id, and name / tools / reasoning / image_input; " +
        "set_partner_model = partner_id, model ({provider_id, model_id} or \"default\"); " +
        "set_default_model = role, model ({provider_id, model_id}, or \"follow_fast\" for title and suggestion).")
    put("properties", buildJsonObject {
        put("type", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray {
                listOf("set_provider_enabled", "rename_provider", "set_provider_address", "add_models", "remove_models",
                    "update_model", "set_partner_model", "set_default_model").forEach { add(it) }
            })
        })
        put("provider_id", buildJsonObject { put("type", "string") })
        put("enabled", buildJsonObject { put("type", "boolean") })
        put("name", buildJsonObject { put("type", "string") })
        put("base_url", buildJsonObject { put("type", "string") })
        put("models", buildJsonObject {
            put("type", "array")
            put("items", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("model_id", buildJsonObject { put("type", "string") })
                    put("name", buildJsonObject { put("type", "string") })
                    put("tools", buildJsonObject { put("type", "boolean") })
                    put("reasoning", buildJsonObject { put("type", "boolean") })
                    put("image_input", buildJsonObject { put("type", "boolean") })
                })
                put("required", buildJsonArray { add("model_id") })
            })
        })
        put("model_ids", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
        put("model_id", buildJsonObject { put("type", "string") })
        put("tools", buildJsonObject { put("type", "boolean") })
        put("reasoning", buildJsonObject { put("type", "boolean") })
        put("image_input", buildJsonObject { put("type", "boolean") })
        put("partner_id", buildJsonObject { put("type", "string") })
        put("role", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray { DefaultModelRole.entries.forEach { add(it.id) } })
        })
        put("model", buildJsonObject {
            put("description", "{provider_id, model_id}, or \"default\" / \"follow_fast\"")
        })
    })
    put("required", buildJsonArray { add("type") })
}

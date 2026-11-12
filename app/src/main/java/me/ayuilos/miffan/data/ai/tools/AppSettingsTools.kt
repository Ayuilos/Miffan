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
import me.ayuilos.miffan.data.appsettings.Appearance
import me.ayuilos.miffan.data.appsettings.CHANGE_APP_SETTINGS_TOOL
import me.ayuilos.miffan.data.appsettings.AsrKeyService
import me.ayuilos.miffan.data.appsettings.CONNECT_VOICE_TOOL
import me.ayuilos.miffan.data.appsettings.TtsKeyService
import me.ayuilos.miffan.data.appsettings.resolveVoiceTarget
import me.ayuilos.miffan.data.appsettings.MAX_BACKUP_INTERVAL
import me.ayuilos.miffan.data.appsettings.MAX_CONTEXT_MESSAGES
import me.ayuilos.miffan.data.appsettings.MAX_REPLY_LIMIT
import me.ayuilos.miffan.data.appsettings.MIN_CONTEXT_MESSAGES
import me.ayuilos.miffan.data.appsettings.MIN_REPLY_LIMIT
import me.ayuilos.miffan.data.appsettings.CONNECT_PROVIDER_TOOL
import me.ayuilos.miffan.data.appsettings.CONNECT_SEARCH_TOOL
import me.ayuilos.miffan.data.appsettings.SET_SECRET_HEADER_TOOL
import me.ayuilos.miffan.data.appsettings.SearchKeyService
import me.ayuilos.miffan.data.appsettings.resolveHeaderTarget
import me.ayuilos.miffan.data.appsettings.resolveSearchTarget
import me.ayuilos.miffan.data.appsettings.DefaultModelRole
import me.ayuilos.miffan.data.appsettings.FontSize
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
    appearancePrefs: me.ayuilos.miffan.data.appsettings.AppearanceStore? = null,
    recorder: me.ayuilos.miffan.data.revision.AiServicesRevisionRecorder? = null,
    /** The partner whose turn this is, marked in the catalog so it can tell its own settings from another partner's. */
    selfId: kotlin.uuid.Uuid? = null,
): List<Tool> = listOf(
    Tool(
        name = APP_SETTINGS_CATALOG_TOOL,
        description = """
            Read the app's AI service setup: each provider (id, name, api, host, whether a key is set, models), the
            default models, which model each partner uses and its reply settings (you are the partner with is_you), and how
            the app looks. Use it before connecting or changing anything.
            API keys are never readable here or anywhere else.
        """.trimIndent().replace("\n", " "),
        parameters = { InputSchema.Obj(properties = buildJsonObject { }) },
        execute = {
            listOf(UIMessagePart.Text(buildAppSettingsCatalog(settingsStore.settingsFlow.value, appearancePrefs?.current(), selfId).toString()))
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
        name = CONNECT_SEARCH_TOOL,
        description = """
            Set up a web search service's API key for the owner when they ask. Like connect_provider, the app shows the
            owner a secure card for the key; you never see it, and must never ask for it in the chat.
            Give service_id (from app_settings_catalog search_services, for one with key_card true) or type (to add one).
            The app runs one test search with the key. The result says whether it is the search service in use.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("service_id", buildJsonObject { put("type", "string") })
                    put("type", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { SearchKeyService.entries.forEach { add(it.id) } })
                    })
                    put("reason", buildJsonObject { put("type", "string"); put("description", "Why, in the owner's language") })
                },
            )
        },
        needsApproval = { input -> resolveSearchTarget(input, settingsStore.settingsFlow.value) is ConnectTargetResult.Ok },
        execute = { input -> listOf(UIMessagePart.Text(secretCardFallback(resolveSearchTarget(input, settingsStore.settingsFlow.value)))) },
    ),
    Tool(
        name = CONNECT_VOICE_TOOL,
        description = """
            Set up a voice service's API key for the owner when they ask: kind "tts" reads replies aloud, kind "asr" turns
            their voice into text. Like connect_provider, the app shows the owner a secure card for the key; you never see
            it, and must never ask for it in the chat. Give service_id (from app_settings_catalog voice, for one with
            key_card true) or type (to add one). For tts the app reads a short sample with the key and the owner hears it.
            For tts you may also give voice, and for openrouter model (any OpenRouter speech model id, such as
            fish-audio/s2-pro); the sample is read with them, so a wrong one shows up right away. Voices belong to the
            model: on a model other than the default Gemini one, give the voice id that model's provider uses (for Fish
            Audio, the id of a voice from the owner's Fish Audio library), or none for the model's default voice.
            format picks the audio format where the service has a choice (catalog voice.tts formats): prefer mp3; pcm is
            raw audio played as 24 kHz, so on a model with another rate it sounds too fast or slow.
            Connecting does not put the service in use: propose select_tts_service / select_asr_service with
            $CHANGE_APP_SETTINGS_TOOL for that.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("kind", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("tts"); add("asr") }) })
                    put("service_id", buildJsonObject { put("type", "string") })
                    put("type", buildJsonObject {
                        put("type", "string")
                        put("description", "tts: ${TtsKeyService.entries.joinToString { it.id }}; asr: ${AsrKeyService.entries.joinToString { it.id }}")
                    })
                    put("model", buildJsonObject { put("type", "string"); put("description", "openrouter tts only: a speech model id") })
                    put("voice", buildJsonObject { put("type", "string"); put("description", "tts only: a voice id of the service or model") })
                    put("format", buildJsonObject { put("type", "string"); put("description", "tts only: mp3, pcm or wav where offered") })
                    put("reason", buildJsonObject { put("type", "string"); put("description", "Why, in the owner's language") })
                },
                required = listOf("kind"),
            )
        },
        needsApproval = { input -> resolveVoiceTarget(input, settingsStore.settingsFlow.value) is ConnectTargetResult.Ok },
        execute = { input -> listOf(UIMessagePart.Text(secretCardFallback(resolveVoiceTarget(input, settingsStore.settingsFlow.value)))) },
    ),
    Tool(
        name = SET_SECRET_HEADER_TOOL,
        description = """
            Set a secret request header (an access token) for an MCP server, or for one model of a provider, when the owner
            asks. The owner types the value into a secure card you never see; never ask for it in the chat.
            Give mcp_server_id, or provider_id with model_id (from app_settings_catalog). header defaults to Authorization;
            scheme "bearer" sends "Bearer <value>" (the default for Authorization), "raw" sends the value as typed.
            A header of the same name is replaced. The value is not checked: tell the owner to try the server or model.
        """.trimIndent().replace("\n", " "),
        parameters = {
            InputSchema.Obj(
                properties = buildJsonObject {
                    put("mcp_server_id", buildJsonObject { put("type", "string") })
                    put("provider_id", buildJsonObject { put("type", "string") })
                    put("model_id", buildJsonObject { put("type", "string") })
                    put("header", buildJsonObject { put("type", "string"); put("description", "Header name, e.g. Authorization or X-Api-Key") })
                    put("scheme", buildJsonObject {
                        put("type", "string")
                        put("enum", buildJsonArray { add("bearer"); add("raw") })
                    })
                    put("reason", buildJsonObject { put("type", "string"); put("description", "Why, in the owner's language") })
                },
            )
        },
        needsApproval = { input -> resolveHeaderTarget(input, settingsStore.settingsFlow.value) is ConnectTargetResult.Ok },
        execute = { input -> listOf(UIMessagePart.Text(secretCardFallback(resolveHeaderTarget(input, settingsStore.settingsFlow.value)))) },
    ),
    Tool(
        name = CHANGE_APP_SETTINGS_TOOL,
        description = """
            Change the AI service setup when the owner asks: switch services on or off, rename them, move a custom service
            to another address, add, remove or adjust models, choose the model a partner uses, set the default models, and
            choose the web search service in use, and adjust how the app looks and notifies: font size, theme, light or dark,
            notifications, and what partners call the owner, and a partner's reply settings (yours or another partner's):
            how long it thinks, how long a reply may get, how much of the chat it is sent, temperature, and memory; and voice:
            which service reads replies aloud and with which voice, how fast, what it skips, and which service does voice input;
            and the backup reminder.
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
        needsApproval = { input ->
            planAppSettingsChange(input, settingsStore.settingsFlow.value, catalog(), appearance = appearancePrefs?.current() ?: Appearance()) is AppSettingsPlan.Ok
        },
        execute = { input ->
            if (currentCoroutineContext()[CurrentToolCall]?.approved != true) {
                // Invalid calls run without a card and must stay that way, even if the settings changed in between.
                val reason = (planAppSettingsChange(input, settingsStore.settingsFlow.value, catalog(),
                    appearance = appearancePrefs?.current() ?: Appearance()) as? AppSettingsPlan.Invalid)?.reason
                return@Tool listOf(UIMessagePart.Text(buildJsonObject {
                    put("success", false)
                    put("error", reason ?: "Nothing was changed: the owner has to confirm on the card. Call the tool again.")
                }.toString()))
            }
            var plan: AppSettingsPlan? = null
            val appearanceBefore = appearancePrefs?.current() ?: Appearance()
            var settingsBefore: me.ayuilos.miffan.data.datastore.Settings? = null
            val reason = (input as? JsonObject)?.get("reason")?.jsonPrimitive?.contentOrNull?.take(200)
            // No trigger message: the call's own record in the chat is where this change is read and undone as a whole,
            // rather than a separate "changed its settings" notice that would undo only the partner's part.
            withContext(RevisionOrigin(RevisionAuthor.AGENT, summary = reason)) {
                settingsStore.update { settings ->
                    // Planned again against the settings as they are now: the owner may have changed something since.
                    settingsBefore = settings
                    planAppSettingsChange(input, settings, catalog(), appearance = appearanceBefore).also { plan = it }
                        .let { if (it is AppSettingsPlan.Ok) it.settings else settings }
                }
                // Light / dark lives outside the settings: record it in the same call's history, then switch it.
                val ok = plan as? AppSettingsPlan.Ok
                if (ok != null && ok.appearance != appearanceBefore && appearancePrefs != null) {
                    val before = settingsBefore?.let { me.ayuilos.miffan.data.revision.aiServicesSnapshot(it, appearanceBefore) }
                    recorder?.record(before, me.ayuilos.miffan.data.revision.aiServicesSnapshot(ok.settings, ok.appearance))
                    appearancePrefs.write(ok.appearance)
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
        "set_default_model = role, model ({provider_id, model_id}, or \"follow_fast\" for title and suggestion); " +
        "select_search_service = service_id (the web search service in use); " +
        "set_display = any of font_size (small, default, large, larger), latex, code_wrap, code_collapse, crop_before_send; " +
        "set_theme = any of theme (wallpaper or an id from catalog theme.choices), color_mode (system, light, dark), pure_black; " +
        "set_notifications = reply_done and/or live_progress; set_owner_name = name (what partners call the owner, empty clears); " +
        "set_fast_thinking = level (quick, auto, more, deep) for the fast model; set_default_model role image = a model that can " +
        "draw, or \"none\"; set_partner_settings = partner_id and any of thinking (quick, auto, more, deep), reply_limit " +
        "($MIN_REPLY_LIMIT–$MAX_REPLY_LIMIT tokens or \"none\"), context_messages ($MIN_CONTEXT_MESSAGES–$MAX_CONTEXT_MESSAGES or \"all\"), " +
        "temperature (0–2 or \"default\"), memory, shared_memory; nothing else, permissions are the owner's; " +
        "select_tts_service = service_id (a read-aloud service from catalog voice.tts, with a key); " +
        "set_tts_voice = service_id (default: the one in use), voice (an id from its voices, or for an OpenRouter model " +
        "without a list the id its provider uses), pitch (0.5–2, system only), tts_model (OpenRouter only: a speech model id; " +
        "give voice with it, as voices belong to the model), tts_format (an audio format from the service's formats; " +
        "prefer mp3: pcm is played as 24 kHz and sounds wrong from a model with another rate); " +
        "set_read_aloud = any of auto_read (read each reply aloud while the chat is open), speed (0.5–2, 1 is normal), only_quoted, " +
        "skip_brackets; " +
        "select_asr_service = service_id (a voice input service from catalog voice.asr, with a key); " +
        "set_backup_reminder = enabled and/or interval_days (1–$MAX_BACKUP_INTERVAL: remind at the top of Chats after that many " +
        "days without a backup). Where backups go is only for the owner to set.")
    put("properties", buildJsonObject {
        put("type", buildJsonObject {
            put("type", "string")
            put("enum", buildJsonArray {
                listOf("set_provider_enabled", "rename_provider", "set_provider_address", "add_models", "remove_models",
                    "update_model", "set_partner_model", "set_default_model", "select_search_service", "set_display", "set_theme",
                    "set_notifications", "set_owner_name", "set_fast_thinking", "set_partner_settings", "select_tts_service", "set_tts_voice",
                    "set_read_aloud", "select_asr_service", "set_backup_reminder").forEach { add(it) }
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
        put("service_id", buildJsonObject { put("type", "string") })
        put("font_size", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { FontSize.entries.forEach { add(it.id) } }) })
        put("latex", buildJsonObject { put("type", "boolean") })
        put("code_wrap", buildJsonObject { put("type", "boolean") })
        put("code_collapse", buildJsonObject { put("type", "boolean") })
        put("crop_before_send", buildJsonObject { put("type", "boolean") })
        put("theme", buildJsonObject { put("type", "string") })
        put("color_mode", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("system"); add("light"); add("dark") }) })
        put("pure_black", buildJsonObject { put("type", "boolean") })
        put("reply_done", buildJsonObject { put("type", "boolean") })
        put("live_progress", buildJsonObject { put("type", "boolean") })
        put("level", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("quick"); add("auto"); add("more"); add("deep") }) })
        put("thinking", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { add("quick"); add("auto"); add("more"); add("deep") }) })
        put("reply_limit", buildJsonObject { put("description", "tokens, or \"none\"") })
        put("context_messages", buildJsonObject { put("description", "a number, or \"all\"") })
        put("temperature", buildJsonObject { put("description", "0–2, or \"default\"") })
        put("memory", buildJsonObject { put("type", "boolean") })
        put("shared_memory", buildJsonObject { put("type", "boolean") })
        put("voice", buildJsonObject { put("type", "string") })
        put("tts_model", buildJsonObject { put("type", "string") })
        put("tts_format", buildJsonObject { put("type", "string") })
        put("speed", buildJsonObject { put("type", "number") })
        put("pitch", buildJsonObject { put("type", "number") })
        put("only_quoted", buildJsonObject { put("type", "boolean") })
        put("auto_read", buildJsonObject { put("type", "boolean") })
        put("interval_days", buildJsonObject { put("type", "integer") })
        put("skip_brackets", buildJsonObject { put("type", "boolean") })
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

/** What a secure card call returns when it runs at all: it was invalid, or the owner did not complete the card. */
private fun secretCardFallback(target: ConnectTargetResult): String = buildJsonObject {
    put("success", false)
    put("error", when (target) {
        is ConnectTargetResult.Invalid -> target.reason
        is ConnectTargetResult.Ok -> "The owner did not complete the card."
    })
}.toString()

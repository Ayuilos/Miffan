package me.ayuilos.miffan.data.bugreport

import android.content.Context
import android.os.Build
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.ayuilos.miffan.BuildConfig
import me.ayuilos.miffan.data.ai.AgentAccess
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.UIMessagePart
import kotlinx.serialization.json.buildJsonObject

/** Deliberately accepts no settings, assistant, conversation, provider or credential objects. */
@Serializable
data class AppDiagnostics(
    @SerialName("app_version") val appVersion: String,
    @SerialName("version_code") val versionCode: Int,
    @SerialName("build_type") val buildType: String,
    @SerialName("android_version") val androidVersion: AndroidVersion,
    val device: DiagnosticDevice,
    @SerialName("interface_mode") val interfaceMode: String,
    val locale: String,
    @SerialName("recent_errors") val recentErrors: List<DiagnosticError> = emptyList(),
)
@Serializable
data class AndroidVersion(val release: String, val sdk: Int)
@Serializable
data class DiagnosticDevice(val manufacturer: String, val model: String)

private val diagnosticsJson = Json { encodeDefaults = true }

fun deviceDiagnostics(context: Context, isImMode: Boolean): AppDiagnostics = AppDiagnostics(
    appVersion = BuildConfig.VERSION_NAME,
    versionCode = BuildConfig.VERSION_CODE.toInt(),
    buildType = BuildConfig.BUILD_TYPE,
    androidVersion = AndroidVersion(Build.VERSION.RELEASE, Build.VERSION.SDK_INT),
    device = DiagnosticDevice(Build.MANUFACTURER, Build.MODEL),
    interfaceMode = if (isImMode) "easy" else "professional",
    locale = context.resources.configuration.locales[0].toLanguageTag(),
)

@Suppress("UNUSED_PARAMETER") // App/device data are shared; no caller-specific data lookup is permitted.
fun createAppDiagnosticsTool(access: AgentAccess, diagnostics: suspend () -> AppDiagnostics): Tool = Tool(
    name = "app_diagnostics",
    description = "Read Miffan version, device, interface mode, locale and the ten most recent sanitized app error summaries. No partner configuration or chat content. Read-only, local to this device.",
    parameters = { InputSchema.Obj(buildJsonObject {}) },
    execute = {
        listOf(UIMessagePart.Text(diagnosticsJson.encodeToString(diagnostics())))
    },
)

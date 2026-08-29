package me.ayuilos.miffan.data.ai.mcp

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Browser
import androidx.browser.customtabs.CustomTabsIntent
import androidx.core.net.toUri

/** OAuth 授权回调的 redirect_uri，需与 AndroidManifest 中 McpOAuthCallbackActivity 的 intent-filter 保持一致。 */
const val MCP_OAUTH_REDIRECT_URI = "miffan://mcp-oauth-callback"

/** 使用 Chrome Custom Tabs 打开授权 URL，并可让授权页采用 APP 当前语言。 */
fun launchOAuthAuthorization(
    context: Context,
    authorizationUrl: String,
    acceptLanguage: String? = null,
) {
    val intent = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .build()
    normalizeOAuthAcceptLanguage(acceptLanguage)?.let { language ->
        intent.intent.putExtra(
            Browser.EXTRA_HEADERS,
            Bundle().apply { putString("Accept-Language", language) },
        )
    }
    intent.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    intent.launchUrl(context, authorizationUrl.toUri())
}

internal fun normalizeOAuthAcceptLanguage(language: String?): String? = language
    ?.trim()
    ?.takeIf { value ->
        value.isNotEmpty() &&
            value.length <= 64 &&
            value.all { character -> character.isLetterOrDigit() || character == '-' }
    }

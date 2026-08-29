package me.ayuilos.miffan.ui.activity

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import me.ayuilos.miffan.RouteActivity
import me.ayuilos.miffan.data.ai.openrouter.OpenRouterAuthService
import org.koin.android.ext.android.inject

/** Receives the one-time OpenRouter authorization code forwarded by the Miffan website. */
class OpenRouterOAuthCallbackActivity : ComponentActivity() {
    private val authService by inject<OpenRouterAuthService>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri?.scheme == "miffan" && uri.host == "openrouter-oauth-callback") {
            authService.handleCallback(
                state = uri.getQueryParameter("state"),
                code = uri.getQueryParameter("code"),
                error = uri.getQueryParameter("error"),
                errorDescription = uri.getQueryParameter("error_description"),
            )
            startActivity(
                Intent(this, RouteActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        }
        finish()
    }
}

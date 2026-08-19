package me.ayuilos.miffan.ui.activity

import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.data.event.AppEvent
import me.ayuilos.miffan.data.event.AppEventBus
import org.koin.android.ext.android.inject

/**
 * 透明 Activity，用于接收 MCP OAuth 授权完成后的 deep link 回调
 * (miffan://mcp-oauth-callback?code=...&state=...)，解析后经 [AppEventBus] 转发。
 */
class McpOAuthCallbackActivity : ComponentActivity() {
    private val eventBus by inject<AppEventBus>()
    private val appScope by inject<AppScope>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data
        if (uri != null) {
            // 使用 AppScope 发送，避免 finish() 取消协程导致事件丢失
            appScope.launch {
                eventBus.emit(
                    AppEvent.McpOAuthCallback(
                        state = uri.getQueryParameter("state"),
                        code = uri.getQueryParameter("code"),
                        error = uri.getQueryParameter("error"),
                    )
                )
            }
        }
        finish()
    }
}

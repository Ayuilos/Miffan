package me.ayuilos.miffan.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import me.ayuilos.miffan.AppScope
import me.ayuilos.miffan.data.datastore.SettingsStore
import me.ayuilos.miffan.data.model.MessageOrigin
import me.ayuilos.miffan.data.thread.ThreadService
import me.rerere.ai.ui.UIMessagePart
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import kotlin.uuid.Uuid

/** Development entry point, absent from release sources and manifest. */
class DeliverEventReceiver : BroadcastReceiver(), KoinComponent {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val pending = goAsync()
        get<AppScope>().launch {
            try {
                val settings = get<SettingsStore>().settingsFlow.first { !it.init }
                val assistantId = intent.getStringExtra("assistant")?.let(Uuid::parse)
                    ?: settings.assistants.firstOrNull()?.id ?: error("No assistant")
                val text = requireNotNull(intent.getStringExtra("text")) { "Missing text" }
                val summary = requireNotNull(intent.getStringExtra("summary")) { "Missing summary" }
                val id = get<ThreadService>().deliverFrom(
                    MessageOrigin.Event("debug", summary), assistantId, listOf(UIMessagePart.Text(text)),
                    intent.getStringExtra("segment")?.let(Uuid::parse),
                )
                Log.i("DeliverEventReceiver", "Delivered to $id")
            } catch (e: Exception) {
                Log.e("DeliverEventReceiver", "Event delivery failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION = "me.ayuilos.miffan.debug.DELIVER_EVENT"
    }
}

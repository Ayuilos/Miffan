package me.ayuilos.miffan.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.ayuilos.miffan.utils.UpdateChecker
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/** 「跳过这个版本」 on the new-version notification. */
class UpdateActionReceiver : BroadcastReceiver(), KoinComponent {
    private val checker: UpdateChecker by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SKIP) return
        val version = intent.getStringExtra(EXTRA_VERSION) ?: return
        checker.skip(version)
    }

    companion object {
        const val ACTION_SKIP = "me.ayuilos.miffan.action.UPDATE_SKIP_VERSION"
        const val EXTRA_VERSION = "version"
    }
}

package me.ayuilos.miffan.utils

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import me.ayuilos.miffan.R
import me.ayuilos.miffan.RouteActivity
import me.ayuilos.miffan.service.UpdateActionReceiver
import me.ayuilos.miffan.service.UpdateDownloadForegroundService
import java.io.File

/** Opens the update page from a notification. */
const val OPEN_UPDATE_EXTRA = "openUpdate"

/** Dedicated update channel; terminal notifications survive the foreground service stopping. */
internal object UpdateDownloadNotifications {
    const val PROGRESS_ID = 2003
    private const val RESULT_ID = 2004
    private const val NEW_VERSION_ID = 2005
    private const val CHANNEL_ID = "app_updates"

    private fun builder(context: Context): NotificationCompat.Builder {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.update_download_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(context.notificationSmallIcon())
    }

    fun progress(context: Context, state: UpdateDownloadState): Notification {
        val downloading = state as? UpdateDownloadState.Downloading
        val total = downloading?.totalBytes
        val percent = if (total != null && total > 0) {
            (downloading.downloadedBytes.toDouble() / total * 100).toInt().coerceIn(0, 100)
        } else 0
        val cancel = PendingIntent.getService(
            context, PROGRESS_ID,
            Intent(context, UpdateDownloadForegroundService::class.java).setAction(UpdateDownloadForegroundService.ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return builder(context)
            .setContentTitle(context.getString(R.string.update_download_title))
            .setContentText(context.getString(
                if (state is UpdateDownloadState.Verifying) R.string.update_download_verifying
                else R.string.update_download_progress,
            ))
            .setSubText(downloading?.sourceHost)
            .setProgress(100, percent, downloading == null || total == null)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.update_download_cancel), cancel)
            .build()
    }

    private fun openUpdatePage(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, RouteActivity::class.java)
            .putExtra(OPEN_UPDATE_EXTRA, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** One per version, posted only while Miffan is in the background. */
    fun newVersion(context: Context, info: UpdateInfo) {
        runCatching {
            val chinese = context.resources.configuration.locales[0].language == "zh"
            val items = parseReleaseNotes(info.changelog, chinese).items
            val first = items.firstOrNull()?.let { it.title ?: it.body }
            val text = when {
                first == null -> context.getString(R.string.update_notify_new_text_fallback)
                items.size > 1 -> context.getString(R.string.update_notify_new_text_more, first, items.size - 1)
                else -> first
            }
            val skip = PendingIntent.getBroadcast(
                context, NEW_VERSION_ID,
                Intent(context, UpdateActionReceiver::class.java)
                    .setAction(UpdateActionReceiver.ACTION_SKIP)
                    .putExtra(UpdateActionReceiver.EXTRA_VERSION, info.version),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = builder(context)
                .setContentTitle(context.getString(R.string.update_notify_new_title, info.version))
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openUpdatePage(context, NEW_VERSION_ID))
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.update_notify_action_update), openUpdatePage(context, NEW_VERSION_ID + 100))
                .addAction(0, context.getString(R.string.update_notify_action_skip), skip)
                .build()
            post(context, NEW_VERSION_ID, notification)
        }.onFailure { Log.e("UpdateDownload", "Unable to show new version notification", it) }
    }

    fun cancelNewVersion(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NEW_VERSION_ID)
    }

    fun clearResult(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(RESULT_ID)
    }

    fun updateProgress(context: Context, state: UpdateDownloadState) = post(context, PROGRESS_ID, progress(context, state))

    fun completed(context: Context, state: UpdateDownloadState) {
        // Notification failures (including revoked permission) must never alter the download state.
        runCatching {
            val notification = builder(context).setAutoCancel(true)
            when (state) {
                is UpdateDownloadState.Ready -> {
                    cancelNewVersion(context)
                    val version = state.download.version()
                    // Without the install permission the update page explains it first.
                    val open = if (context.packageManager.canRequestPackageInstalls()) {
                        val file = File(context.cacheDir, "updates/${state.download.name}")
                        PendingIntent.getActivity(
                            context, RESULT_ID, updateInstallIntent(context, file),
                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                        )
                    } else openUpdatePage(context, RESULT_ID)
                    notification.setContentTitle(
                        if (version != null) context.getString(R.string.update_notify_ready_title, version)
                        else context.getString(R.string.update_download_complete_title)
                    )
                        .setContentText(context.getString(R.string.update_notify_ready_text))
                        .setContentIntent(open)
                        .addAction(0, context.getString(R.string.update_card_install), open)
                }
                is UpdateDownloadState.Failed -> notification
                    .setContentTitle(context.getString(R.string.update_download_failed_title))
                    .setContentText(context.getString(
                        if (state.error == UpdateDownloadError.Network) R.string.update_notify_failed_network
                        else R.string.update_download_failed_text
                    ))
                    .setContentIntent(openUpdatePage(context, RESULT_ID))
                else -> return
            }
            post(context, RESULT_ID, notification.build())
        }.onFailure { Log.e("UpdateDownload", "Unable to show update result notification", it) }
    }

    private fun post(context: Context, id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        try {
            context.getSystemService(NotificationManager::class.java).notify(id, notification)
        } catch (error: SecurityException) {
            Log.e("UpdateDownload", "Update notification permission revoked", error)
        }
    }
}

/** "Miffan-4.8.1-arm64-v8a.apk" → "4.8.1". */
internal fun UpdateDownload.version(): String? =
    Regex("^Miffan-(.+)-arm64-v8a\\.apk$").matchEntire(name)?.groupValues?.get(1)

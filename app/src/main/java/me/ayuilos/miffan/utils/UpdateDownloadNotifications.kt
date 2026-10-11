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
import me.ayuilos.miffan.service.UpdateDownloadForegroundService
import java.io.File

/** Dedicated update channel; terminal notifications survive the foreground service stopping. */
internal object UpdateDownloadNotifications {
    const val PROGRESS_ID = 2003
    private const val RESULT_ID = 2004
    private const val CHANNEL_ID = "app_updates"

    private fun builder(context: Context): NotificationCompat.Builder {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, context.getString(R.string.update_download_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
        return NotificationCompat.Builder(context, CHANNEL_ID).setSmallIcon(R.drawable.small_icon)
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
                    val file = File(context.cacheDir, "updates/${state.download.name}")
                    val install = PendingIntent.getActivity(
                        context, RESULT_ID, updateInstallIntent(context, file),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    notification.setContentTitle(context.getString(R.string.update_download_complete_title))
                        .setContentText(context.getString(R.string.update_download_complete_text))
                        .setContentIntent(install)
                }
                is UpdateDownloadState.Failed -> notification
                    .setContentTitle(context.getString(R.string.update_download_failed_title))
                    .setContentText(context.getString(R.string.update_download_failed_text))
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

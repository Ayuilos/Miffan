package me.ayuilos.miffan.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import me.ayuilos.miffan.utils.UpdateApkDownloader
import me.ayuilos.miffan.utils.UpdateDownloadNotifications
import me.ayuilos.miffan.utils.UpdateDownloadState
import org.koin.android.ext.android.inject

/** Keeps the process alive and mirrors state; the app scope owns the actual download. */
class UpdateDownloadForegroundService : Service() {
    companion object {
        const val ACTION_CANCEL = "me.ayuilos.miffan.action.UPDATE_DOWNLOAD_CANCEL"

        fun start(context: Context) {
            runCatching {
                UpdateDownloadNotifications.clearResult(context)
                ContextCompat.startForegroundService(context, Intent(context, UpdateDownloadForegroundService::class.java))
            }.onFailure { Log.e("UpdateDownload", "Unable to start update foreground service", it) }
        }
    }

    private val downloader: UpdateApkDownloader by inject()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Even if a tiny download finished before this command arrived, fulfill startForeground
        // first. In particular, POST_NOTIFICATIONS is not required to enter foreground.
        try {
            ServiceCompat.startForeground(
                this, UpdateDownloadNotifications.PROGRESS_ID,
                UpdateDownloadNotifications.progress(this, downloader.downloadState.value),
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
            )
        } catch (error: Exception) {
            Log.e("UpdateDownload", "Unable to enter foreground", error)
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CANCEL) downloader.cancelUpdateDownload()
        observer?.cancel()
        observer = scope.launch {
            downloader.downloadState.collect { state ->
                when (state) {
                    is UpdateDownloadState.Downloading, is UpdateDownloadState.Verifying ->
                        UpdateDownloadNotifications.updateProgress(this@UpdateDownloadForegroundService, state)
                    else -> {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(startId)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        downloader.cancelUpdateDownload()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}

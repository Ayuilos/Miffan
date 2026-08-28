package me.ayuilos.miffan.utils

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Log
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.IOException

/** Persist the remaining sources so DOWNLOAD_COMPLETE can retry after the UI/process exits. */
internal object UpdateDownloader {
    private const val PREFERENCES_NAME = "pending_update_downloads"
    private val lock = Any()
    private val json = Json { ignoreUnknownKeys = true }

    fun enqueue(context: Context, download: UpdateDownload): Long = synchronized(lock) {
        val manager = context.getSystemService(DownloadManager::class.java)
        val queued = enqueueFirstAvailableDownload(download) { candidate ->
            manager.enqueue(
                DownloadManager.Request(candidate.url.toUri()).apply {
                    setTitle(candidate.name)
                    setDescription("正在下载更新包...")
                    setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    setAllowedNetworkTypes(DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE)
                    setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, candidate.name)
                    setMimeType("application/vnd.android.package-archive")
                }
            )
        }
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        if (!preferences.edit()
                .putString(queued.id.toString(), json.encodeToString(queued.download))
                .commit()
        ) {
            manager.remove(queued.id)
            throw IOException("Unable to persist update download")
        }
        queued.id
    }

    fun onDownloadComplete(context: Context, id: Long) = synchronized(lock) {
        val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        val encoded = preferences.getString(id.toString(), null) ?: return@synchronized
        val download = json.decodeFromString<UpdateDownload>(encoded)
        val manager = context.getSystemService(DownloadManager::class.java)
        val result = manager.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            // DownloadManager replaces COLUMN_URI after permanent redirects; it is not
            // a stable identity. The locally persisted ID and title identify our task.
            if (cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) != download.name
            ) return@use null
            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) to
                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        }
        if (result != null && result.first != DownloadManager.STATUS_SUCCESSFUL &&
            result.first != DownloadManager.STATUS_FAILED
        ) return@synchronized

        preferences.edit().remove(id.toString()).commit()
        // A missing task was removed/cancelled by the user. Never restart it.
        val next = result?.let { (status, reason) -> download.afterFailure(status, reason) }
        if (next != null) {
            // Only remove our failed task, releasing its partial destination before retrying.
            manager.remove(id)
            enqueue(context, next)
        }
    }
}

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (id < 0) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                UpdateDownloader.onDownloadComplete(context.applicationContext, id)
            } catch (error: Exception) {
                Log.e("UpdateDownloader", "Unable to process update download completion", error)
            } finally {
                pendingResult.finish()
            }
        }
    }
}

internal data class EnqueuedUpdateDownload(val id: Long, val download: UpdateDownload)

internal fun enqueueFirstAvailableDownload(
    download: UpdateDownload,
    enqueue: (UpdateDownload) -> Long,
): EnqueuedUpdateDownload {
    val urls = (listOf(download.url) + download.fallbackUrls).distinct()
    val failure = IOException("Unable to enqueue an update download")
    for ((index, url) in urls.withIndex()) {
        val candidate = download.copy(url = url, fallbackUrls = urls.drop(index + 1))
        try {
            return EnqueuedUpdateDownload(enqueue(candidate), candidate)
        } catch (error: Exception) {
            failure.addSuppressed(error)
        }
    }
    throw failure
}

internal fun UpdateDownload.afterFailure(status: Int, reason: Int): UpdateDownload? {
    if (status != DownloadManager.STATUS_FAILED || fallbackUrls.isEmpty()) return null
    val retryable = reason in 400..599 || reason in setOf(
        DownloadManager.ERROR_UNKNOWN,
        DownloadManager.ERROR_HTTP_DATA_ERROR,
        DownloadManager.ERROR_TOO_MANY_REDIRECTS,
        DownloadManager.ERROR_UNHANDLED_HTTP_CODE,
        DownloadManager.ERROR_CANNOT_RESUME,
    )
    // Storage errors (including an existing filename) cannot be fixed by switching sources.
    if (!retryable) return null
    return copy(url = fallbackUrls.first(), fallbackUrls = fallbackUrls.drop(1))
}

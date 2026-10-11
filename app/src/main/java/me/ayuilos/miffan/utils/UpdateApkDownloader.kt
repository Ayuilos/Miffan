package me.ayuilos.miffan.utils

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ayuilos.miffan.BuildConfig
import me.ayuilos.miffan.service.UpdateDownloadForegroundService
import me.rerere.common.http.await
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One app-wide job. Android lifetime and package parsing are boundaries, injectable in JVM tests. */
class UpdateApkDownloader internal constructor(
    client: OkHttpClient,
    private val appScope: CoroutineScope,
    private val directory: File,
    private val isAppPackage: (File) -> Boolean,
    private val onStart: () -> Unit = {},
    private val onTerminal: (UpdateDownloadState) -> Unit = {},
) {
    constructor(context: Context, client: OkHttpClient, appScope: CoroutineScope) : this(
        client = client,
        appScope = appScope,
        directory = File(context.applicationContext.cacheDir, "updates"),
        isAppPackage = { file ->
            @Suppress("DEPRECATION")
            (context.applicationContext.packageManager.getPackageArchiveInfo(file.path, 0)?.packageName ==
                context.applicationContext.packageName)
        },
        onStart = { UpdateDownloadForegroundService.start(context.applicationContext) },
        onTerminal = { state -> UpdateDownloadNotifications.completed(context.applicationContext, state) },
    ) {
        context.applicationContext.deleteSharedPreferences("pending_update_downloads")
    }

    private val client = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val downloadMutex = Mutex()
    private val lock = Any()
    private var generation = 0L
    private var job: Job? = null
    private val state = MutableStateFlow<UpdateDownloadState>(UpdateDownloadState.Idle)
    val downloadState: StateFlow<UpdateDownloadState> = state.asStateFlow()

    fun downloadUpdate(download: UpdateDownload): Unit = synchronized(lock) {
        val currentDownload = when (val current = state.value) {
            is UpdateDownloadState.Downloading -> current.download
            is UpdateDownloadState.Verifying -> current.download
            is UpdateDownloadState.Ready -> current.download
            else -> null
        }
        if (currentDownload == download) return@synchronized
        job?.cancel()
        val id = ++generation
        state.value = UpdateDownloadState.Downloading(download, host(download.url), 0, download.sizeBytes)
        // Request foreground lifetime on the caller's thread, before the activity can be hidden.
        onStart()
        job = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            try {
                downloadMutex.withLock { runDownload(download, id) }
            } catch (cancelled: CancellationException) {
                publish(id, UpdateDownloadState.Idle)
                throw cancelled
            }
        }.also { it.start() }
    }

    fun cancelUpdateDownload() = synchronized(lock) {
        ++generation
        job?.cancel()
        state.value = UpdateDownloadState.Idle
    }

    fun installUpdate(context: Context): Boolean = synchronized(lock) {
        val ready = state.value as? UpdateDownloadState.Ready ?: return@synchronized false
        context.startActivity(updateInstallIntent(context, File(directory, ready.download.name)))
        true
    }

    private fun publish(id: Long, value: UpdateDownloadState) = synchronized(lock) {
        if (id == generation) {
            state.value = value
            if (value is UpdateDownloadState.Ready || value is UpdateDownloadState.Failed) {
                onTerminal(value)
            }
        }
    }

    private suspend fun runDownload(download: UpdateDownload, id: Long) {
        var part: File? = null
        try {
            // Release metadata is remote input; never allow it to escape cacheDir/updates.
            if (download.name.isBlank() || download.name in listOf(".", "..") ||
                '/' in download.name || '\\' in download.name
            ) throw AttemptFailure(UpdateDownloadError.Storage)
            val final = File(directory, download.name)
            part = File(directory, "${download.name}.part")
            storage {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create updates directory")
                val files = directory.listFiles() ?: throw IOException("Cannot list updates directory")
                files.filter { it != final }.forEach(::deleteFile)
            }
            if (final.isFile) {
                publish(id, UpdateDownloadState.Verifying(download))
                if (verify(final, download)) {
                    publish(id, UpdateDownloadState.Ready(download))
                    return
                }
                storage { deleteFile(final) }
            }
            var lastError = UpdateDownloadError.Network
            for (url in listOf(download.url) + download.fallbackUrls) {
                currentCoroutineContext().ensureActive()
                try {
                    transfer(url, part, download, id)
                    publish(id, UpdateDownloadState.Verifying(download))
                    if (!verify(part, download)) throw AttemptFailure(UpdateDownloadError.Verification)
                    currentCoroutineContext().ensureActive()
                    storage {
                        if (!part.renameTo(final)) throw IOException("Cannot finalize update APK")
                    }
                    currentCoroutineContext().ensureActive()
                    publish(id, UpdateDownloadState.Ready(download))
                    return
                } catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    if (error is CancellationException) throw error
                    lastError = (error as? AttemptFailure)?.error ?: UpdateDownloadError.Network
                    try {
                        storage { deleteFile(part) }
                    } catch (_: AttemptFailure) {
                        lastError = UpdateDownloadError.Storage
                        break
                    }
                }
            }
            publish(id, UpdateDownloadState.Failed(download, lastError))
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            if (error is CancellationException) throw error
            publish(id, UpdateDownloadState.Failed(download, (error as? AttemptFailure)?.error ?: UpdateDownloadError.Storage))
        } finally {
            // The mutex holds until cleanup finishes, including across rapid replacements.
            part?.delete()
        }
    }

    private suspend fun transfer(url: String, part: File, download: UpdateDownload, id: Long) = coroutineScope {
        publish(id, UpdateDownloadState.Downloading(download, host(url), 0, download.sizeBytes))
        val request = Request.Builder().url(url)
            .header("User-Agent", "Miffan-Android/${BuildConfig.VERSION_NAME} #${BuildConfig.VERSION_CODE}")
            .build()
        val call = client.newCall(request)
        // await() cancels before headers; this watcher also interrupts blocking body reads.
        val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                call.cancel()
            }
        }
        try {
            call.await().use { response ->
                if (!response.isSuccessful) throw AttemptFailure(UpdateDownloadError.Network)
                val total = download.sizeBytes ?: response.body.contentLength().takeIf { it >= 0 }
                publish(id, UpdateDownloadState.Downloading(download, host(url), 0, total))
                response.body.byteStream().use { input ->
                    val output = storage { part.outputStream() }
                    try {
                        val buffer = ByteArray(32 * 1024)
                        var bytes = 0L
                        var lastBytes = 0L
                        var lastTime = System.nanoTime()
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            storage { output.write(buffer, 0, count) }
                            bytes += count
                            val now = System.nanoTime()
                            if (now - lastTime >= 200_000_000L ||
                                (total != null && total > 0 && bytes - lastBytes >= maxOf(1, total / 100))
                            ) {
                                publish(id, UpdateDownloadState.Downloading(download, host(url), bytes, total))
                                lastTime = now
                                lastBytes = bytes
                            }
                        }
                        publish(id, UpdateDownloadState.Downloading(download, host(url), bytes, total))
                    } finally {
                        storage { output.close() }
                    }
                }
            }
        } finally {
            cancellation.cancel()
        }
    }

    private suspend fun verify(file: File, download: UpdateDownload): Boolean {
        currentCoroutineContext().ensureActive()
        if (download.sizeBytes != null && file.length() != download.sizeBytes) return false
        if (download.sha256 != null) {
            val digest = MessageDigest.getInstance("SHA-256")
            storage {
                file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                }
            }
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(download.sha256, ignoreCase = true)) return false
        }
        currentCoroutineContext().ensureActive()
        val matchesPackage = try {
            isAppPackage(file)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
        currentCoroutineContext().ensureActive()
        return matchesPackage
    }

    private inline fun <T> storage(block: () -> T): T = try {
        block()
    } catch (error: IOException) {
        throw AttemptFailure(UpdateDownloadError.Storage, error)
    } catch (error: SecurityException) {
        throw AttemptFailure(UpdateDownloadError.Storage, error)
    }

    private fun deleteFile(file: File) {
        if (file.exists() && !file.delete()) throw IOException("Cannot remove update file")
    }

    private fun host(url: String): String = url.toHttpUrlOrNull()?.host.orEmpty()

    private class AttemptFailure(val error: UpdateDownloadError, cause: Throwable? = null) : IOException(cause)
}

internal fun updateInstallIntent(context: Context, file: File): Intent = Intent(Intent.ACTION_VIEW).apply {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    setDataAndType(uri, "application/vnd.android.package-archive")
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
}

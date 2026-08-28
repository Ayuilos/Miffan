package me.ayuilos.miffan.utils

import android.app.DownloadManager
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.UUID
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class UpdateDownloaderInstrumentedTest {
    @Test
    fun systemCompletionBroadcastRetriesFailedSources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(DownloadManager::class.java)
        val name = "miffan-update-test-${UUID.randomUUID()}.apk"
        val requests = Collections.synchronizedList(mutableListOf<String>())
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val serverThread = thread(isDaemon = true, name = "update-download-test") {
            try {
                while (!server.isClosed) {
                    server.accept().use { socket ->
                        socket.soTimeout = 5_000
                        val reader = socket.getInputStream().bufferedReader()
                        val path = reader.readLine().split(' ')[1]
                        while (!reader.readLine().isNullOrEmpty()) { /* Consume headers. */ }
                        requests += path
                        val body = if (path == "/github.apk") "test download" else ""
                        val status = when (path) {
                            "/custom.apk" -> "301 Moved Permanently"
                            "/github.apk" -> "200 OK"
                            else -> "404 Not Found"
                        }
                        val location = if (path == "/custom.apk") "Location: /missing.apk\r\n" else ""
                        socket.getOutputStream().write(
                            ("HTTP/1.1 $status\r\nContent-Length: ${body.length}\r\n" +
                                location +
                                "Content-Type: application/vnd.android.package-archive\r\n" +
                                "Connection: close\r\n\r\n$body").toByteArray()
                        )
                    }
                }
            } catch (error: Exception) {
                if (!server.isClosed) throw error
            }
        }
        fun downloads(): List<Pair<Long, Int>> = manager.query(DownloadManager.Query()).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    if (cursor.getString(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) == name) {
                        add(cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)) to
                            cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)))
                    }
                }
            }
        }
        try {
            val origin = "http://127.0.0.1:${server.localPort}"
            UpdateDownloader.enqueue(context, UpdateDownload(
                name = name,
                url = "$origin/custom.apk",
                sizeBytes = null,
                fallbackUrls = listOf("$origin/official.apk", "$origin/github.apk"),
            ))
            val deadline = SystemClock.elapsedRealtime() + 30_000
            while (downloads().none { it.second == DownloadManager.STATUS_SUCCESSFUL } &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                SystemClock.sleep(100)
            }
            assertEquals(listOf("/custom.apk", "/missing.apk", "/official.apk", "/github.apk"), requests)
            assertTrue("The fallback must finish through DownloadManager", downloads().any {
                it.second == DownloadManager.STATUS_SUCCESSFUL
            })
        } finally {
            downloads().forEach { (id, _) -> manager.remove(id) }
            server.close()
            serverThread.join(1_000)
        }
    }
}

package me.ayuilos.miffan.ui.pages.extensions.workspace.screen

import androidx.compose.ui.graphics.ImageBitmap
import io.mockk.mockk
import me.ayuilos.miffan.data.repository.RemoteHostWindow
import me.rerere.cuaspace.SpaceWindow
import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteWindowThumbnailsTest {
    private fun hosted(id: String, current: Boolean = true, minimized: Boolean = false, hidden: Boolean = false) = RemoteHostWindow(
        SpaceWindow(id = id, epoch = 0, app = "App", appId = "", pid = 1, title = id, x = 0.0, y = 0.0, width = 800.0, height = 600.0,
            state = if (minimized) SpaceWindow.State.MINIMIZED else SpaceWindow.State.NORMAL, available = true, limitation = null,
            focused = false, zOrder = 0),
        desktop = if (current) 1 else 2, current = current, hidden = hidden,
    )

    private val image = mockk<ImageBitmap>()
    private val now = 10_000_000L

    @Test fun `windows that gave no picture go last and wait longer after each failure`() {
        val windows = listOf(hosted("failed-once"), hosted("new-elsewhere", current = false), hosted("stale"), hosted("new"),
            hosted("failed-often"), hosted("minimized", minimized = true), hosted("hidden", hidden = true))
        val thumbnails = mapOf(
            "failed-once" to WindowThumbnail(null, now - 31_000, failed = true, failures = 1),
            "stale" to WindowThumbnail(image, now - 16_000),
            // Three failures in a row: two minutes between tries, so not yet.
            "failed-often" to WindowThumbnail(null, now - 100_000, failed = true, failures = 3),
        )
        assertEquals(listOf("new", "new-elsewhere", "stale", "failed-once"),
            dueThumbnails(windows, thumbnails, open = true, now = now).map { it.window.id })
        val later = thumbnails + ("failed-often" to WindowThumbnail(null, now - 121_000, failed = true, failures = 3))
        assertEquals(listOf("new", "new-elsewhere", "stale", "failed-once", "failed-often"),
            dueThumbnails(windows, later, open = true, now = now).map { it.window.id })
    }

    @Test fun `the wait doubles up to eight minutes`() {
        assertEquals(listOf(30_000L, 60_000L, 120_000L, 240_000L, 480_000L, 480_000L, 480_000L),
            (1..7).map(::thumbnailRetryMillis))
    }

    @Test fun `a window that came back is due at once and no longer last`() {
        val windows = listOf(hosted("back"), hosted("fresh"))
        val thumbnails = mapOf(
            "back" to WindowThumbnail(null, 0L, failed = true),
            "fresh" to WindowThumbnail(image, now - 1_000),
        )
        assertEquals(listOf("back"), dueThumbnails(windows, thumbnails, open = true, now = now).map { it.window.id })
    }

    @Test fun `with the picker closed only the desktop in front's new windows are taken`() {
        val windows = listOf(hosted("new"), hosted("elsewhere", current = false), hosted("failed"), hosted("stale"))
        val thumbnails = mapOf(
            "failed" to WindowThumbnail(null, 0L, failed = true, failures = 1),
            "stale" to WindowThumbnail(image, 0L),
        )
        assertEquals(listOf("new"), dueThumbnails(windows, thumbnails, open = false, now = now).map { it.window.id })
    }
}

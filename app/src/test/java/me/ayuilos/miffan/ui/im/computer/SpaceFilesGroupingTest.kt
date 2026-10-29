package me.ayuilos.miffan.ui.im.computer

import java.time.ZoneId
import java.time.ZonedDateTime
import me.rerere.workspace.WorkspaceFileEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceFilesGroupingTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = ZonedDateTime.of(2026, 10, 29, 15, 0, 0, 0, zone).toInstant().toEpochMilli()

    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant().toEpochMilli()
    private fun file(name: String, updatedAt: Long, directory: Boolean = false) =
        WorkspaceFileEntry("Desktop/Shared/$name", name, directory, 10, updatedAt)

    @Test fun todayAndEarlierAreSplitByTheLocalDateNewestFirst() {
        val groups = groupSpaceFiles(listOf(
            file("old.md", at(28, 23, 59)),
            file("morning.md", at(29, 8)),
            file("report", at(27, 10), directory = true),
            file("just-now.png", at(29, 14, 59)),
        ), now, zone)
        assertEquals(listOf("just-now.png", "morning.md"), groups.today.map { it.name })
        assertEquals(listOf("old.md", "report"), groups.earlier.map { it.name })
    }

    @Test fun justAfterMidnightIsStillToday() {
        assertEquals(listOf("a"), groupSpaceFiles(listOf(file("a", at(29, 0, 0))), now, zone).today.map { it.name })
    }

    @Test fun noFilesMakeNoGroups() {
        val groups = groupSpaceFiles(emptyList(), now, zone)
        assertTrue(groups.today.isEmpty() && groups.earlier.isEmpty())
    }

    @Test fun anUploadKnowsItsShareOnlyWhenTheSizeIsKnown() {
        assertEquals(50, SpaceFilesState.Upload("a", 1, 1, bytes = 50, total = 100).percent)
        assertEquals(100, SpaceFilesState.Upload("a", 1, 1, bytes = 120, total = 100).percent)
        assertEquals(null, SpaceFilesState.Upload("a", 1, 1, bytes = 50, total = null).percent)
        assertEquals(null, SpaceFilesState.Upload("a", 1, 1, bytes = 0, total = 0).percent)
    }
}

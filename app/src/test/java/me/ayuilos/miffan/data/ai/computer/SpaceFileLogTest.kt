package me.ayuilos.miffan.data.ai.computer

import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceFileLogTest {
    private val dir = Files.createTempDirectory("space-file-log").toFile()

    private fun notes(log: SpaceFileLog, id: String = "w1") = runBlocking { log.notes(id).first() }

    @Test fun notesComeNewestFirstAndSurviveARestart() {
        val log = SpaceFileLog(dir)
        log.record("w1", listOf("a.md"), SpaceFileDirection.TO_PARTNER, at = 10)
        log.record("w1", listOf("b.png"), SpaceFileDirection.FROM_PARTNER, at = 20)
        assertEquals(listOf("b.png", "a.md"), notes(log).map { it.path })
        assertEquals(notes(log), notes(SpaceFileLog(dir)))
    }

    @Test fun aFileThatChangesHandsKeepsOnlyItsLatestDirection() {
        val log = SpaceFileLog(dir)
        log.record("w1", listOf("a.md"), SpaceFileDirection.TO_PARTNER, at = 10)
        log.record("w1", listOf("a.md"), SpaceFileDirection.FROM_PARTNER, at = 30)
        assertEquals(listOf(SpaceFileNote("a.md", SpaceFileDirection.FROM_PARTNER, 30)), notes(log))
    }

    @Test fun forgettingAFolderTakesItsFilesToo() {
        val log = SpaceFileLog(dir)
        log.record("w1", listOf("photos", "photos/1.png", "photos2/2.png", "x.md"), SpaceFileDirection.FROM_PARTNER, at = 1)
        log.forget("w1", "photos")
        assertEquals(setOf("photos2/2.png", "x.md"), notes(log).map { it.path }.toSet())
    }

    @Test fun spacesAreKeptApartAndClearedOnTheirOwn() {
        val log = SpaceFileLog(dir)
        log.record("w1", listOf("a.md"), SpaceFileDirection.TO_PARTNER, at = 1)
        log.record("w2", listOf("b.md"), SpaceFileDirection.TO_PARTNER, at = 1)
        log.clear("w1")
        assertTrue(notes(log).isEmpty())
        assertEquals(listOf("b.md"), notes(SpaceFileLog(dir), "w2").map { it.path })
        assertTrue(dir.listFiles().orEmpty().none { it.name.startsWith("w1") })
    }

    @Test fun anUnreadableFileCountsAsNoNotes() {
        java.io.File(dir, "w1.json").writeText("{not json")
        assertTrue(notes(SpaceFileLog(dir)).isEmpty())
    }

    @Test fun onlyTheLatestTwoHundredAreKept() {
        val log = SpaceFileLog(dir)
        log.record("w1", (1..250).map { "f$it" }, SpaceFileDirection.TO_PARTNER, at = 5)
        assertEquals(200, notes(log).size)
    }
}

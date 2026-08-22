package me.ayuilos.miffan.ui.components.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime

class MiffanDayPhaseTest {
    @Test
    fun mapsPhaseBoundaries() {
        assertEquals(MiffanDayPhase.Night, miffanDayPhaseAt(LocalTime.of(4, 59)))
        assertEquals(MiffanDayPhase.Morning, miffanDayPhaseAt(LocalTime.of(5, 0)))
        assertEquals(MiffanDayPhase.Morning, miffanDayPhaseAt(LocalTime.of(10, 59)))
        assertEquals(MiffanDayPhase.Noon, miffanDayPhaseAt(LocalTime.of(11, 0)))
        assertEquals(MiffanDayPhase.Noon, miffanDayPhaseAt(LocalTime.of(17, 59)))
        assertEquals(MiffanDayPhase.Night, miffanDayPhaseAt(LocalTime.of(18, 0)))
    }
}

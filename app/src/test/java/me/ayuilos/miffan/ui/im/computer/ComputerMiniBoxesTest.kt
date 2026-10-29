package me.ayuilos.miffan.ui.im.computer

import me.ayuilos.miffan.data.ai.computer.SpacePower
import me.ayuilos.miffan.data.ai.computer.SpaceUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputerMiniBoxesTest {
    private fun awake(mb: Int?) = MiniBoxReading(SpacePower.AWAKE, mb?.let { SpaceUsage(it, null, null) })

    @Test
    fun `bar splits the total into use now and the rest of the limits`() {
        val (used, rest) = MiniBoxMemory(limitMb = 12288, usedMb = 8192, totalMb = 32768).shares()!!
        assertEquals(0.25f, used, 0.0001f)
        assertEquals(0.125f, rest, 0.0001f)
    }

    @Test
    fun `bar without use shows only the limits`() {
        val (used, rest) = MiniBoxMemory(limitMb = 8192, usedMb = null, totalMb = 32768).shares()!!
        assertEquals(0f, used, 0f)
        assertEquals(0.25f, rest, 0.0001f)
    }

    @Test
    fun `bar without the computer's size has no shares`() {
        assertNull(MiniBoxMemory(limitMb = 8192, usedMb = 700, totalMb = null).shares())
        assertNull(MiniBoxMemory(limitMb = 8192, usedMb = 700, totalMb = 0).shares())
    }

    @Test
    fun `bar never runs past the total or the limits`() {
        val over = MiniBoxMemory(limitMb = 40000, usedMb = 4000, totalMb = 32768).shares()!!
        assertEquals(1f, over.first + over.second, 0.0001f)
        val heavy = MiniBoxMemory(limitMb = 2048, usedMb = 5000, totalMb = 32768).shares()!!
        assertEquals(2048f / 32768, heavy.first, 0.0001f)
        assertEquals(0f, heavy.second, 0f)
    }

    @Test
    fun `used memory is the sum of what is known`() {
        assertEquals(1200, miniBoxesUsedMb(listOf(awake(700), awake(500), MiniBoxReading(SpacePower.ASLEEP, null))))
        assertEquals(700, miniBoxesUsedMb(listOf(awake(700), awake(null), MiniBoxReading(SpacePower.UNREACHABLE, null), null)))
    }

    @Test
    fun `used memory is zero only when every known box is asleep`() {
        assertEquals(0, miniBoxesUsedMb(listOf(MiniBoxReading(SpacePower.ASLEEP, null))))
    }

    @Test
    fun `used memory is unknown when nothing is known`() {
        assertNull(miniBoxesUsedMb(emptyList()))
        assertNull(miniBoxesUsedMb(listOf(null, awake(null), MiniBoxReading(SpacePower.UNKNOWN, null))))
    }

    @Test
    fun `a sleeping box's old usage does not count`() {
        assertEquals(0, miniBoxesUsedMb(listOf(MiniBoxReading(SpacePower.ASLEEP, SpaceUsage(900, null, null)))))
    }
}

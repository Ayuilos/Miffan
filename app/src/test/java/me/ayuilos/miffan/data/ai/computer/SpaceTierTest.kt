package me.ayuilos.miffan.data.ai.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpaceTierTest {
    private fun linux(cpus: Int, memoryGb: Int) = SpaceCapacity.from(CuaSpaceProbe.Host(cpus, memoryGb * 1024))!!

    @Test fun linuxHostsKeepHalfForTheUser() {
        // 8 GB laptop: 4 GB stays with the user, 4 GB for Spaces.
        assertEquals(SpaceTier.STANDARD, linux(4, 8).recommend())
        // 16 GB (CachyOS reports 15.5): keeping 8 GB, performance (8 GB) is not recommended...
        val cachyos = SpaceCapacity.from(CuaSpaceProbe.Host(12, 15920))!!
        assertEquals(SpaceTier.STANDARD, cachyos.recommend())
        // ...but the user may still pick it: a limit, not a reservation, and 4 GB stay free.
        assertTrue(cachyos.fits(SpaceTier.PERFORMANCE))
        assertEquals(SpaceTier.PERFORMANCE, linux(12, 32).recommend())
        // Two cores only: performance needs four.
        assertEquals(SpaceTier.STANDARD, linux(2, 64).recommend())
        // Less than 4 GB left still gets a computer, the smallest.
        assertEquals(SpaceTier.LIGHT, linux(2, 6).recommend())
        assertEquals(SpaceTier.LIGHT, linux(2, 4).recommend())
    }

    @Test fun otherSpacesAndSeveralPartnersShareTheBudget() {
        val host = linux(12, 32) // 16 GB for Spaces
        assertEquals(SpaceTier.STANDARD, host.recommend(usedMemoryMb = 10_240))
        assertEquals(SpaceTier.PERFORMANCE, host.recommend(count = 2))
        assertEquals(SpaceTier.STANDARD, host.recommend(count = 3))
        // Picking only has to leave 4 GB: 28 GB to spread.
        assertTrue(host.fits(SpaceTier.PERFORMANCE, usedMemoryMb = 10_240))
        assertFalse(host.fits(SpaceTier.PERFORMANCE, usedMemoryMb = 22_000))
        assertTrue(host.fits(SpaceTier.LIGHT, usedMemoryMb = 22_000))
    }

    @Test fun macSpacesShareCuasVm() {
        val mac = SpaceCapacity.from(CuaSpaceProbe.Host(8, 16_384, CuaSpaceProbe.Runtime(cpus = 4, memoryMb = 12_288)))!!
        assertEquals(SpaceCapacity(4, 11_264), mac)
        assertEquals(SpaceTier.PERFORMANCE, mac.recommend())
        assertEquals(SpaceTier.STANDARD, mac.recommend(usedMemoryMb = 4096))
        val small = SpaceCapacity.from(CuaSpaceProbe.Host(8, 16_384, CuaSpaceProbe.Runtime(cpus = 2, memoryMb = 4096)))!!
        assertEquals(SpaceTier.LIGHT, small.recommend())
    }

    @Test fun unknownHostsHaveNoCapacity() {
        assertNull(SpaceCapacity.from(null))
        assertNull(SpaceCapacity.from(CuaSpaceProbe.Host(cpus = null, memoryMb = 8192)))
        assertEquals(SpaceTier.STANDARD, SpaceTier.of(2, 4096))
        assertNull(SpaceTier.of(3, 4096))
    }

    @Test fun handoffFoldersAreEnglishSafeAndUnique() {
        assertEquals("Miffan Partners/小满", PartnerSpaces.handoffFolder("小满", emptySet()))
        assertEquals("Miffan Partners/小满 (2)", PartnerSpaces.handoffFolder("小满", setOf("Miffan Partners/小满")))
        assertEquals("Miffan Partners/a_b_c", PartnerSpaces.handoffFolder("a/b:c", emptySet()))
        assertEquals("Miffan Partners/Partner", PartnerSpaces.handoffFolder(" ..", emptySet()))
        assertEquals("Miffan Partners/x", PartnerSpaces.handoffFolder("..x..", emptySet()))
    }

    @Test fun spacesGetTheFontOfTheAppLanguage() {
        assertEquals("fonts-wqy-microhei", spaceFontPackage(java.util.Locale.SIMPLIFIED_CHINESE))
        assertEquals("fonts-wqy-microhei", spaceFontPackage(java.util.Locale.forLanguageTag("zh-TW")))
        assertEquals("fonts-ipafont-gothic", spaceFontPackage(java.util.Locale.JAPANESE))
        assertEquals("fonts-nanum", spaceFontPackage(java.util.Locale.KOREA))
        // Latin and Cyrillic are in the image already.
        assertNull(spaceFontPackage(java.util.Locale.ENGLISH))
        assertNull(spaceFontPackage(java.util.Locale.forLanguageTag("ru")))
        val withFont = spaceDesktopCommand("fonts-nanum")
        assertTrue("dpkg -s fonts-nanum >/dev/null 2>&1 || inst fonts-nanum" in withFont)
        assertTrue("libavcodec-extra" in withFont && "xfwm4 --replace" in withFont)
        // Without a font to add, only the codecs, and the desktop is left alone.
        val codecsOnly = spaceDesktopCommand(null)
        assertTrue("libavcodec-extra" in codecsOnly && "xfwm4" !in codecsOnly && "exit 1" !in codecsOnly)
    }
}

package me.ayuilos.miffan.ui.components.ui

import me.ayuilos.miffan.data.model.MiffanKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiffanKindStyleTest {
    @Test
    fun everyKindResolvesToADistinctCoherentStyle() {
        val styles = MiffanKind.entries.map { it.miffanKindStyle() }

        assertEquals(MiffanKind.entries.size, styles.toSet().size)
        assertTrue(styles.all { it.content.name.isNotBlank() })
    }

    @Test
    fun nonRiceKindsChangeAllThreeVisualSignals() {
        val rice = MiffanKind.RICE.miffanKindStyle()

        MiffanKind.entries.filterNot { it == MiffanKind.RICE }.forEach { kind ->
            val style = kind.miffanKindStyle()
            assertNotEquals(rice.content, style.content)
            assertNotEquals(rice.bowlFinish, style.bowlFinish)
            assertNotEquals(rice.accessory, style.accessory)
        }
    }
}

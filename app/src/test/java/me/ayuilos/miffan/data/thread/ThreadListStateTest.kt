package me.ayuilos.miffan.data.thread

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ThreadListStateTest {
    private val values = mutableMapOf<String, Any>()
    private val context = mockk<Context>()
    private val preferences = mockk<SharedPreferences>()
    private val editor = mockk<SharedPreferences.Editor>(relaxed = true)

    init {
        every { context.getSharedPreferences("im_thread_list", Context.MODE_PRIVATE) } returns preferences
        every { preferences.all } answers { values.toMap() }
        every { preferences.edit() } returns editor
        every { editor.putBoolean(any(), any()) } answers {
            values[firstArg()] = secondArg<Boolean>()
            editor
        }
        every { editor.putLong(any(), any()) } answers {
            values[firstArg()] = secondArg<Long>()
            editor
        }
        every { editor.remove(any()) } answers {
            values.remove(firstArg<String>())
            editor
        }
    }

    @Test
    fun collapsePersistsReloadsAndExpandRemovesOnlyTargetKey() {
        val id = Uuid.random()
        val other = Uuid.random()
        val state = ThreadListState(context)
        state.setGroupCollapsed(id, true)
        state.setGroupCollapsed(other, true)
        state.setGroupCollapsed(id, true)
        assertEquals(true, values["collapsed:$id"])
        assertEquals(setOf(id.toString(), other.toString()), ThreadListState(context).value.value.collapsedGroups)
        state.setGroupCollapsed(id, false)
        assertFalse(values.containsKey("collapsed:$id"))
        assertEquals(setOf(other.toString()), state.value.value.collapsedGroups)
        assertEquals(state.value.value, ThreadListState(context).value.value)
    }

    @Test
    fun loadIgnoresFalseOrMalformedCollapseValuesAndPreservesReadAndHidden() {
        values.putAll(mapOf("collapsed:yes" to true, "collapsed:no" to false, "collapsed:bad" to "true",
            "read:partner" to 12L, "hidden:partner" to 34L))
        val loaded = ThreadListState(context).value.value
        assertEquals(setOf("yes"), loaded.collapsedGroups)
        assertEquals(mapOf("partner" to 12L), loaded.readAt)
        assertEquals(mapOf("partner" to 34L), loaded.hiddenAt)
    }

    @Test
    fun migrationReadsAndClearsOnlyLegacyPinKeys() {
        val id = Uuid.random()
        values.putAll(mapOf("pinned:$id" to true, "pinned:bad" to true, "pinned:old" to false,
            "collapsed:group" to true, "read:$id" to 12L, "hidden:$id" to 34L))
        val state = ThreadListState(context)
        assertEquals(setOf(id.toString(), "bad", "old"), state.legacyPins())
        state.clearLegacyPins()
        state.clearLegacyPins()
        assertTrue(state.legacyPins().isEmpty())
        assertEquals(mapOf("collapsed:group" to true, "read:$id" to 12L, "hidden:$id" to 34L), values)
        assertEquals(state.value.value, ThreadListState(context).value.value)
    }

    @Test
    fun readAndHiddenUpdatesStillWorkWithCollapsedGroups() {
        val id = Uuid.random()
        val state = ThreadListState(context)
        state.setGroupCollapsed(id, true)
        state.markRead(id, 20L)
        state.markRead(id, 10L)
        state.hide(id, 30L)
        assertEquals(20L, state.value.value.readAt[id.toString()])
        assertEquals(30L, state.value.value.hiddenAt[id.toString()])
        assertEquals(setOf(id.toString()), state.value.value.collapsedGroups)
        assertEquals(state.value.value, ThreadListState(context).value.value)
    }
}

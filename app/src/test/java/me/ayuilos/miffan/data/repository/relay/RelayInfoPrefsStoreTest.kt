package me.ayuilos.miffan.data.repository.relay

import android.content.SharedPreferences
import me.rerere.workspace.relay.StoredRelayInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RelayInfoPrefsStoreTest {
    @Test fun roundTripsPerRelay() {
        val store = RelayInfoPrefsStore(MemoryPrefs())
        val info = StoredRelayInfo(1_700_000_000_000, """{"v":1,"edges":["104.17.5.5"]}""")
        store.write("https://relay.example", info)
        assertEquals(info, store.read("https://relay.example"))
        assertNull(store.read("https://other.example"))
    }

    @Test fun unreadableEntryIsIgnored() {
        val prefs = MemoryPrefs().apply { values["https://relay.example"] = "not json" }
        assertNull(RelayInfoPrefsStore(prefs).read("https://relay.example"))
    }
}

/** Just enough SharedPreferences for string values. */
internal class MemoryPrefs : SharedPreferences {
    val values = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = values
    override fun getString(key: String, defValue: String?): String? = values[key] as? String ?: defValue
    override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
    override fun getInt(key: String, defValue: Int): Int = values[key] as? Int ?: defValue
    override fun getLong(key: String, defValue: Long): Long = values[key] as? Long ?: defValue
    override fun getFloat(key: String, defValue: Float): Float = values[key] as? Float ?: defValue
    override fun getBoolean(key: String, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
    override fun contains(key: String): Boolean = key in values
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String?) = apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { pending[key] = values }
        override fun putInt(key: String, value: Int) = apply { pending[key] = value }
        override fun putLong(key: String, value: Long) = apply { pending[key] = value }
        override fun putFloat(key: String, value: Float) = apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { pending[key] = value }
        override fun remove(key: String) = apply { pending[key] = null }
        override fun clear() = apply { values.clear() }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() { pending.forEach { (k, v) -> if (v == null) values.remove(k) else values[k] = v } }
    }
}

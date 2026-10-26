package me.rerere.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchServiceOptionsFactoriesTest {
    @Test
    fun `every selectable search type has a factory that builds that type`() {
        assertEquals(SearchServiceOptions.TYPES.keys, SearchServiceOptions.FACTORIES.keys)
        SearchServiceOptions.FACTORIES.forEach { (type, create) ->
            assertTrue(type.isInstance(create()))
        }
    }
}

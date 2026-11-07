package me.ayuilos.miffan.data.db.fts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FtsIndexPlanTest {
    private fun row(node: String, text: String, message: String = "m-$node") = FtsRow(node, message, text)
    private fun stored(id: Long, row: FtsRow) = FtsStoredRow(id, row)

    /** Applies a plan to stored rows the way the SQL does, returning the rows left (ignoring rowids). */
    private fun apply(existing: List<FtsStoredRow>, plan: FtsPlan): List<FtsRow> =
        existing.filter { it.rowId !in plan.deleteRowIds }.map { it.row } + plan.inserts

    @Test fun unchangedConversationTouchesNothing() {
        val rows = listOf(row("a", "hello"), row("b", "world"))
        val plan = planFtsUpdate(rows.mapIndexed { i, r -> stored(i + 1L, r) }, rows)
        assertTrue(plan.deleteRowIds.isEmpty())
        assertTrue(plan.inserts.isEmpty())
    }

    @Test fun firstIndexInsertsEveryNodeInOrder() {
        val rows = listOf(row("a", "hello"), row("b", "world"))
        val plan = planFtsUpdate(emptyList(), rows)
        assertTrue(plan.deleteRowIds.isEmpty())
        assertEquals(rows, plan.inserts)
    }

    @Test fun appendedNodeIsTheOnlyInsert() {
        val old = listOf(row("a", "hello"), row("b", "world"))
        val plan = planFtsUpdate(old.mapIndexed { i, r -> stored(i + 1L, r) }, old + row("c", "new reply"))
        assertTrue(plan.deleteRowIds.isEmpty())
        assertEquals(listOf(row("c", "new reply")), plan.inserts)
    }

    @Test fun editedNodeIsReplacedAndOthersStay() {
        val old = listOf(row("a", "hello"), row("b", "world"))
        val plan = planFtsUpdate(old.mapIndexed { i, r -> stored(i + 1L, r) }, listOf(row("a", "hello"), row("b", "edited")))
        assertEquals(listOf(2L), plan.deleteRowIds)
        assertEquals(listOf(row("b", "edited")), plan.inserts)
    }

    @Test fun regeneratedMessageWithTheSameTextIdIsReplaced() {
        val old = listOf(row("a", "same text", message = "m1"))
        val plan = planFtsUpdate(listOf(stored(7, old.single())), listOf(row("a", "same text", message = "m2")))
        assertEquals(listOf(7L), plan.deleteRowIds)
        assertEquals(listOf(row("a", "same text", message = "m2")), plan.inserts)
    }

    @Test fun removedAndNowBlankNodesAreDeleted() {
        val old = listOf(row("a", "hello"), row("b", "world"), row("c", "gone"))
        // b became blank (no row desired), c was deleted from the conversation.
        val plan = planFtsUpdate(old.mapIndexed { i, r -> stored(i + 1L, r) }, listOf(row("a", "hello")))
        assertEquals(listOf(2L, 3L), plan.deleteRowIds)
        assertTrue(plan.inserts.isEmpty())
    }

    @Test fun duplicateLeftoverRowsOfANodeAreCleanedUp() {
        val r = row("a", "hello")
        val plan = planFtsUpdate(listOf(stored(1, r), stored(2, r)), listOf(r))
        assertEquals(listOf(2L), plan.deleteRowIds)
        assertTrue(plan.inserts.isEmpty())
    }

    @Test fun anyChangeLeavesExactlyTheDesiredRows() {
        val random = Random(42)
        repeat(200) {
            val nodes = (0 until random.nextInt(0, 12)).map { "n$it" }
            val old = nodes.filter { random.nextInt(4) != 0 }.map { row(it, "text ${random.nextInt(3)}", "m${random.nextInt(2)}") }
            val desired = (nodes + "extra").filter { random.nextInt(4) != 0 }.map { row(it, "text ${random.nextInt(3)}", "m${random.nextInt(2)}") }
            val stored = old.mapIndexed { i, r -> stored(i + 1L, r) }
            val result = apply(stored, planFtsUpdate(stored, desired))
            assertEquals(desired.toSet(), result.toSet())
            assertEquals(desired.size, result.size)
        }
    }
}

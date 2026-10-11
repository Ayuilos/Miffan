package me.ayuilos.miffan.data.model

import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class ChatOrganizationTest {
    private val a = Uuid.random()
    private val b = Uuid.random()
    private val c = Uuid.random()
    private val g1 = ChatGroup(name = "First", chatIds = listOf(a, b))
    private val g2 = ChatGroup(name = "Second", chatIds = listOf(c))
    private val organization = ChatOrganization(groups = listOf(g1, g2))

    @Test
    fun pinAppendsOnceAndUnpinKeepsMembership() {
        val pinned = organization.pin(b).pin(a)
        assertEquals(listOf(b, a), pinned.pinned)
        assertSame(pinned, pinned.pin(b))
        assertTrue(pinned.isPinned(a))
        assertFalse(pinned.isPinned(c))
        assertEquals(g1, pinned.groupOf(a))
        assertNull(pinned.groupOf(Uuid.random()))
        val unpinned = pinned.unpin(b)
        assertEquals(listOf(a), unpinned.pinned)
        assertEquals(organization.groups, unpinned.groups)
        assertSame(unpinned, unpinned.unpin(b))
        assertTrue(organization.pinned.isEmpty())
    }

    @Test
    fun moveRemovesOldMembershipAndAppendsWithoutChangingPins() {
        val pinned = organization.pin(a)
        val moved = pinned.moveToGroup(a, g2.id)
        assertEquals(listOf(b), moved.groups[0].chatIds)
        assertEquals(listOf(c, a), moved.groups[1].chatIds)
        assertEquals(pinned.pinned, moved.pinned)
        assertEquals(g2.id, moved.groupOf(a)?.id)
        assertEquals(listOf(a, b), organization.groups[0].chatIds)
        assertEquals(listOf(b, a), organization.moveToGroup(a, g1.id).groups[0].chatIds)
        assertSame(moved, moved.moveToGroup(a, g2.id))
    }

    @Test
    fun movingOutAndMissingTargetAreHandled() {
        assertSame(organization, organization.moveToGroup(a, Uuid.random()))
        val moved = organization.moveToGroup(a, null)
        assertNull(moved.groupOf(a))
        assertEquals(listOf(b), moved.groups.first().chatIds)
        assertSame(moved, moved.moveToGroup(a, null))
    }

    @Test
    fun createTrimsTruncatesAppendsAndCanMoveChat() {
        val id = Uuid.random()
        val created = organization.pin(a).createGroup("  ${"字".repeat(30)}  ", a, id)
        assertEquals(id, created.groups.last().id)
        assertEquals("字".repeat(24), created.groups.last().name)
        assertEquals(listOf(a), created.groups.last().chatIds)
        assertEquals(listOf(b), created.groups.first().chatIds)
        assertEquals(listOf(a), created.pinned)
        assertSame(organization, organization.createGroup(" \n\t "))
        val duplicateNames = organization.createGroup(" First ")
        assertEquals(listOf("First", "Second", "First"), duplicateNames.groups.map { it.name })
        assertTrue(duplicateNames.groups.last().chatIds.isEmpty())
    }

    @Test
    fun renameUsesNameRulesAndLeavesOtherDataAlone() {
        val renamed = organization.renameGroup(g1.id, "  Renamed  ")
        assertEquals("Renamed", renamed.groups.first().name)
        assertEquals(g1.chatIds, renamed.groups.first().chatIds)
        assertEquals(g2, renamed.groups.last())
        assertSame(organization, organization.renameGroup(g1.id, " \t "))
        assertSame(organization, organization.renameGroup(Uuid.random(), "New"))
        assertSame(organization, organization.renameGroup(g1.id, " First "))
        assertEquals(24, organization.renameGroup(g1.id, "a".repeat(25)).groups.first().name.length)
    }

    @Test
    fun moveGroupOnlySwapsAdjacentGroups() {
        val moved = organization.moveGroup(g1.id, 1)
        assertEquals(listOf(g2, g1), moved.groups)
        assertEquals(organization, moved.moveGroup(g1.id, -1))
        assertEquals(listOf(g2, g1), organization.moveGroup(g2.id, -1).groups)
        assertSame(organization, organization.moveGroup(g1.id, -1))
        assertSame(organization, organization.moveGroup(g2.id, 1))
        assertSame(organization, organization.moveGroup(Uuid.random(), 1))
        listOf(Int.MIN_VALUE, 0, 2, Int.MAX_VALUE).forEach {
            assertSame(organization, organization.moveGroup(g1.id, it))
        }
    }

    @Test
    fun deletingGroupUngroupsMembersAndKeepsPins() {
        val pinned = organization.pin(a)
        val deleted = pinned.deleteGroup(g1.id)
        assertEquals(listOf(g2), deleted.groups)
        assertNull(deleted.groupOf(a))
        assertEquals(listOf(a), deleted.pinned)
        assertSame(deleted, deleted.deleteGroup(g1.id))
        assertEquals(2, organization.groups.size)
    }

    @Test
    fun retainDropsDeletedChatsKeepsEmptyGroupsAndMigrationFlag() {
        val pinned = organization.pin(a).pin(c).copy(legacyPinsMigrated = true)
        val retained = pinned.retainChats(setOf(a))
        assertEquals(listOf(a), retained.pinned)
        assertEquals(listOf(a), retained.groups.first().chatIds)
        assertTrue(retained.groups.last().chatIds.isEmpty())
        assertTrue(retained.legacyPinsMigrated)
        assertSame(retained, retained.retainChats(setOf(a)))
        val empty = pinned.retainChats(emptySet())
        assertTrue(empty.pinned.isEmpty())
        assertEquals(listOf(g1.id, g2.id), empty.groups.map { it.id })
        assertTrue(empty.groups.all { it.chatIds.isEmpty() })
    }

    @Test
    fun everyOperationPreservesUniquenessEvenForImportedDuplicates() {
        val corrupt = ChatOrganization(
            pinned = listOf(a, a, b),
            groups = listOf(g1.copy(chatIds = listOf(a, a, b)), g2.copy(chatIds = listOf(a, c, c))),
        )
        val results = listOf(
            corrupt.pin(a), corrupt.pin(c), corrupt.unpin(b),
            corrupt.moveToGroup(a, g2.id), corrupt.moveToGroup(a, null),
            corrupt.moveToGroup(a, Uuid.random()), corrupt.createGroup("New", a),
            corrupt.createGroup(" "), corrupt.renameGroup(g1.id, "New"),
            corrupt.renameGroup(g1.id, " "), corrupt.moveGroup(g1.id, 1),
            corrupt.moveGroup(g1.id, -1), corrupt.moveGroup(g1.id, 0),
            corrupt.deleteGroup(g2.id), corrupt.deleteGroup(Uuid.random()),
            corrupt.retainChats(setOf(a, b, c)),
        )
        results.forEach { result ->
            assertEquals(result.pinned.distinct(), result.pinned)
            val members = result.groups.flatMap { it.chatIds }
            assertEquals(members.distinct(), members)
        }
        assertEquals(listOf(a, b), results.first().groups.first().chatIds)
        assertEquals(listOf(c), results.first().groups.last().chatIds)
        assertEquals(3, corrupt.pinned.size)
    }

    @Test
    fun duplicateGroupIdsStillCannotCreateDuplicateMembership() {
        val duplicateIds = ChatOrganization(groups = listOf(g1, g1.copy(chatIds = listOf(c))))
        val moved = duplicateIds.moveToGroup(b, g1.id)
        assertEquals(1, moved.groups.flatMap { it.chatIds }.count { it == b })
        val created = organization.createGroup("Duplicate id", a, g1.id)
        assertEquals(1, created.groups.flatMap { it.chatIds }.count { it == a })
    }
}

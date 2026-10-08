package me.ayuilos.miffan.data.audit

import androidx.sqlite.db.SupportSQLiteDatabase
import io.mockk.every
import io.mockk.mockk
import me.ayuilos.miffan.data.db.dao.AUDIT_TRIM_SQL
import me.ayuilos.miffan.data.db.migrations.Migration_30_31
import me.ayuilos.miffan.data.model.Assistant
import me.ayuilos.miffan.data.model.Conversation
import me.ayuilos.miffan.data.model.withWorkspaceShellApproval
import me.ayuilos.miffan.service.approvePendingWorkspaceShellTools
import me.ayuilos.miffan.service.settleUnfinishedTools
import me.ayuilos.miffan.testutils.workspaceTestResources
import me.ayuilos.miffan.utils.JsonInstant
import me.rerere.ai.core.Tool
import me.rerere.ai.ui.*
import org.junit.Assert.*
import org.junit.Test
import java.sql.DriverManager
import kotlin.uuid.Uuid

class AuditTest {
    @Test fun computerObservationAndForegroundNeverGetAutomaticProvenance() {
        val args = kotlinx.serialization.json.JsonObject(emptyMap())
        for (name in listOf("get_desktop_state", "read_guide", "bring_to_front", "start")) {
            assertNull(me.ayuilos.miffan.data.ai.tools.computerAutoApprovedBy(name, args, false))
        }
        assertEquals(ToolDecisionVia.NO_ASK_SETTING, me.ayuilos.miffan.data.ai.tools.computerAutoApprovedBy("click", args, false))
        assertNull(me.ayuilos.miffan.data.ai.tools.computerAutoApprovedBy("click", args, true))
        val foreground = kotlinx.serialization.json.Json.parseToJsonElement("""{"delivery_mode":"foreground"}""")
        assertNull(me.ayuilos.miffan.data.ai.tools.computerAutoApprovedBy("click", foreground, false))
    }

    @Test fun computerTargetsAreCapturedForAuditWithoutChangingApproval() {
        val target = WorkspaceToolTargetSnapshot("partner", "rev", "work", null, "remote", remoteHostId = "host", workspaceName = "Work", remoteHostName = "Mac")
        val call = UIMessagePart.Tool("click", "computer_click", "{}")
        val definition = Tool("computer_click", "", workspaceTarget = target, execute = { emptyList() })
        val bound = me.ayuilos.miffan.data.ai.tools.captureWorkspaceToolTarget(call, definition)
        assertEquals(target, bound.workspaceTarget)
        assertEquals(call.approvalState, bound.approvalState)
    }

    private val resources = workspaceTestResources("values")
    private fun call() = UIMessagePart.Tool("call", "workspace_shell", """{"command":"uname -a"}""")
    private fun pending() = call().waitForApproval(100L)
    private fun row(tool: UIMessagePart.Tool) = approvalAuditRow(Uuid.random(), Uuid.random(), Uuid.random(), tool) { id, args -> resources.getString(id, *args.toTypedArray()) }

    @Test fun oldJsonRoundTripAndMergeKeepRecords() {
        val old = """{"type":"tool","toolCallId":"old","toolName":"workspace_shell","input":"{}"}"""
        val tool = JsonInstant.decodeFromString<UIMessagePart>(old) as UIMessagePart.Tool
        assertNull(tool.approvalRecord)
        assertEquals(tool, JsonInstant.decodeFromString<UIMessagePart>(JsonInstant.encodeToString<UIMessagePart>(tool)))
        val decided = pending().recordCardDecision(ToolApprovalState.Approved, ToolDecisionVia.CARD, 200)
        assertEquals(decided, JsonInstant.decodeFromString<UIMessagePart>(JsonInstant.encodeToString<UIMessagePart>(decided)))
        assertEquals(decided.approvalRecord, decided.merge(call()).approvalRecord)
    }

    @Test fun pendingAndEveryCardDecisionHaveTimesAndOrigin() {
        assertEquals(ToolApprovalRecord(requestedAt = 100), pending().approvalRecord)
        assertNull(row(pending()))
        val states = listOf(ToolApprovalState.Approved, ToolApprovalState.Denied("no"), ToolApprovalState.Answered("yes"))
        val decisions = listOf(ToolDecision.ALLOWED, ToolDecision.DECLINED, ToolDecision.ANSWERED)
        for (via in listOf(ToolDecisionVia.CARD, ToolDecisionVia.PARTNER_SCREEN)) states.zip(decisions).forEach { (state, decision) ->
            val result = pending().recordCardDecision(state, via, 200)
            assertEquals(ToolApprovalRecord(100, 200, decision, via), result.approvalRecord)
            assertEquals(result.approvalRecord, result.recordDecision(ToolDecision.CANCELLED, ToolDecisionVia.STOP, 300).approvalRecord)
        }
    }

    @Test fun chatReplyAndStopRecordOnlyGatedUnsettledCalls() {
        for (replied in listOf(true, false)) {
            val message = UIMessage.assistant("").copy(parts = listOf(pending(), call().copy(toolCallId = "observation", toolName = "computer_get_desktop_state")))
            val result = message.settleUnfinishedTools(replied).getTools()
            assertEquals(if (replied) ToolDecision.REPLIED_IN_CHAT else ToolDecision.CANCELLED, result[0].approvalRecord?.decision)
            assertEquals(if (replied) ToolDecisionVia.CHAT_REPLY else ToolDecisionVia.STOP, result[0].approvalRecord?.via)
            assertEquals(100L, result[0].approvalRecord?.requestedAt)
            assertNull(result[1].approvalRecord)
        }
    }

    @Test fun automaticReasonsAndObservationExclusion() {
        for (via in listOf(ToolDecisionVia.STANDING_ALWAYS_ALLOW, ToolDecisionVia.NO_ASK_SETTING)) {
            val definition = Tool("workspace_shell", "", autoApprovedBy = { via }, execute = { emptyList() })
            val result = call().recordAutomaticApproval(definition, 200)
            assertEquals(ToolApprovalRecord(null, 200, ToolDecision.AUTO_ALLOWED, via), result.approvalRecord)
            assertNotNull(row(result))
        }
        assertNull(row(call().recordAutomaticApproval(Tool("observe", "", execute = { emptyList() }), 200)))
        assertEquals(pending(), pending().recordAutomaticApproval(Tool("shell", "", autoApprovedBy = { ToolDecisionVia.NO_ASK_SETTING }, execute = { emptyList() }), 200))
    }

    @Test fun alwaysAllowRecordsTargetValidationAndSettingOrigin() {
        val assistant = Assistant()
        val target = WorkspaceToolTargetSnapshot(assistant.id.toString(), "legacy", Uuid.random().toString(), null, "local", workspaceName = "Work")
        val message = UIMessage.assistant("").copy(parts = listOf(pending().copy(workspaceTarget = target)))
        val conversation = Conversation.linear(assistantId = assistant.id, messages = listOf(message))
        val allowed = conversation.approvePendingWorkspaceShellTools(target, true).currentMessages.last().getTools().single()
        assertEquals(ToolDecision.ALLOWED, allowed.approvalRecord?.decision)
        assertEquals(ToolDecisionVia.ALWAYS_ALLOW, allowed.approvalRecord?.via)
        val denied = conversation.approvePendingWorkspaceShellTools(target, false).currentMessages.last().getTools().single()
        assertEquals(ToolDecision.DECLINED, denied.approvalRecord?.decision)
        val bound = assistant.copy(workspaceId = Uuid.parse(target.workspaceId))
        assertEquals(ToolDecisionVia.NO_ASK_SETTING, bound.withWorkspaceShellApproval(false, target).workspaceShellApprovalVia)
    }

    @Test fun rowsUseHistoricalTargetAndUnknownEnumsAreSafe() {
        val target = WorkspaceToolTargetSnapshot("partner", "rev", "work", null, "remote", remoteHostId = "host", workspaceName = "Work", remoteHostName = "Old name")
        val tool = pending().copy(workspaceTarget = target).recordCardDecision(ToolApprovalState.Denied(), ToolDecisionVia.CARD, 200)
        val row = requireNotNull(row(tool))
        assertEquals("Run command: uname -a", row.summary)
        assertEquals("host", row.hostId); assertEquals("Old name", row.hostName)
        assertEquals(100L, row.requestedAt); assertEquals(200L, row.at)
        assertNull(row.copy(kind = "FUTURE", decision = "FUTURE", via = "FUTURE").toEvent().decision)
        assertNull(row.copy(kind = "FUTURE").toEvent().kind)
        assertNull(row.copy(via = "FUTURE").toEvent().via)
    }

    @Test fun sqliteMigrationDeduplicatesAndRetainsNewestFiveThousand() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { connection ->
            val db = mockk<SupportSQLiteDatabase>()
            every { db.execSQL(any()) } answers { connection.createStatement().use { it.execute(firstArg<String>()) }; Unit }
            Migration_30_31.migrate(db)
            connection.autoCommit = false
            connection.prepareStatement("INSERT OR IGNORE INTO audit_event(id, at, kind, tool_call_id, summary) VALUES (?, ?, 'APPROVAL', ?, 'test')").use { insert ->
                for (i in 0..5001) {
                    insert.setString(1, "row-$i"); insert.setLong(2, i.toLong()); insert.setString(3, "call-$i"); insert.executeUpdate()
                }
                insert.setString(1, "duplicate"); insert.setLong(2, 99999); insert.setString(3, "call-0"); assertEquals(0, insert.executeUpdate())
            }
            connection.createStatement().use { statement ->
                statement.execute(AUDIT_TRIM_SQL)
                statement.executeQuery("SELECT COUNT(*), MIN(at), MAX(at) FROM audit_event").use { result ->
                    assertTrue(result.next()); assertEquals(5000, result.getInt(1)); assertEquals(2L, result.getLong(2)); assertEquals(5001L, result.getLong(3))
                }
                statement.execute("INSERT INTO audit_event(id, at, kind, summary) VALUES ('s1', 10, 'SCREEN_TAKEN_OVER', 'screen'), ('s2', 10, 'SCREEN_TAKEN_OVER', 'screen')")
                statement.executeQuery("SELECT COUNT(*) FROM audit_event WHERE kind = 'SCREEN_TAKEN_OVER'").use { assertTrue(it.next()); assertEquals(2, it.getInt(1)) }
            }
        }
    }
}

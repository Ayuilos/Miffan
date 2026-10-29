package me.ayuilos.miffan.ui.im.thread

import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.WorkspaceToolTargetSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadComputerRecordTest {
    private val space = WorkspaceToolTargetSnapshot("a", "r", "ws", null, WorkspaceEntity.KIND_SPACE, workspaceName = "box", remoteHostId = "h")
    private val ok = """{"ok":true}"""
    private val failed = """{"status":"error","reason":"no window"}"""

    private fun computer(name: String, id: String = name, output: String? = ok, input: String = "{}", state: ToolApprovalState = ToolApprovalState.Auto) =
        UIMessagePart.Tool(id, "computer_$name", input, output?.let { listOf(UIMessagePart.Text(it)) }.orEmpty(), state)

    private fun boxTool(name: String, id: String = name, input: String = "{}", output: String? = ok) =
        UIMessagePart.Tool(id, name, input, output?.let { listOf(UIMessagePart.Text(it)) }.orEmpty(), workspaceTarget = space)

    private fun List<ComputerRecordStep>.ids() = map { it.tool.toolCallId }

    @Test
    fun mergesComputerActionsAndMiniBoxStepsInTheOrderTheyWereTaken() {
        val steps = computerRecordSteps(listOf(
            boxTool("workspace_write_file", "write", """{"path":"poem.md"}"""),
            computer("get_desktop_state", "look"),
            computer("click", "click"),
            boxTool("give_to_owner", "give", """{"path":"poem.md"}"""),
        ), live = false)
        // Looking is no step; the file and handoff steps are worded by threadSteps.
        assertEquals(listOf("write", "click", "give"), steps.ids())
        assertEquals(R.string.im_step_write_file, steps[0].step?.label)
        assertNull(steps[1].step)
        assertEquals(R.string.im_step_hand_over, steps[2].step?.label)
    }

    @Test
    fun aFailedStepFollowedByASuccessfulOneWasWorkedAround() {
        val steps = computerRecordSteps(listOf(
            computer("click", "a", failed),
            computer("click", "b"),
            computer("type_text", "c", failed),
            computer("press_key", "d"),
        ), live = false)
        assertEquals(listOf(true, false, true, false), steps.map { it.workedAround })
        assertNull(steps.unresolvedFailure())
    }

    @Test
    fun aFailureNothingSucceededAfterDecidesTheReply() {
        val steps = computerRecordSteps(listOf(
            computer("click", "a"),
            computer("click", "b", failed),
            computer("click", "c", failed),
        ), live = false)
        assertEquals(listOf(false, false, false), steps.map { it.workedAround })
        assertEquals("c", steps.unresolvedFailure()?.tool?.toolCallId)
    }

    @Test
    fun aLaterStepStillRunningHasNotWorkedAroundYet() {
        val steps = computerRecordSteps(listOf(
            computer("click", "a", failed),
            computer("click", "b", output = null),
        ), live = true)
        assertTrue(steps[1].running)
        assertFalse(steps[0].workedAround)
        // Not over, so no failure either: the failure of a reply is judged by its last step.
        assertNull(steps.unresolvedFailure())
    }

    @Test
    fun aMiniBoxCommandThatFailedCountsLikeAComputerAction() {
        val steps = computerRecordSteps(listOf(
            boxTool("workspace_shell", "run", output = """{"exitCode":1,"stdout":"","stderr":"no","timedOut":false}"""),
            boxTool("workspace_shell", "again", output = """{"exitCode":0,"stdout":"ok","stderr":"","timedOut":false}"""),
        ), live = false)
        assertEquals(listOf(true, false), steps.map { it.failed })
        assertTrue(steps[0].workedAround)
        assertNull(steps.unresolvedFailure())
    }

    @Test
    fun aTakeoverOrUnconfirmedActionIsNoFailure() {
        val steps = computerRecordSteps(listOf(
            computer("click", "a", """{"status":"refused"}"""),
            computer("click", "b", """{"status":"unconfirmed"}"""),
        ), live = false)
        assertEquals(listOf(false, false), steps.map { it.failed })
        assertEquals(listOf(me.ayuilos.miffan.ui.components.message.ComputerToolStatus.REFUSED,
            me.ayuilos.miffan.ui.components.message.ComputerToolStatus.UNCONFIRMED), steps.map { it.note })
        assertNull(steps.unresolvedFailure())
    }

    @Test
    fun aFailedLookupStillCountsAndDeclinedOrUnrunCallsLeaveNothing() {
        val steps = computerRecordSteps(listOf(
            computer("get_desktop_state", "look", failed),
            computer("click", "denied", output = null, state = ToolApprovalState.Denied("no")),
            computer("click", "never", output = null),
        ), live = false)
        assertEquals(listOf("look"), steps.ids())
        assertEquals("look", steps.unresolvedFailure()?.tool?.toolCallId)
    }

    @Test
    fun onlyLookingLeavesNoStepsAtAll() {
        assertTrue(computerRecordSteps(listOf(computer("get_desktop_state"), computer("list_windows")), live = false).isEmpty())
    }

    @Test
    fun whileLiveTheCallWaitingForTheOwnerIsTheCurrentStep() {
        val steps = computerRecordSteps(listOf(
            computer("click", "a"),
            computer("click", "ask", output = null, state = ToolApprovalState.Pending),
        ), live = true)
        assertEquals("ask", steps.currentStep()?.tool?.toolCallId)
        assertTrue(steps.last().running)
    }

    @Test
    fun theReplyIsLiveWhileItsLatestThingIsComputerWork() {
        val click = computer("click")
        assertTrue(computerWorkIsLive(listOf(UIMessagePart.Text("好"), click), streaming = true))
        assertTrue(computerWorkIsLive(listOf(click, UIMessagePart.Reasoning("…")), streaming = true))
        assertTrue(computerWorkIsLive(listOf(boxTool("workspace_shell")), streaming = true))
        assertFalse(computerWorkIsLive(listOf(click, UIMessagePart.Text("做完了")), streaming = true))
        assertFalse(computerWorkIsLive(listOf(click), streaming = false))
        assertFalse(computerWorkIsLive(listOf(click, UIMessagePart.Tool("s", "search_web", "{}", listOf(UIMessagePart.Text(ok)))), streaming = true))
    }

    @Test
    fun theReplyBlockHoldsMiniBoxStepsOnlyWhenThereIsARecordToPutThemIn() {
        val parts = listOf(boxTool("workspace_read_file", "read"), boxTool("workspace_shell", "run"))
        // Light work alone stays out of easy mode; with a computer action, or enough steps, it joins the one card.
        assertTrue(threadReplyBlocks(parts, recordSteps = false).isEmpty())
        assertEquals(listOf("read", "run"), (threadReplyBlocks(parts, recordSteps = true).single() as ThreadReplyBlock.Computer).tools.map { it.toolCallId })
        val withClick = threadReplyBlocks(parts + computer("click", "c"), recordSteps = false).single() as ThreadReplyBlock.Computer
        assertEquals(listOf("read", "run", "c"), withClick.tools.map { it.toolCallId })
    }

    @Test
    fun aQuestionWaitingInAStreamingReplyJoinsTheComputerCard() {
        val pending = computer("click", "ask", output = null, state = ToolApprovalState.Pending)
        val parts = listOf(computer("click", "a"), pending)
        assertEquals(listOf("a", "ask"), (threadReplyBlocks(parts, recordSteps = false, streaming = true).single() as ThreadReplyBlock.Computer).tools.map { it.toolCallId })
        assertEquals(2, threadReplyBlocks(parts, recordSteps = false, streaming = false).size)
    }

    @Test
    fun pointsWhereTheActionLandsOnThePictureItWasGiven() {
        val click = computer("click", input = """{"x":480,"y":270}""")
        assertEquals(.25f to .25f, computerPointer(click, 1920f, 1080f))
        assertNull(computerPointer(computer("click", input = """{"x":3000,"y":10}"""), 1920f, 1080f))
        assertNull(computerPointer(computer("click", input = """{"x":-1,"y":10}"""), 1920f, 1080f))
        assertNull(computerPointer(computer("click", input = """{"element_token":"1:2"}"""), 1920f, 1080f))
        assertNull(computerPointer(computer("click", input = """{"x":"left","y":"top"}"""), 1920f, 1080f))
        assertNull(computerPointer(click, 0f, 0f))
        assertTrue(click.hasPointer())
        assertTrue(click.isClick())
        assertFalse(computer("type_text", input = """{"text":"猫"}""").hasPointer())
    }

    @Test
    fun theFirstShotBeforeAnActionIsWhatItsPositionsMean() {
        val shot = { url: String -> listOf(UIMessagePart.Text(ok), UIMessagePart.Image(url)) }
        val first = computer("get_desktop_state", "first").copy(output = shot("one.png"))
        val second = computer("click", "second").copy(output = shot("two.png"))
        val third = computer("click", "third", output = null)
        val tools = listOf(first, second, third)
        assertEquals("two.png", screenshotBefore(tools, third))
        // An action with a picture of its own shows the result, so its positions do not apply to anything.
        assertNull(screenshotBefore(tools, second))
    }

    @Test
    fun readsWhereAHandedFileLanded() {
        val file = requireNotNull(handedFile(boxTool("give_to_owner", "give", """{"path":"poem.md"}""",
            """{"computer":"CachyOS","copied":["~/Miffan Partners/米饭/poem.md"]}""")))
        assertEquals("poem.md", file.name)
        assertEquals("~/Miffan Partners/米饭", file.folder)
        assertEquals("~/Miffan Partners/米饭/poem.md", file.hostPath)
        assertEquals("CachyOS", file.computer)
        assertEquals(1, file.count)
        assertEquals("ws", file.workspaceId)
        assertEquals("poem.md", file.sharedPath)
    }

    @Test
    fun aRenamedCopyIsStillTheFileInTheSharedFolder() {
        val file = requireNotNull(handedFile(boxTool("give_to_owner", "give", """{"path":"/home/cua/Desktop/Shared/docs/poem.md"}""",
            """{"computer":"C","copied":["~/P/x/poem (2).md"]}""")))
        assertEquals("docs/poem.md", file.sharedPath)
    }

    @Test
    fun aFolderCannotBeOpenedOnThePhoneAsOneFile() {
        val file = requireNotNull(handedFile(boxTool("give_to_owner", "give", """{"path":"photos"}""",
            """{"computer":"C","copied":["~/P/x/photos/a.jpg","~/P/x/photos/b.jpg"]}""")))
        assertEquals(2, file.count)
        assertEquals("~/P/x/photos", file.hostPath)
        assertEquals("photos", file.name)
        assertNull(file.sharedPath)
        // A single file inside a folder is not the folder's name either.
        val one = requireNotNull(handedFile(boxTool("give_to_owner", "give", """{"path":"photos"}""",
            """{"computer":"C","copied":["~/P/x/photos/a.jpg"]}""")))
        assertNull(one.sharedPath)
    }

    @Test
    fun nothingHandedOverMakesNoFileCard() {
        assertNull(handedFile(boxTool("give_to_owner", output = """{"error":"Not inside the Shared folder."}""")))
        assertNull(handedFile(boxTool("give_to_owner", output = """{"computer":"C","copied":[]}""")))
        assertNull(handedFile(boxTool("give_to_owner", output = null)))
        assertEquals(1, handedFiles(listOf(UIMessagePart.Text("好"), boxTool("give_to_owner", output = """{"computer":"C","copied":["~/a.txt"]}"""),
            boxTool("workspace_shell"))).size)
        assertNotNull(handedFiles(listOf(boxTool("give_to_owner", output = """{"computer":"C","copied":["~/a.txt"]}"""))).single().folder)
    }

    @Test
    fun findsTheCommonFolderOfSeveralFiles() {
        assertEquals("~/P/x/photos", commonFolder(listOf("~/P/x/photos/a.jpg", "~/P/x/photos/sub/b.jpg")))
        assertEquals("~/P", commonFolder(listOf("~/P/a.txt", "~/P/x/b.txt")))
    }
}

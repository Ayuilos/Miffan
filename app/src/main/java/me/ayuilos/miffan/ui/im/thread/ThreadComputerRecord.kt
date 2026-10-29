package me.ayuilos.miffan.ui.im.thread

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import me.ayuilos.miffan.data.ai.tools.COMPUTER_TOOL_PREFIX
import me.ayuilos.miffan.data.ai.tools.SPACE_HANDOFF_TOOL
import me.ayuilos.miffan.data.ai.tools.spaceSharedPath
import me.ayuilos.miffan.data.db.entity.WorkspaceEntity
import me.ayuilos.miffan.ui.components.message.ComputerToolStatus
import me.ayuilos.miffan.ui.components.message.computerToolNeedsForegroundWarning
import me.ayuilos.miffan.ui.components.message.computerToolStatus
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart

private val recordJson = Json { ignoreUnknownKeys = true }

/**
 * One line of a reply's computer record: a computer action, or a step the partner took inside its own mini box
 * (files, commands). [step] is null for computer actions, which are worded by [computerActionText].
 */
internal data class ComputerRecordStep(
    val tool: UIMessagePart.Tool,
    val step: ThreadStep?,
    val failed: Boolean,
    val running: Boolean,
    /** Failed, but a later step worked: the partner found another way, so it is no failure of the reply. */
    val workedAround: Boolean = false,
    /** The owner took over, or the result could not be confirmed; a note under the line, not a failure. */
    val note: ComputerToolStatus? = null,
)

/** A step inside the partner's own mini box: the file and command tools that [threadSteps] words, aimed at a Space. */
internal fun UIMessagePart.Tool.isMiniBoxStep(): Boolean =
    !isComputerTool() && workspaceTarget?.kind == WorkspaceEntity.KIND_SPACE && threadSteps(listOf(this)).isNotEmpty()

internal fun UIMessagePart.Tool.inMiniBox(): Boolean = workspaceTarget?.kind == WorkspaceEntity.KIND_SPACE

/**
 * The steps of one reply's computer work, in the order they were taken. Looking (screenshots, lists) is no step
 * unless it failed; calls that were declined or never ran leave no line. While [live] the call still running,
 * or waiting for the owner, is the last line.
 */
internal fun computerRecordSteps(tools: List<UIMessagePart.Tool>, live: Boolean): List<ComputerRecordStep> {
    val steps = tools.mapNotNull { tool ->
        if (tool.approvalState is ToolApprovalState.Denied) return@mapNotNull null
        val waiting = !tool.isExecuted
        if (waiting && !live) return@mapNotNull null
        if (tool.isComputerTool()) {
            val status = computerToolStatus(tool.output)
            val failed = status == ComputerToolStatus.ERROR
            val lookup = tool.toolName.removePrefix(COMPUTER_TOOL_PREFIX) in computerObservationNames
            if (lookup && !failed) return@mapNotNull null
            ComputerRecordStep(tool, null, failed, running = waiting,
                note = status?.takeUnless { failed })
        } else threadSteps(listOf(tool)).singleOrNull()?.let {
            ComputerRecordStep(tool, it, it.failed, running = waiting)
        }
    }
    return steps.markWorkedAround()
}

/**
 * A failed step is worked around when a later step went through (finished and did not fail). A later step still
 * running does not count yet. So only a failure with nothing succeeding after it stays a failure.
 */
internal fun List<ComputerRecordStep>.markWorkedAround(): List<ComputerRecordStep> = mapIndexed { index, step ->
    step.copy(workedAround = step.failed && drop(index + 1).any { !it.failed && !it.running })
}

/**
 * Whether the reply's computer work ended in failure: its last step failed, so nothing worked after it.
 * Earlier failures that a later step recovered from never make the reply a failure.
 */
internal fun List<ComputerRecordStep>.unresolvedFailure(): ComputerRecordStep? =
    lastOrNull()?.takeIf { it.failed && !it.workedAround }

/** The last thing the reply asked to do was turned down by the owner; the record ends with that, not with a failure. */
internal fun declinedAtEnd(tools: List<UIMessagePart.Tool>): UIMessagePart.Tool? =
    tools.lastOrNull()?.takeIf { it.approvalState is ToolApprovalState.Denied }

/** How the record ends badly: its last step failed, unless the owner said no after that, which is no failure. */
internal fun recordFailure(steps: List<ComputerRecordStep>, tools: List<UIMessagePart.Tool>): ComputerRecordStep? =
    if (declinedAtEnd(tools) != null) null else steps.unresolvedFailure()

/** The computer actions of a reply that wait for the owner, in the order the partner asked. */
internal fun pendingComputerActions(tools: List<UIMessagePart.Tool>): List<UIMessagePart.Tool> =
    tools.filter { it.isComputerTool() && it.approvalState is ToolApprovalState.Pending }

/** The action would bring a window to the front over the owner's own work; in the partner's mini box it cannot. */
internal fun UIMessagePart.Tool.interruptsOwner(): Boolean =
    !inMiniBox() && computerToolNeedsForegroundWarning(toolName, inputAsJson())

/** What the live card is on: the step still running, else the latest one. */
internal fun List<ComputerRecordStep>.currentStep(): ComputerRecordStep? = lastOrNull { it.running } ?: lastOrNull()

/** The reply is still at its computer work: its latest thing that counts is a computer or mini box call, not text. */
internal fun computerWorkIsLive(parts: List<UIMessagePart>, streaming: Boolean): Boolean {
    if (!streaming) return false
    val last = parts.lastOrNull { part ->
        part is UIMessagePart.Tool || part is UIMessagePart.ServerTool || part is UIMessagePart.Text && part.text.isNotBlank()
    }
    return last is UIMessagePart.Tool && (last.isComputerTool() || last.isMiniBoxStep())
}

/** Where a computer action points on the screenshot it was given, as fractions of its width and height. */
internal fun computerPointer(tool: UIMessagePart.Tool, width: Float, height: Float): Pair<Float, Float>? {
    if (!tool.isComputerTool() || width <= 0f || height <= 0f) return null
    val input = tool.inputAsJson() as? JsonObject ?: return null
    fun number(key: String) = (input[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.toFloat()
    val x = number("x") ?: return null
    val y = number("y") ?: return null
    // Outside the picture the numbers mean something else (a window, a zoomed view): no marker.
    if (x < 0f || y < 0f || x > width || y > height) return null
    return x / width to y / height
}

internal fun UIMessagePart.Tool.hasPointer(): Boolean {
    val input = inputAsJson() as? JsonObject ?: return false
    return isComputerTool() && "x" in input && "y" in input
}

/** Whether a pending action acts by clicking (the highlight then says "click here"). */
internal fun UIMessagePart.Tool.isClick(): Boolean =
    toolName.removePrefix(COMPUTER_TOOL_PREFIX) in setOf("click", "double_click", "right_click")

/**
 * The last screenshot the computer tools gave before [tool], i.e. what the partner saw when it chose that action
 * (its x,y are positions in this picture); null when [tool] has a picture of its own, which shows the result.
 */
internal fun screenshotBefore(tools: List<UIMessagePart.Tool>, tool: UIMessagePart.Tool): String? {
    if (tool.output.any { it is UIMessagePart.Image }) return null
    val before = tools.takeWhile { it !== tool }
    return before.flatMap { it.output }.filterIsInstance<UIMessagePart.Image>().lastOrNull()?.url
}

/** A file or folder the partner gave the owner from its mini box, where it landed, and how to open it on the phone. */
internal data class HandedFile(
    val toolCallId: String,
    val computer: String,
    /** Where it is on the owner's computer, as the tool reported ("~/Miffan Partners/Rice/poem.md"). */
    val hostPath: String,
    val name: String,
    /** The folder it is in. */
    val folder: String,
    /** More than one file came (a folder was handed over); [hostPath] is then their common folder. */
    val count: Int,
    val workspaceId: String?,
    /** Its path under the mini box's Shared folder, when it is one plain file the phone can fetch from there. */
    val sharedPath: String?,
)

internal fun handedFiles(parts: List<UIMessagePart>): List<HandedFile> = parts.filterIsInstance<UIMessagePart.Tool>()
    .filter { it.toolName == SPACE_HANDOFF_TOOL && it.approvalState !is ToolApprovalState.Denied }
    .mapNotNull { handedFile(it) }

internal fun handedFile(tool: UIMessagePart.Tool): HandedFile? {
    val text = tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text ?: return null
    val result = runCatching { recordJson.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    if ("error" in result) return null
    val copied = (result["copied"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
        .orEmpty()
    if (copied.isEmpty()) return null
    val single = copied.size == 1
    val hostPath = if (single) copied.single() else commonFolder(copied)
    val given = (tool.inputAsJson() as? JsonObject)?.get("path")
        ?.let { (it as? JsonPrimitive)?.contentOrNull }?.let(::spaceSharedPath)
    return HandedFile(
        toolCallId = tool.toolCallId,
        computer = (result["computer"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        hostPath = hostPath,
        name = hostPath.substringAfterLast('/'),
        folder = hostPath.substringBeforeLast('/', ""),
        count = copied.size,
        workspaceId = tool.workspaceTarget?.workspaceId,
        sharedPath = given?.takeIf { single && sameFile(it.substringAfterLast('/'), copied.single().substringAfterLast('/')) },
    )
}

/** The deepest folder all of [paths] are in. */
internal fun commonFolder(paths: List<String>): String {
    val folders = paths.map { it.split('/').dropLast(1) }
    val first = folders.first()
    val shared = first.indices.takeWhile { index -> folders.all { it.size > index && it[index] == first[index] } }.size
    return first.take(shared).joinToString("/")
}

/** [a] and [b] are one file name, allowing for the " (2)" the helper adds when the name was taken. */
private fun sameFile(a: String, b: String): Boolean = withoutCopyMark(a) == withoutCopyMark(b)

private fun withoutCopyMark(name: String): String {
    val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
    return name.substring(0, dot).replace(Regex(" \\(\\d+\\)$"), "") + name.substring(dot)
}

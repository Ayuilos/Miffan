package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import me.ayuilos.miffan.R
import me.ayuilos.miffan.data.ai.tools.EditableParamKind
import me.ayuilos.miffan.data.ai.tools.EditableParamSpec
import me.ayuilos.miffan.data.ai.tools.editableApprovalFields
import me.ayuilos.miffan.data.ai.tools.isValidEditedToolInput
import me.ayuilos.miffan.ui.im.ImActionCard
import me.ayuilos.miffan.ui.im.ImCardBlock
import me.ayuilos.miffan.ui.im.ImCardBlockDivider
import me.ayuilos.miffan.ui.im.ImCardButton
import me.ayuilos.miffan.ui.im.ImCardButtons
import me.ayuilos.miffan.ui.im.ImCardOutlinedButton
import me.ayuilos.miffan.ui.im.ImCardStatus
import me.ayuilos.miffan.ui.im.ImFoldedLine
import me.ayuilos.miffan.ui.im.ImOption
import me.ayuilos.miffan.ui.im.ImOptionList
import me.ayuilos.miffan.ui.im.ImTag
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import me.rerere.ai.ui.isRepliedInChat
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Add01
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.BubbleChatQuestion
import me.rerere.hugeicons.stroke.Clock02
import me.rerere.hugeicons.stroke.Tick01
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Unified state store for in-progress interactive cards (both `ask_user` question drafts and
 * inline-editable tool approval cards). Drafts survive recomposition and screen navigation,
 * and are cleared as soon as the tool call leaves `Pending` or is submitted/approved.
 */
internal object PrimitiveCardStateStore {
    private val questionDrafts = mutableMapOf<String, QuestionDraft>()
    private val approvalDrafts = mutableMapOf<String, ApprovalEditDraft>()

    fun questionDraft(toolCallId: String, questions: List<UserQuestion>): QuestionDraft =
        questionDrafts.getOrPut(toolCallId) { QuestionDraft(questions) }

    fun approvalDraft(toolCallId: String, toolName: String, originalInput: JsonObject): ApprovalEditDraft =
        approvalDrafts.getOrPut(toolCallId) { ApprovalEditDraft(toolName, originalInput) }

    fun remove(toolCallId: String) {
        questionDrafts.remove(toolCallId)
        approvalDrafts.remove(toolCallId)
    }

    fun clearAll() {
        questionDrafts.clear()
        approvalDrafts.clear()
    }
}

/**
 * In-progress inline parameter edits for a pending tool approval card.
 */
internal class ApprovalEditDraft(
    val toolName: String,
    val originalInput: JsonObject,
    val specs: List<EditableParamSpec> = editableApprovalFields(toolName, originalInput),
) {
    var editing by mutableStateOf(false)
    private val editedValues = mutableStateMapOf<String, String>()

    init {
        specs.forEach { spec ->
            val raw = (originalInput[spec.key] as? JsonPrimitive)?.contentOrNull
            if (raw != null) {
                editedValues[spec.key] = raw
            }
        }
    }

    fun value(key: String): String =
        editedValues[key] ?: (originalInput[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    fun set(key: String, value: String) {
        editedValues[key] = value
    }

    fun booleanValue(key: String, default: Boolean = false): Boolean =
        editedValues[key]?.lowercase(Locale.ROOT)?.toBooleanStrictOrNull()
            ?: (originalInput[key] as? JsonPrimitive)?.booleanOrNull
            ?: default

    fun setBoolean(key: String, value: Boolean) {
        editedValues[key] = value.toString()
    }

    fun reset() {
        editedValues.clear()
        specs.forEach { spec ->
            val raw = (originalInput[spec.key] as? JsonPrimitive)?.contentOrNull
            if (raw != null) {
                editedValues[spec.key] = raw
            }
        }
        editing = false
    }

    fun buildEditedJson(): JsonObject {
        if (specs.isEmpty()) return originalInput
        val mutable = originalInput.toMutableMap()
        specs.forEach { spec ->
            val rawStr = editedValues[spec.key]
            when (spec.kind) {
                EditableParamKind.BOOLEAN -> {
                    val b = rawStr?.lowercase(Locale.ROOT)?.toBooleanStrictOrNull()
                        ?: (originalInput[spec.key] as? JsonPrimitive)?.booleanOrNull
                    if (b != null) {
                        mutable[spec.key] = JsonPrimitive(b)
                    }
                }
                EditableParamKind.INTEGER -> {
                    val l = rawStr?.trim()?.toLongOrNull()
                        ?: (originalInput[spec.key] as? JsonPrimitive)?.longOrNull
                    if (l != null) {
                        mutable[spec.key] = JsonPrimitive(l)
                    }
                }
                EditableParamKind.NUMBER -> {
                    val d = rawStr?.trim()?.toDoubleOrNull()
                        ?: (originalInput[spec.key] as? JsonPrimitive)?.doubleOrNull
                    if (d != null) {
                        mutable[spec.key] = JsonPrimitive(d)
                    }
                }
                EditableParamKind.TEXT,
                EditableParamKind.MULTILINE_TEXT,
                EditableParamKind.DATETIME,
                EditableParamKind.WORKSPACE_PATH -> {
                    if (rawStr != null) {
                        val normalized = if (spec.kind == EditableParamKind.WORKSPACE_PATH || spec.kind == EditableParamKind.DATETIME) {
                            rawStr.trim()
                        } else {
                            rawStr
                        }
                        if (!spec.required && normalized.isBlank() && spec.key !in originalInput) {
                            mutable.remove(spec.key)
                        } else {
                            mutable[spec.key] = JsonPrimitive(normalized)
                        }
                    }
                }
            }
        }
        return JsonObject(mutable)
    }

    val isModified: Boolean
        get() = buildEditedJson() != originalInput

    val isValid: Boolean
        get() = isValidEditedToolInput(toolName, originalInput, buildEditedJson())
}

internal data class QuestionOption(
    val value: String,
    val label: String = value,
    val supporting: String? = null,
    val tag: String? = null,
)

internal data class QuestionCondition(
    val questionId: String,
    val equalsValue: String? = null,
    val notEqualsValue: String? = null,
    val inValues: List<String> = emptyList(),
) {
    fun isSatisfied(answers: Map<String, String?>): Boolean {
        val raw = answers[questionId]?.trim().orEmpty()
        val tokens = raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (equalsValue != null) {
            return raw.equals(equalsValue, ignoreCase = true) ||
                tokens.any { it.equals(equalsValue, ignoreCase = true) }
        }
        if (notEqualsValue != null) {
            if (raw.isEmpty()) return false
            return !raw.equals(notEqualsValue, ignoreCase = true) &&
                tokens.none { it.equals(notEqualsValue, ignoreCase = true) }
        }
        if (inValues.isNotEmpty()) {
            return inValues.any { target ->
                raw.equals(target, ignoreCase = true) ||
                    tokens.any { it.equals(target, ignoreCase = true) }
            }
        }
        return raw.isNotEmpty()
    }
}

internal enum class QuestionCardLayout {
    PAGED,
    STACKED,
}

internal data class UserQuestionCardSpec(
    val title: String? = null,
    val layout: QuestionCardLayout = QuestionCardLayout.PAGED,
    val questions: List<UserQuestion> = emptyList(),
)

/**
 * One ask_user question or interactive behavior primitive block.
 * Preserves the first 4 constructor parameters ([id], [question], [options], [type]) for compatibility.
 */
internal data class UserQuestion(
    val id: String,
    val question: String,
    val options: List<String>,
    val type: String,
    val optionItems: List<QuestionOption> = options.map { QuestionOption(value = it, label = it) },
    val description: String? = null,
    val placeholder: String? = null,
    val defaultValue: String? = null,
    val required: Boolean = true,
    val allowCustom: Boolean = false,
    val presentation: String? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val unit: String? = null,
    val mode: String? = null,
    val minSelect: Int? = null,
    val maxSelect: Int? = null,
    val visibleWhen: QuestionCondition? = null,
) {
    val normalizedType: String
        get() = when (type.lowercase(Locale.ROOT)) {
            "single", "radio", "select", "choice" -> "single"
            "multi", "multiple", "checkbox", "tags" -> "multi"
            "slider", "range", "scale" -> "slider"
            "stepper", "number", "counter", "quantity" -> "stepper"
            "datetime", "date", "time" -> "datetime"
            "toggle", "boolean", "switch", "confirm" -> "toggle"
            "reorder", "rank", "priority", "sort" -> "reorder"
            "text", "input", "textarea" -> "text"
            else -> if (optionItems.isNotEmpty()) "single" else "text"
        }

    val effectiveDateTimeMode: String
        get() = when {
            mode?.lowercase(Locale.ROOT) in setOf("date", "time", "datetime") ->
                mode!!.lowercase(Locale.ROOT)
            type.lowercase(Locale.ROOT) in setOf("date", "time") ->
                type.lowercase(Locale.ROOT)
            else -> "datetime"
        }

    val effectivePresentation: String
        get() = presentation?.lowercase(Locale.ROOT) ?: when (normalizedType) {
            "single", "multi" -> "list"
            else -> "default"
        }

    fun formatDisplayAnswer(rawAnswer: String?): String {
        val raw = rawAnswer?.trim().orEmpty()
        if (raw.isEmpty()) return "—"
        return when (normalizedType) {
            "single" -> {
                optionItems.firstOrNull { it.value == raw }?.label ?: raw
            }
            "multi" -> {
                raw.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(", ") { token ->
                        optionItems.firstOrNull { it.value == token }?.label ?: token
                    }
            }
            "reorder" -> {
                raw.split(",")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .joinToString(" → ") { token ->
                        optionItems.firstOrNull { it.value == token }?.label ?: token
                    }
            }
            "slider", "stepper" -> {
                val u = unit?.trim().orEmpty()
                if (u.isNotEmpty() && !raw.endsWith(u)) "$raw $u" else raw
            }
            "toggle" -> {
                if (optionItems.isEmpty()) {
                    when (raw.lowercase(Locale.ROOT)) {
                        "true", "yes", "1" -> "Yes"
                        "false", "no", "0" -> "No"
                        else -> raw
                    }
                } else {
                    raw.split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(", ") { token ->
                            optionItems.firstOrNull { it.value == token }?.label ?: token
                        }
                }
            }
            else -> raw
        }
    }
}

internal fun UIMessagePart.Tool.userQuestionSpec(): UserQuestionCardSpec =
    parseUserQuestionCardSpec(inputAsJson())

internal fun UIMessagePart.Tool.userQuestions(): List<UserQuestion> =
    userQuestionSpec().questions

internal fun parseUserQuestionCardSpec(input: JsonElement?): UserQuestionCardSpec {
    val root = runCatching { input?.jsonObject }.getOrNull() ?: return UserQuestionCardSpec()
    val title = root["title"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    val layoutRaw = root["layout"]?.jsonPrimitive?.contentOrNull?.lowercase(Locale.ROOT)
    val questionsArray = (root["questions"] ?: root["blocks"])?.let {
        runCatching { it.jsonArray }.getOrNull()
    } ?: return UserQuestionCardSpec(title = title)

    val questions = questionsArray.mapIndexedNotNull { idx, elem ->
        val obj = runCatching { elem.jsonObject }.getOrNull() ?: return@mapIndexedNotNull null
        val q = (obj["question"] ?: obj["label"] ?: obj["title"])
            ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return@mapIndexedNotNull null
        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: "q_$idx"
        val optionItems = parseQuestionOptions(obj)
        val rawType = (obj["selection_type"] ?: obj["type"] ?: obj["kind"])
            ?.jsonPrimitive?.contentOrNull
            ?: "text"

        UserQuestion(
            id = id,
            question = q,
            options = optionItems.map { it.label },
            type = rawType,
            optionItems = optionItems,
            description = (obj["description"] ?: obj["supporting"] ?: obj["helper_text"])
                ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            placeholder = obj["placeholder"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            defaultValue = parseDefaultValueString(obj["default_value"] ?: obj["default"]),
            required = obj["required"]?.jsonPrimitive?.booleanOrNull ?: true,
            allowCustom = (obj["allow_custom"] ?: obj["allow_other"])?.jsonPrimitive?.booleanOrNull ?: false,
            presentation = (obj["presentation"] ?: obj["variant"] ?: obj["style"])
                ?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            min = obj["min"]?.jsonPrimitive?.doubleOrNull,
            max = obj["max"]?.jsonPrimitive?.doubleOrNull,
            step = obj["step"]?.jsonPrimitive?.doubleOrNull,
            unit = obj["unit"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            mode = obj["mode"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
            minSelect = obj["min_select"]?.jsonPrimitive?.intOrNull,
            maxSelect = obj["max_select"]?.jsonPrimitive?.intOrNull,
            visibleWhen = parseQuestionCondition(obj["visible_when"] ?: obj["when"]),
        )
    }

    val resolvedLayout = when (layoutRaw) {
        "stacked", "form", "all", "compact" -> QuestionCardLayout.STACKED
        "paged", "wizard", "step" -> QuestionCardLayout.PAGED
        else -> QuestionCardLayout.PAGED
    }

    return UserQuestionCardSpec(
        title = title,
        layout = resolvedLayout,
        questions = questions,
    )
}

private fun parseQuestionOptions(obj: JsonObject): List<QuestionOption> {
    val richArray = runCatching { obj["option_items"]?.jsonArray }.getOrNull()
    if (!richArray.isNullOrEmpty()) {
        val parsed = richArray.mapNotNull { parseSingleOptionElement(it) }
        if (parsed.isNotEmpty()) return parsed
    }
    val optionsArray = runCatching { obj["options"]?.jsonArray }.getOrNull() ?: return emptyList()
    return optionsArray.mapNotNull { parseSingleOptionElement(it) }
}

private fun parseSingleOptionElement(element: JsonElement): QuestionOption? {
    return when (element) {
        is JsonPrimitive -> {
            val raw = element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // Support pipe-separated shorthand: "Label | Supporting description | Tag"
            if (raw.contains(" | ")) {
                val parts = raw.split("|").map { it.trim() }
                val label = parts.getOrNull(0)?.takeIf { it.isNotEmpty() } ?: raw
                val supporting = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                val tag = parts.getOrNull(2)?.takeIf { it.isNotEmpty() }
                QuestionOption(
                    value = label,
                    label = label,
                    supporting = supporting,
                    tag = tag,
                )
            } else {
                QuestionOption(value = raw, label = raw)
            }
        }
        is JsonObject -> {
            val label = (element["label"] ?: element["title"] ?: element["value"] ?: element["id"])
                ?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: return null
            val value = (element["value"] ?: element["id"] ?: element["label"])
                ?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: label
            val supporting = (element["supporting"] ?: element["description"] ?: element["subtitle"])
                ?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val tag = (element["tag"] ?: element["badge"] ?: element["meta"])
                ?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            QuestionOption(
                value = value,
                label = label,
                supporting = supporting,
                tag = tag,
            )
        }
        else -> null
    }
}

private fun parseDefaultValueString(element: JsonElement?): String? {
    return when (element) {
        is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }
        is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(", ")
            .takeIf { it.isNotBlank() }
        else -> null
    }
}

private fun parseQuestionCondition(element: JsonElement?): QuestionCondition? {
    val obj = runCatching { element?.jsonObject }.getOrNull() ?: return null
    val questionId = (obj["question_id"] ?: obj["id"] ?: obj["field"])
        ?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val equalsValue = (obj["equals"] ?: obj["is"] ?: obj["value"])
        ?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val notEqualsValue = (obj["not_equals"] ?: obj["is_not"])
        ?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    val inValues = runCatching { obj["in"]?.jsonArray }
        .getOrNull()
        ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        .orEmpty()
    return QuestionCondition(
        questionId = questionId,
        equalsValue = equalsValue,
        notEqualsValue = notEqualsValue,
        inValues = inValues,
    )
}

internal fun formatNumericValue(value: Double, step: Double?): String {
    val isIntegerStep = step == null || (step % 1.0 == 0.0 && value % 1.0 == 0.0)
    return if (isIntegerStep && value % 1.0 == 0.0) {
        value.roundToLong().toString()
    } else {
        String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
    }
}

internal fun snapToStep(value: Double, min: Double, max: Double, step: Double?): Double {
    val clamped = value.coerceIn(min, max)
    if (step == null || step <= 0.0) return clamped
    val steps = ((clamped - min) / step).roundToInt()
    return (min + steps * step).coerceIn(min, max)
}

/**
 * What the partner needs to know before it carries on: either one question at a time (`paged`),
 * or a compact multi-control form (`stacked`). Supports declarative behavior primitives:
 * `single`, `multi`, `slider`, `stepper`, `datetime`, `toggle`, `reorder`, and `text`.
 */
@Composable
internal fun ThreadQuestionCard(
    tool: UIMessagePart.Tool,
    onAnswer: (String, String) -> Unit,
    replyHint: Boolean = true,
) {
    val spec = remember(tool.input) { tool.userQuestionSpec() }
    val allQuestions = spec.questions
    val draft = remember(tool.toolCallId, allQuestions) {
        PrimitiveCardStateStore.questionDraft(tool.toolCallId, allQuestions)
    }
    var sent by remember(tool.toolCallId) { mutableStateOf(false) }

    LaunchedEffect(tool.approvalState) {
        if (tool.approvalState != ToolApprovalState.Pending) {
            PrimitiveCardStateStore.remove(tool.toolCallId)
        }
    }

    if (tool.approvalState !is ToolApprovalState.Pending && !sent) {
        SettledQuestionCard(tool = tool, spec = spec)
        return
    }

    val visibleQuestions = draft.visibleQuestions(allQuestions)
    val currentIdx = draft.currentIndex.coerceIn(0, (visibleQuestions.size - 1).coerceAtLeast(0))
    val current = visibleQuestions.getOrNull(currentIdx)
    val isStacked = spec.layout == QuestionCardLayout.STACKED && visibleQuestions.size > 1
    val last = isStacked || currentIdx >= visibleQuestions.lastIndex

    val cardOverline = when {
        isStacked -> stringResource(R.string.im_question_progress, visibleQuestions.size, visibleQuestions.size)
        visibleQuestions.size > 1 -> stringResource(R.string.im_question_progress, currentIdx + 1, visibleQuestions.size)
        else -> stringResource(R.string.im_question_one)
    }
    val cardTitle = when {
        isStacked -> spec.title?.takeIf { it.isNotBlank() }
            ?: current?.question?.takeIf { it.isNotBlank() }
        else -> current?.question?.takeIf { it.isNotBlank() }
    }

    ImActionCard(
        title = cardTitle,
        overline = cardOverline,
        icon = HugeIcons.BubbleChatQuestion,
    ) {
        if (current != null && !sent) {
            if (isStacked) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    visibleQuestions.forEachIndexed { idx, q ->
                        if (idx > 0 || !spec.title.isNullOrBlank()) {
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                ) {
                                    Text(
                                        text = q.question,
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    if (!q.required) {
                                        ImTag("Optional")
                                    }
                                }
                                if (!q.description.isNullOrBlank()) {
                                    Text(
                                        text = q.description,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                        QuestionPrimitiveContent(
                            question = q,
                            draft = draft,
                            autoAdvanceOnSingleSelect = false,
                            onAutoAdvance = {},
                        )
                        if (idx < visibleQuestions.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceContainerHigh)
                        }
                    }
                }
            } else {
                if (!current.description.isNullOrBlank()) {
                    Text(
                        text = current.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                QuestionPrimitiveContent(
                    question = current,
                    draft = draft,
                    autoAdvanceOnSingleSelect = !last && !current.allowCustom,
                    onAutoAdvance = { draft.currentIndex = currentIdx + 1 },
                )
            }
        }

        if (sent) {
            ImCardStatus(stringResource(R.string.im_question_sent))
        } else if (current != null) {
            ImCardButtons {
                if (!isStacked && currentIdx > 0) {
                    ImCardOutlinedButton(
                        text = stringResource(R.string.im_question_back),
                        onClick = { draft.currentIndex = (currentIdx - 1).coerceAtLeast(0) },
                    )
                }
                ImCardButton(
                    text = stringResource(if (last) R.string.im_question_send else R.string.im_question_next),
                    enabled = if (last) draft.allVisibleAnswered(visibleQuestions) else draft.isAnswered(current),
                    onClick = {
                        if (!last) {
                            draft.currentIndex = currentIdx + 1
                            return@ImCardButton
                        }
                        sent = true
                        val payload = draft.buildAnswerJson(visibleQuestions)
                        PrimitiveCardStateStore.remove(tool.toolCallId)
                        onAnswer(tool.toolCallId, payload)
                    },
                )
            }
        }
        if (replyHint && !sent) {
            Text(
                text = stringResource(R.string.im_question_reply_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Extracts each visible question and its formatted display answer for a settled `ask_user` call.
 */
internal fun extractSettledQuestionAnswers(
    tool: UIMessagePart.Tool,
    spec: UserQuestionCardSpec = tool.userQuestionSpec(),
): List<Pair<UserQuestion, String>> {
    val questions = spec.questions
    if (questions.isEmpty()) return emptyList()
    val answeredState = tool.approvalState as? ToolApprovalState.Answered
    val rawPayload = answeredState?.answer
        ?: tool.output.filterIsInstance<UIMessagePart.Text>().firstOrNull()?.text
    val answersObj = rawPayload?.let { payload ->
        runCatching {
            val parsed = Json.parseToJsonElement(payload).jsonObject
            (parsed["answers"] as? JsonObject) ?: parsed
        }.getOrNull()
    }
    val answerMap = answersObj
        ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull }
        .orEmpty()

    val visible = if (answerMap.isEmpty()) {
        questions
    } else {
        questions.filter { q ->
            q.id in answerMap || (q.visibleWhen?.isSatisfied(answerMap) ?: true)
        }
    }

    return visible.map { q ->
        val rawAnswer = when {
            answersObj != null -> answerMap[q.id].orEmpty()
            answeredState != null -> answeredState.answer
            else -> ""
        }
        val formatted = if (rawAnswer.isNotBlank()) {
            q.formatDisplayAnswer(rawAnswer)
        } else {
            ""
        }
        q to formatted
    }
}

/**
 * Formats a concise 1-line summary for a settled `ask_user` card (`ImFoldedLine` or step header).
 */
internal fun formatSettledQuestionSummary(
    spec: UserQuestionCardSpec,
    answeredPairs: List<Pair<UserQuestion, String>>,
): String {
    val nonBlank = answeredPairs.filter { it.second.isNotBlank() }
    val header = spec.title?.takeIf { it.isNotBlank() }
        ?: answeredPairs.firstOrNull()?.first?.question?.takeIf { it.isNotBlank() }
        ?: "Question"
    if (nonBlank.isEmpty()) return header
    return if (nonBlank.size == 1 && spec.questions.size <= 1) {
        val (q, ans) = nonBlank.first()
        "${q.question}: $ans"
    } else {
        val joinedAnswers = nonBlank.joinToString(" · ") { (_, ans) -> ans }
        "$header — $joinedAnswers"
    }
}

/**
 * Compact folded record for an answered or settled `ask_user` card.
 * Tapping the folded line toggles a read-only breakdown of each answered field.
 */
@Composable
internal fun SettledQuestionCard(
    tool: UIMessagePart.Tool,
    spec: UserQuestionCardSpec = remember(tool.input) { tool.userQuestionSpec() },
) {
    val pairs = remember(tool.input, tool.approvalState, tool.output) {
        extractSettledQuestionAnswers(tool, spec)
    }
    val summary = remember(spec, pairs) {
        formatSettledQuestionSummary(spec, pairs)
    }
    var expanded by remember(tool.toolCallId) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
        ImFoldedLine(
            summary = summary,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded && pairs.isNotEmpty()) {
            ImCardBlock {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pairs.forEachIndexed { idx, (q, ans) ->
                        if (idx > 0) ImCardBlockDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                text = q.question,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = ans.ifBlank {
                                    if (tool.approvalState.isRepliedInChat) {
                                        stringResource(R.string.chat_message_tool_replied_in_chat)
                                    } else {
                                        "—"
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionPrimitiveContent(
    question: UserQuestion,
    draft: QuestionDraft,
    autoAdvanceOnSingleSelect: Boolean,
    onAutoAdvance: () -> Unit,
) {
    when (question.normalizedType) {
        "single" -> {
            val selected = draft.singleAnswer(question.id)
            val useChips = question.effectivePresentation in setOf("chips", "pills", "segmented", "grid") &&
                question.optionItems.all { it.supporting.isNullOrBlank() }

            if (useChips) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    question.optionItems.forEach { option ->
                        val isSelected = selected == option.value
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                draft.setSingle(question.id, option.value)
                                if (autoAdvanceOnSingleSelect) onAutoAdvance()
                            },
                            label = {
                                Text(
                                    text = if (option.tag != null) "${option.label} · ${option.tag}" else option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = HugeIcons.Tick01,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            } else {
                ImOptionList(
                    options = question.optionItems.map { opt ->
                        ImOption(
                            key = opt.value,
                            title = opt.label,
                            supporting = opt.supporting,
                            tag = opt.tag,
                        )
                    },
                    selected = setOfNotNull(selected.takeIf { it.isNotBlank() }),
                    onSelect = { value ->
                        draft.setSingle(question.id, value)
                        if (autoAdvanceOnSingleSelect) onAutoAdvance()
                    },
                )
            }

            if (question.allowCustom) {
                Spacer(modifier = Modifier.height(6.dp))
                val customValue = draft.customInput(question.id)
                OutlinedTextField(
                    value = customValue,
                    onValueChange = { text ->
                        draft.setCustomInput(question.id, text)
                        if (text.isNotBlank()) {
                            draft.setSingle(question.id, text.trim())
                        } else if (selected !in question.optionItems.map { it.value }) {
                            draft.setSingle(question.id, "")
                        }
                    },
                    placeholder = {
                        Text(question.placeholder ?: stringResource(R.string.im_question_answer_hint))
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        "multi" -> {
            val selectedSet = draft.multiAnswer(question.id)
            val useChips = question.effectivePresentation in setOf("chips", "pills", "tags") &&
                question.optionItems.all { it.supporting.isNullOrBlank() }

            if (question.minSelect != null || question.maxSelect != null) {
                val hint = buildString {
                    if (question.minSelect != null && question.maxSelect != null) {
                        append("Select ${question.minSelect}–${question.maxSelect}")
                    } else if (question.minSelect != null) {
                        append("Select at least ${question.minSelect}")
                    } else if (question.maxSelect != null) {
                        append("Select up to ${question.maxSelect}")
                    }
                    append(" (${selectedSet.size} selected)")
                }
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (useChips) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    question.optionItems.forEach { option ->
                        val isSelected = option.value in selectedSet
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                draft.toggleMulti(
                                    id = question.id,
                                    option = option.value,
                                    maxSelect = question.maxSelect,
                                )
                            },
                            label = {
                                Text(
                                    text = if (option.tag != null) "${option.label} · ${option.tag}" else option.label,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            leadingIcon = if (isSelected) {
                                {
                                    Icon(
                                        imageVector = HugeIcons.Tick01,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            } else {
                ImOptionList(
                    options = question.optionItems.map { opt ->
                        ImOption(
                            key = opt.value,
                            title = opt.label,
                            supporting = opt.supporting,
                            tag = opt.tag,
                        )
                    },
                    selected = selectedSet,
                    multiple = true,
                    onSelect = { value ->
                        draft.toggleMulti(
                            id = question.id,
                            option = value,
                            maxSelect = question.maxSelect,
                        )
                    },
                )
            }

            if (question.allowCustom) {
                Spacer(modifier = Modifier.height(6.dp))
                var customItemText by remember(question.id) { mutableStateOf("") }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = customItemText,
                        onValueChange = { customItemText = it },
                        placeholder = {
                            Text(question.placeholder ?: stringResource(R.string.im_question_answer_hint))
                        },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedIconButton(
                        onClick = {
                            val trimmed = customItemText.trim()
                            if (trimmed.isNotEmpty()) {
                                draft.toggleMulti(question.id, trimmed, question.maxSelect)
                                customItemText = ""
                            }
                        },
                        enabled = customItemText.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = HugeIcons.Add01,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }

        "slider" -> {
            val min = question.min ?: 0.0
            val max = (question.max ?: 100.0).coerceAtLeast(min + 1.0)
            val step = question.step?.takeIf { it > 0.0 }
            val currentVal = draft.numericAnswer(question, defaultFallback = min)
            val formatted = formatNumericValue(currentVal, step)
            val unitSuffix = question.unit?.let { " $it" }.orEmpty()

            val stepsCount = if (step != null && step > 0.0) {
                (((max - min) / step).roundToInt() - 1).coerceIn(0, 100)
            } else {
                0
            }

            ImCardBlock {
                Column(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Text(
                            text = "$formatted$unitSuffix",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "${formatNumericValue(min, step)} – ${formatNumericValue(max, step)}$unitSuffix",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = currentVal.toFloat(),
                        onValueChange = { raw ->
                            val snapped = snapToStep(raw.toDouble(), min, max, step)
                            draft.setSingle(question.id, formatNumericValue(snapped, step))
                        },
                        valueRange = min.toFloat()..max.toFloat(),
                        steps = stepsCount,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        "stepper" -> {
            val min = question.min ?: 0.0
            val max = (question.max ?: 9999.0).coerceAtLeast(min)
            val step = question.step?.takeIf { it > 0.0 } ?: 1.0
            val currentVal = draft.numericAnswer(question, defaultFallback = min.coerceAtLeast(0.0))
            val formatted = formatNumericValue(currentVal, step)
            val unitSuffix = question.unit?.let { " $it" }.orEmpty()

            ImCardBlock {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "$formatted$unitSuffix",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                        )
                        if (question.min != null || question.max != null) {
                            Text(
                                text = "${formatNumericValue(min, step)} – ${formatNumericValue(max, step)}$unitSuffix",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            onClick = {
                                val next = snapToStep(currentVal - step, min, max, step)
                                draft.setSingle(question.id, formatNumericValue(next, step))
                            },
                            enabled = currentVal > min,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh,
                            modifier = Modifier
                                .size(36.dp)
                                .alpha(if (currentVal > min) 1f else 0.4f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "−",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                        Surface(
                            onClick = {
                                val next = snapToStep(currentVal + step, min, max, step)
                                draft.setSingle(question.id, formatNumericValue(next, step))
                            },
                            enabled = currentVal < max,
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier
                                .size(36.dp)
                                .alpha(if (currentVal < max) 1f else 0.4f),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "+",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                        }
                    }
                }
            }
        }

        "datetime" -> {
            DateTimePrimitive(question = question, draft = draft)
        }

        "toggle" -> {
            if (question.optionItems.size <= 1) {
                val label = question.optionItems.firstOrNull()?.label ?: question.question
                val supporting = question.optionItems.firstOrNull()?.supporting
                val checked = draft.singleAnswer(question.id).equals("true", ignoreCase = true)
                ImCardBlock(onClick = { draft.setSingle(question.id, (!checked).toString()) }) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (!supporting.isNullOrBlank()) {
                                Text(
                                    text = supporting,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = checked,
                            onCheckedChange = { draft.setSingle(question.id, it.toString()) },
                        )
                    }
                }
            } else {
                val selectedSet = draft.multiAnswer(question.id)
                ImCardBlock {
                    question.optionItems.forEachIndexed { index, option ->
                        if (index > 0) ImCardBlockDivider()
                        val checked = option.value in selectedSet
                        Surface(
                            onClick = { draft.toggleMulti(question.id, option.value, question.maxSelect) },
                            color = androidx.compose.ui.graphics.Color.Transparent,
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 52.dp)
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            text = option.label,
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        option.tag?.let { ImTag(it) }
                                    }
                                    if (!option.supporting.isNullOrBlank()) {
                                        Text(
                                            text = option.supporting,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                Switch(
                                    checked = checked,
                                    onCheckedChange = {
                                        draft.toggleMulti(question.id, option.value, question.maxSelect)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }

        "reorder" -> {
            val orderedValues = draft.orderedAnswer(question)
            val optionByValue = remember(question.optionItems) {
                question.optionItems.associateBy { it.value }
            }
            ImCardBlock {
                orderedValues.forEachIndexed { index, value ->
                    if (index > 0) ImCardBlockDivider()
                    val option = optionByValue[value] ?: QuestionOption(value = value, label = value)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(26.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text(
                                    text = option.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                option.tag?.let { ImTag(it) }
                            }
                            if (!option.supporting.isNullOrBlank()) {
                                Text(
                                    text = option.supporting,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            IconButton(
                                onClick = { draft.moveOrderedItem(question, index, index - 1) },
                                enabled = index > 0,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.ArrowUp01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                            IconButton(
                                onClick = { draft.moveOrderedItem(question, index, index + 1) },
                                enabled = index < orderedValues.lastIndex,
                                modifier = Modifier.size(32.dp),
                            ) {
                                Icon(
                                    imageVector = HugeIcons.ArrowDown01,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }

        else -> {
            // Suggested answers fill the field; the owner can still write their own.
            if (question.optionItems.isNotEmpty()) {
                ImOptionList(
                    options = question.optionItems.map { opt ->
                        ImOption(
                            key = opt.value,
                            title = opt.label,
                            supporting = opt.supporting,
                            tag = opt.tag,
                        )
                    },
                    selected = setOfNotNull(draft.singleAnswer(question.id).takeIf { it.isNotBlank() }),
                    onSelect = { draft.setSingle(question.id, it) },
                )
            }
            val isMultiline = question.effectivePresentation == "multiline" ||
                question.type.equals("textarea", ignoreCase = true)
            val isNumericInput = question.effectivePresentation == "number"
            OutlinedTextField(
                value = draft.singleAnswer(question.id),
                onValueChange = { draft.setSingle(question.id, it) },
                placeholder = {
                    Text(question.placeholder ?: stringResource(R.string.im_question_answer_hint))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
                singleLine = !isMultiline,
                minLines = if (isMultiline) 3 else 1,
                maxLines = if (isMultiline) 5 else 4,
                keyboardOptions = if (isNumericInput) {
                    KeyboardOptions(keyboardType = KeyboardType.Decimal)
                } else {
                    KeyboardOptions.Default
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DateTimePrimitive(
    question: UserQuestion,
    draft: QuestionDraft,
) {
    val mode = question.effectiveDateTimeMode
    val currentValue = draft.singleAnswer(question.id)

    var showDateDialog by remember(question.id) { mutableStateOf(false) }
    var showTimeDialog by remember(question.id) { mutableStateOf(false) }
    var pendingDate by remember(question.id) { mutableStateOf<LocalDate?>(null) }

    if (question.optionItems.isNotEmpty()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            question.optionItems.forEach { option ->
                val isSelected = currentValue == option.value
                FilterChip(
                    selected = isSelected,
                    onClick = { draft.setSingle(question.id, option.value) },
                    label = { Text(option.label) },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = HugeIcons.Tick01,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    } else {
                        null
                    },
                )
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
    }

    ImCardBlock(
        onClick = {
            if (mode == "time") {
                showTimeDialog = true
            } else {
                showDateDialog = true
            }
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = HugeIcons.Clock02,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = currentValue.ifBlank {
                    question.placeholder ?: stringResource(R.string.im_calendar_change_time)
                },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (currentValue.isNotBlank()) FontWeight.SemiBold else FontWeight.Normal,
                fontFamily = if (currentValue.isNotBlank()) FontFamily.Monospace else null,
                color = if (currentValue.isNotBlank()) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.im_calendar_change_time),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (showDateDialog) {
        val zone = remember { ZoneId.systemDefault() }
        val initialMillis = remember(currentValue) {
            runCatching {
                val datePart = currentValue.substringBefore(" ").substringBefore("T").trim()
                LocalDate.parse(datePart).atStartOfDay(zone).toInstant().toEpochMilli()
            }.getOrElse { System.currentTimeMillis() }
        }
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)
        DatePickerDialog(
            onDismissRequest = { showDateDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        val millis = datePickerState.selectedDateMillis ?: initialMillis
                        val pickedDate = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
                        showDateDialog = false
                        if (mode == "date") {
                            draft.setSingle(
                                question.id,
                                pickedDate.format(DateTimeFormatter.ISO_LOCAL_DATE),
                            )
                        } else {
                            pendingDate = pickedDate
                            showTimeDialog = true
                        }
                    },
                ) {
                    Text(
                        if (mode == "date") {
                            stringResource(R.string.common_confirm)
                        } else {
                            stringResource(R.string.im_calendar_next)
                        },
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDateDialog = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        ) {
            DatePicker(state = datePickerState)
        }
    }

    if (showTimeDialog) {
        val initialTime = remember(currentValue) {
            runCatching {
                val timePart = currentValue.substringAfter(" ", currentValue.substringAfter("T", ""))
                    .trim()
                    .takeIf { it.contains(":") }
                if (timePart != null) LocalTime.parse(timePart) else LocalTime.now().withSecond(0).withNano(0)
            }.getOrElse { LocalTime.now().withSecond(0).withNano(0) }
        }
        val timePickerState = rememberTimePickerState(
            initialHour = initialTime.hour,
            initialMinute = initialTime.minute,
            is24Hour = true,
        )
        AlertDialog(
            onDismissRequest = {
                showTimeDialog = false
                pendingDate = null
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val pickedTime = LocalTime.of(timePickerState.hour, timePickerState.minute)
                        val timeStr = pickedTime.format(DateTimeFormatter.ofPattern("HH:mm"))
                        val formatted = if (mode == "time") {
                            timeStr
                        } else {
                            val date = pendingDate ?: LocalDate.now()
                            "${date.format(DateTimeFormatter.ISO_LOCAL_DATE)} $timeStr"
                        }
                        draft.setSingle(question.id, formatted)
                        showTimeDialog = false
                        pendingDate = null
                    },
                ) {
                    Text(stringResource(R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showTimeDialog = false
                        pendingDate = null
                    },
                ) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
            text = { TimePicker(state = timePickerState) },
        )
    }
}

/** Answers in progress for one call. */
internal class QuestionDraft(questions: List<UserQuestion> = emptyList()) {
    var currentIndex by mutableIntStateOf(0)
    private val single = mutableStateMapOf<String, String>()
    private val multi = mutableStateMapOf<String, Set<String>>()
    private val ordered = mutableStateMapOf<String, List<String>>()
    private val customInputs = mutableStateMapOf<String, String>()

    init {
        questions.forEach { q ->
            when (q.normalizedType) {
                "multi" -> {
                    val defaults = q.defaultValue
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        ?.toSet()
                        .orEmpty()
                    if (defaults.isNotEmpty()) {
                        multi[q.id] = defaults
                    }
                }
                "toggle" -> {
                    if (q.optionItems.size <= 1) {
                        val initVal = when (q.defaultValue?.lowercase(Locale.ROOT)) {
                            "true", "1", "yes" -> "true"
                            else -> "false"
                        }
                        single[q.id] = initVal
                    } else {
                        val defaults = q.defaultValue
                            ?.split(",")
                            ?.map { it.trim() }
                            ?.filter { it.isNotEmpty() }
                            ?.toSet()
                            .orEmpty()
                        if (defaults.isNotEmpty()) {
                            multi[q.id] = defaults
                        }
                    }
                }
                "slider", "stepper" -> {
                    val defaultNum = q.defaultValue?.toDoubleOrNull()
                        ?: q.min
                        ?: 0.0
                    single[q.id] = formatNumericValue(defaultNum, q.step)
                }
                "reorder" -> {
                    val defaultOrder = q.defaultValue
                        ?.split(",")
                        ?.map { it.trim() }
                        ?.filter { it.isNotEmpty() }
                        .orEmpty()
                    val baseValues = q.optionItems.map { it.value }
                    ordered[q.id] = if (defaultOrder.isNotEmpty()) {
                        (defaultOrder + baseValues).distinct()
                    } else {
                        baseValues
                    }
                }
                else -> {
                    if (!q.defaultValue.isNullOrBlank()) {
                        single[q.id] = q.defaultValue
                    }
                }
            }
        }
    }

    fun singleAnswer(id: String): String = single[id].orEmpty()

    fun setSingle(id: String, value: String) {
        single[id] = value
    }

    fun customInput(id: String): String = customInputs[id].orEmpty()

    fun setCustomInput(id: String, value: String) {
        customInputs[id] = value
    }

    fun numericAnswer(question: UserQuestion, defaultFallback: Double): Double {
        return single[question.id]?.toDoubleOrNull()
            ?: question.defaultValue?.toDoubleOrNull()
            ?: defaultFallback
    }

    fun multiAnswer(id: String): Set<String> = multi[id].orEmpty()

    fun toggleMulti(id: String, option: String, maxSelect: Int? = null) {
        val current = multi[id].orEmpty()
        multi[id] = if (option in current) {
            current - option
        } else {
            if (maxSelect != null && maxSelect > 0 && current.size >= maxSelect) {
                current
            } else {
                current + option
            }
        }
    }

    fun orderedAnswer(question: UserQuestion): List<String> {
        return ordered[question.id] ?: question.optionItems.map { it.value }
    }

    fun moveOrderedItem(question: UserQuestion, fromIndex: Int, toIndex: Int) {
        val list = orderedAnswer(question).toMutableList()
        if (fromIndex !in list.indices || toIndex !in list.indices) return
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        ordered[question.id] = list
    }

    fun currentAnswerMap(questions: List<UserQuestion>): Map<String, String?> {
        return questions.associate { q ->
            val value = when (q.normalizedType) {
                "multi" -> multi[q.id]?.joinToString(", ")
                "toggle" -> if (q.optionItems.size <= 1) {
                    single[q.id] ?: "false"
                } else {
                    multi[q.id]?.joinToString(", ")
                }
                "reorder" -> orderedAnswer(q).joinToString(", ")
                else -> single[q.id]
            }
            q.id to value
        }
    }

    fun visibleQuestions(allQuestions: List<UserQuestion>): List<UserQuestion> {
        val answers = currentAnswerMap(allQuestions)
        return allQuestions.filter { q ->
            q.visibleWhen?.isSatisfied(answers) ?: true
        }
    }

    fun isAnswered(q: UserQuestion): Boolean {
        if (!q.required) return true
        return when (q.normalizedType) {
            "multi" -> {
                val count = multi[q.id]?.size ?: 0
                val minReq = (q.minSelect ?: 1).coerceAtLeast(1)
                val maxReq = q.maxSelect ?: Int.MAX_VALUE
                count in minReq..maxReq
            }
            "toggle" -> {
                if (q.optionItems.size <= 1) {
                    true
                } else {
                    val count = multi[q.id]?.size ?: 0
                    val minReq = q.minSelect ?: 0
                    val maxReq = q.maxSelect ?: Int.MAX_VALUE
                    count in minReq..maxReq
                }
            }
            "slider", "stepper" -> single[q.id]?.isNotBlank() == true
            "reorder" -> orderedAnswer(q).isNotEmpty()
            else -> single[q.id]?.isNotBlank() == true
        }
    }

    fun allVisibleAnswered(visibleQuestions: List<UserQuestion>): Boolean {
        return visibleQuestions.all { isAnswered(it) }
    }

    fun buildAnswerJson(visibleQuestions: List<UserQuestion>): String {
        return buildJsonObject {
            put(
                "answers",
                buildJsonObject {
                    visibleQuestions.forEach { q ->
                        val v = when (q.normalizedType) {
                            "multi" -> multi[q.id]?.joinToString(", ").orEmpty()
                            "toggle" -> if (q.optionItems.size <= 1) {
                                single[q.id] ?: "false"
                            } else {
                                multi[q.id]?.joinToString(", ").orEmpty()
                            }
                            "reorder" -> orderedAnswer(q).joinToString(", ")
                            else -> single[q.id].orEmpty()
                        }
                        put(q.id, v)
                    }
                },
            )
        }.toString()
    }
}

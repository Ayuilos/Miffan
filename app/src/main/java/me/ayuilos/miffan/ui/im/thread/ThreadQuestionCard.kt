package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.R
import me.rerere.ai.ui.UIMessagePart
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.BubbleChatQuestion

/** One ask_user question; [type] is "text" (options are suggestions), "single" or "multi". */
internal data class UserQuestion(val id: String, val question: String, val options: List<String>, val type: String)

internal fun UIMessagePart.Tool.userQuestions(): List<UserQuestion> = runCatching {
    inputAsJson().jsonObject["questions"]?.jsonArray?.map { item ->
        val question = item.jsonObject
        UserQuestion(
            id = question["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            question = question["question"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            options = question["options"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull?.takeIf(String::isNotBlank) }.orEmpty(),
            type = question["selection_type"]?.jsonPrimitive?.contentOrNull ?: "text",
        )
    }
}.getOrNull().orEmpty()

/**
 * What the partner needs to know before it carries on, one question at a time: tap an option or write
 * an answer. Choosing the only option of a question moves on by itself; nothing is sent before the last
 * question's button. Replying in the chat instead still settles the question.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ThreadQuestionCard(tool: UIMessagePart.Tool, onAnswer: (String, String) -> Unit, replyHint: Boolean = true) {
    val questions = remember(tool.input) { tool.userQuestions() }
    val draft = remember(tool.toolCallId) { QuestionDrafts.getOrPut(tool.toolCallId) { QuestionDraft() } }
    val answers = draft.answers
    val picked = draft.picked
    var index by draft.index
    var sent by remember(tool.toolCallId) { mutableStateOf(false) }
    fun UserQuestion.answered() = if (type == "multi") !picked[id].isNullOrEmpty() else !answers[id].isNullOrBlank()
    val current = questions.getOrNull(index.coerceIn(0, (questions.size - 1).coerceAtLeast(0)))
    val last = index >= questions.lastIndex
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.size(40.dp)) {
                    Box(contentAlignment = Alignment.Center) { Icon(HugeIcons.BubbleChatQuestion, null, Modifier.size(22.dp)) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (questions.size > 1) stringResource(R.string.im_question_progress, index + 1, questions.size)
                        else stringResource(R.string.im_question_one),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    current?.question?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            if (current != null && !sent) when (current.type) {
                "single" -> QuestionOptions(current.options, selected = setOfNotNull(answers[current.id]), multiple = false) { option ->
                    answers[current.id] = option
                    if (!last) index++
                }
                "multi" -> {
                    val chosen = picked[current.id].orEmpty()
                    QuestionOptions(current.options, selected = chosen, multiple = true) { option ->
                        picked[current.id] = if (option in chosen) chosen - option else chosen + option
                    }
                }
                else -> {
                    if (current.options.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        current.options.forEach { option ->
                            FilterChip(selected = answers[current.id] == option, onClick = { answers[current.id] = option },
                                label = { Text(option) })
                        }
                    }
                    OutlinedTextField(
                        value = answers[current.id].orEmpty(),
                        onValueChange = { answers[current.id] = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.im_question_answer_hint)) },
                        maxLines = 4,
                        shape = MaterialTheme.shapes.medium,
                    )
                }
            }
            if (sent) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.extraLarge) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.im_question_sent))
                    }
                }
            } else if (current != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (index > 0) OutlinedButton(onClick = { index-- }, Modifier.weight(1f).heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.im_question_back))
                    }
                    Button(
                        onClick = {
                            if (!last) {
                                index++
                                return@Button
                            }
                            sent = true
                            QuestionDrafts.remove(tool.toolCallId)
                            onAnswer(tool.toolCallId, buildJsonObject {
                                put("answers", buildJsonObject {
                                    questions.forEach { q ->
                                        put(q.id, JsonPrimitive(if (q.type == "multi") picked[q.id].orEmpty().joinToString(", ") else answers[q.id].orEmpty()))
                                    }
                                })
                            }.toString())
                        },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                        enabled = if (last) questions.all { it.answered() } else current.answered(),
                    ) { Text(stringResource(if (last) R.string.im_question_send else R.string.im_question_next)) }
                }
            }
            if (replyHint && !sent) Text(stringResource(R.string.im_question_reply_hint), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Answers in progress for one call. */
private class QuestionDraft {
    val answers = mutableStateMapOf<String, String>()
    val picked = mutableStateMapOf<String, Set<String>>()
    val index = mutableIntStateOf(0)
}

/**
 * Drafts live as long as the app, so leaving the chat and coming back keeps what was already chosen.
 * A draft is dropped once its answers are sent.
 */
private val QuestionDrafts = mutableMapOf<String, QuestionDraft>()

/** Options as full-width rows, easier to hit than chips; radio for one choice, checkboxes for several. */
@Composable
private fun QuestionOptions(options: List<String>, selected: Set<String>, multiple: Boolean, onSelect: (String) -> Unit) {
    if (options.isEmpty()) return
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.fillMaxWidth()) {
            options.forEachIndexed { i, option ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh)
                val chosen = option in selected
                val rowModifier = if (multiple) Modifier.toggleable(chosen, role = Role.Checkbox) { onSelect(option) }
                    else Modifier.selectable(chosen, role = Role.RadioButton) { onSelect(option) }
                Row(rowModifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    if (multiple) Checkbox(checked = chosen, onCheckedChange = null, Modifier.padding(12.dp))
                    else RadioButton(selected = chosen, onClick = null, Modifier.padding(12.dp))
                    Text(option, Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

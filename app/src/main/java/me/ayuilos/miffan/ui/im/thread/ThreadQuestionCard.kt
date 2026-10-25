package me.ayuilos.miffan.ui.im.thread

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.ayuilos.miffan.R
import me.ayuilos.miffan.ui.im.ImActionCard
import me.ayuilos.miffan.ui.im.ImCardButton
import me.ayuilos.miffan.ui.im.ImCardButtons
import me.ayuilos.miffan.ui.im.ImCardOutlinedButton
import me.ayuilos.miffan.ui.im.ImCardStatus
import me.ayuilos.miffan.ui.im.ImOption
import me.ayuilos.miffan.ui.im.ImOptionList
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
    ImActionCard(
        title = current?.question?.takeIf { it.isNotBlank() },
        overline = if (questions.size > 1) stringResource(R.string.im_question_progress, index + 1, questions.size)
            else stringResource(R.string.im_question_one),
        icon = HugeIcons.BubbleChatQuestion,
    ) {
        if (current != null && !sent) when (current.type) {
            "single" -> ImOptionList(current.options.map { ImOption(it, it) }, selected = setOfNotNull(answers[current.id]),
                onSelect = { option ->
                    answers[current.id] = option
                    if (!last) index++
                })
            "multi" -> {
                val chosen = picked[current.id].orEmpty()
                ImOptionList(current.options.map { ImOption(it, it) }, selected = chosen, multiple = true, onSelect = { option ->
                    picked[current.id] = if (option in chosen) chosen - option else chosen + option
                })
            }
            else -> {
                // Suggested answers fill the field; the owner can still write their own.
                ImOptionList(current.options.map { ImOption(it, it) }, selected = setOfNotNull(answers[current.id]),
                    onSelect = { answers[current.id] = it })
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
            ImCardStatus(stringResource(R.string.im_question_sent))
        } else if (current != null) {
            ImCardButtons {
                if (index > 0) ImCardOutlinedButton(stringResource(R.string.im_question_back), onClick = { index-- })
                ImCardButton(
                    stringResource(if (last) R.string.im_question_send else R.string.im_question_next),
                    enabled = if (last) questions.all { it.answered() } else current.answered(),
                    onClick = {
                        if (!last) {
                            index++
                            return@ImCardButton
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
                )
            }
        }
        if (replyHint && !sent) Text(stringResource(R.string.im_question_reply_hint), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
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

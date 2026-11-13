package me.ayuilos.miffan.ui.im.thread

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.ayuilos.miffan.data.ai.tools.EditableParamKind
import me.ayuilos.miffan.data.ai.tools.appendCardEditNoteToToolOutput
import me.ayuilos.miffan.data.ai.tools.editableApprovalFields
import me.ayuilos.miffan.data.ai.tools.isEditedOnCard
import me.ayuilos.miffan.data.ai.tools.isValidEditedToolInput
import me.ayuilos.miffan.data.ai.tools.withEditedCardInput
import me.rerere.ai.ui.ToolApprovalState
import me.rerere.ai.ui.UIMessagePart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadQuestionCardTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parses legacy ask_user questions with full backward compatibility`() {
        val raw = json.parseToJsonElement(
            """
            {
              "questions": [
                {
                  "id": "framework",
                  "question": "Which framework should we use?",
                  "options": ["Jetpack Compose", "XML Views"],
                  "selection_type": "single"
                },
                {
                  "id": "notes",
                  "question": "Any extra requirements?",
                  "selection_type": "text"
                }
              ]
            }
            """.trimIndent(),
        )

        val spec = parseUserQuestionCardSpec(raw)
        assertNull(spec.title)
        assertEquals(QuestionCardLayout.PAGED, spec.layout)
        assertEquals(2, spec.questions.size)

        val q1 = spec.questions[0]
        assertEquals("framework", q1.id)
        assertEquals("Which framework should we use?", q1.question)
        assertEquals(listOf("Jetpack Compose", "XML Views"), q1.options)
        assertEquals("single", q1.normalizedType)
        assertEquals(2, q1.optionItems.size)
        assertEquals("Jetpack Compose", q1.optionItems[0].value)

        val q2 = spec.questions[1]
        assertEquals("notes", q2.id)
        assertEquals("text", q2.normalizedType)
        assertTrue(q2.options.isEmpty())
    }

    @Test
    fun `parses stacked layout, rich option_items, and pipe-delimited option shorthand`() {
        val raw = json.parseToJsonElement(
            """
            {
              "title": "Trip Preferences",
              "layout": "stacked",
              "questions": [
                {
                  "id": "cabin",
                  "question": "Select cabin class",
                  "selection_type": "single",
                  "option_items": [
                    {
                      "value": "eco",
                      "label": "Economy",
                      "supporting": "Standard legroom, 1 carry-on",
                      "tag": "${'$'}199"
                    },
                    {
                      "value": "biz",
                      "label": "Business",
                      "supporting": "Lie-flat seat, lounge access",
                      "tag": "${'$'}699"
                    }
                  ]
                },
                {
                  "id": "perks",
                  "question": "Add-ons",
                  "selection_type": "multi",
                  "options": [
                    "Wi-Fi | Full flight high-speed | +${'$'}12",
                    "Extra Bag | 23kg checked | +${'$'}35"
                  ],
                  "min_select": 1,
                  "max_select": 2
                }
              ]
            }
            """.trimIndent(),
        )

        val spec = parseUserQuestionCardSpec(raw)
        assertEquals("Trip Preferences", spec.title)
        assertEquals(QuestionCardLayout.STACKED, spec.layout)
        assertEquals(2, spec.questions.size)

        val cabin = spec.questions[0]
        assertEquals("eco", cabin.optionItems[0].value)
        assertEquals("Economy", cabin.optionItems[0].label)
        assertEquals("Standard legroom, 1 carry-on", cabin.optionItems[0].supporting)
        assertEquals("\$199", cabin.optionItems[0].tag)
        assertEquals("Business", cabin.formatDisplayAnswer("biz"))

        val perks = spec.questions[1]
        assertEquals(1, perks.minSelect)
        assertEquals(2, perks.maxSelect)
        assertEquals("Wi-Fi", perks.optionItems[0].value)
        assertEquals("Full flight high-speed", perks.optionItems[0].supporting)
        assertEquals("+\$12", perks.optionItems[0].tag)
    }

    @Test
    fun `handles slider, stepper, toggle, and reorder primitives in QuestionDraft`() {
        val raw = json.parseToJsonElement(
            """
            {
              "layout": "stacked",
              "questions": [
                {
                  "id": "budget",
                  "question": "Daily budget",
                  "selection_type": "slider",
                  "min": 50,
                  "max": 500,
                  "step": 25,
                  "unit": "USD",
                  "default_value": "150"
                },
                {
                  "id": "guests",
                  "question": "Travelers",
                  "selection_type": "stepper",
                  "min": 1,
                  "max": 6,
                  "step": 1,
                  "unit": "people",
                  "default_value": "2"
                },
                {
                  "id": "refundable",
                  "question": "Refundable only",
                  "selection_type": "toggle",
                  "default_value": "true"
                },
                {
                  "id": "priority",
                  "question": "Rank your priorities",
                  "selection_type": "reorder",
                  "options": ["Price", "Speed", "Comfort"]
                }
              ]
            }
            """.trimIndent(),
        )

        val spec = parseUserQuestionCardSpec(raw)
        val draft = QuestionDraft(spec.questions)
        assertTrue(draft.allVisibleAnswered(spec.questions))

        // Reorder: move "Comfort" (index 2) to top (index 0)
        val priorityQuestion = spec.questions[3]
        draft.moveOrderedItem(priorityQuestion, fromIndex = 2, toIndex = 0)
        assertEquals(listOf("Comfort", "Price", "Speed"), draft.orderedAnswer(priorityQuestion))
        assertEquals(
            "Comfort → Price → Speed",
            priorityQuestion.formatDisplayAnswer("Comfort, Price, Speed"),
        )

        // Slider display with unit
        val budgetQuestion = spec.questions[0]
        assertEquals("150 USD", budgetQuestion.formatDisplayAnswer(draft.singleAnswer("budget")))

        val answerPayload = json.parseToJsonElement(draft.buildAnswerJson(spec.questions)).jsonObject
        val answers = answerPayload["answers"]!!.jsonObject
        assertEquals("150", answers["budget"]?.jsonPrimitive?.content)
        assertEquals("2", answers["guests"]?.jsonPrimitive?.content)
        assertEquals("true", answers["refundable"]?.jsonPrimitive?.content)
        assertEquals("Comfort, Price, Speed", answers["priority"]?.jsonPrimitive?.content)
    }

    @Test
    fun `evaluates visible_when conditions and omits hidden questions from submission`() {
        val raw = json.parseToJsonElement(
            """
            {
              "layout": "stacked",
              "questions": [
                {
                  "id": "deploy_mode",
                  "question": "Deployment target",
                  "selection_type": "single",
                  "options": ["Local", "Cloud"],
                  "default_value": "Local"
                },
                {
                  "id": "cloud_region",
                  "question": "Cloud region",
                  "selection_type": "single",
                  "options": ["us-east-1", "ap-northeast-1"],
                  "visible_when": {
                    "question_id": "deploy_mode",
                    "equals": "Cloud"
                  }
                }
              ]
            }
            """.trimIndent(),
        )

        val spec = parseUserQuestionCardSpec(raw)
        val draft = QuestionDraft(spec.questions)

        // Initially deploy_mode = Local, so cloud_region is hidden
        val initialVisible = draft.visibleQuestions(spec.questions)
        assertEquals(listOf("deploy_mode"), initialVisible.map { it.id })
        assertTrue(draft.allVisibleAnswered(initialVisible))

        val localPayload = json.parseToJsonElement(draft.buildAnswerJson(initialVisible)).jsonObject
        val localAnswers = localPayload["answers"]!!.jsonObject
        assertEquals("Local", localAnswers["deploy_mode"]?.jsonPrimitive?.content)
        assertFalse("cloud_region" in localAnswers)

        // Switch deploy_mode to Cloud -> cloud_region becomes visible and required
        draft.setSingle("deploy_mode", "Cloud")
        val cloudVisible = draft.visibleQuestions(spec.questions)
        assertEquals(listOf("deploy_mode", "cloud_region"), cloudVisible.map { it.id })
        assertFalse(draft.allVisibleAnswered(cloudVisible))

        draft.setSingle("cloud_region", "ap-northeast-1")
        assertTrue(draft.allVisibleAnswered(cloudVisible))
    }

    @Test
    fun `enforces min_select and max_select on multi choice primitive`() {
        val q = UserQuestion(
            id = "features",
            question = "Pick 2 features",
            options = listOf("A", "B", "C"),
            type = "multi",
            minSelect = 2,
            maxSelect = 2,
        )
        val draft = QuestionDraft(listOf(q))
        assertFalse(draft.isAnswered(q))

        draft.toggleMulti("features", "A", maxSelect = q.maxSelect)
        assertFalse(draft.isAnswered(q))

        draft.toggleMulti("features", "B", maxSelect = q.maxSelect)
        assertTrue(draft.isAnswered(q))

        // Attempting to select a 3rd item when maxSelect = 2 should be ignored
        draft.toggleMulti("features", "C", maxSelect = q.maxSelect)
        assertEquals(setOf("A", "B"), draft.multiAnswer("features"))
    }

    @Test
    fun `snaps and formats numeric values accurately`() {
        assertEquals(150.0, snapToStep(142.0, min = 50.0, max = 500.0, step = 25.0), 0.001)
        assertEquals("150", formatNumericValue(150.0, step = 25.0))
        assertEquals("0.75", formatNumericValue(0.75, step = 0.05))
    }

    @Test
    fun `validates editable approval schema and blocks security-sensitive tool edits`() {
        // 1. workspace_shell and computer_* and secret tools must never expose editable fields
        val shellInput = buildJsonObject {
            put("command", "ls -la")
            put("cwd", "/workspace")
        }
        assertTrue(editableApprovalFields("workspace_shell", shellInput).isEmpty())
        assertFalse(
            isValidEditedToolInput(
                "workspace_shell",
                shellInput,
                buildJsonObject {
                    put("command", "rm -rf /")
                    put("cwd", "/workspace")
                },
            ),
        )
        assertTrue(editableApprovalFields("computer_click", shellInput).isEmpty())
        assertTrue(editableApprovalFields("connect_provider_api_key", shellInput).isEmpty())

        // 2. workspace_write_file allows safe path and text edits, blocks path traversal
        val writeOriginal = buildJsonObject {
            put("path", "notes/todo.md")
            put("text", "Initial content")
        }
        assertTrue(
            isValidEditedToolInput(
                "workspace_write_file",
                writeOriginal,
                buildJsonObject {
                    put("path", "notes/updated.md")
                    put("text", "Updated content")
                },
            ),
        )
        assertFalse(
            isValidEditedToolInput(
                "workspace_write_file",
                writeOriginal,
                buildJsonObject {
                    put("path", "../etc/passwd")
                    put("text", "Updated content")
                },
            ),
        )

        // 3. workspace_fetch_url allows editing destination_path but locks url
        val fetchOriginal = buildJsonObject {
            put("url", "https://example.com/report.pdf")
            put("destination_path", "downloads/report.pdf")
        }
        assertTrue(
            isValidEditedToolInput(
                "workspace_fetch_url",
                fetchOriginal,
                buildJsonObject {
                    put("url", "https://example.com/report.pdf")
                    put("destination_path", "docs/q3-report.pdf")
                },
            ),
        )
        assertFalse(
            isValidEditedToolInput(
                "workspace_fetch_url",
                fetchOriginal,
                buildJsonObject {
                    put("url", "https://attacker.example/payload.sh")
                    put("destination_path", "downloads/report.pdf")
                },
            ),
        )

        // 4. mcp__* allows editing primitive parameters while locking id/url/token keys
        val mcpOriginal = buildJsonObject {
            put("database_id", "db_123")
            put("title", "Weekly Sync")
            put("archived", false)
            put("priority", 2)
        }
        val mcpSpecs = editableApprovalFields("mcp__notion__create_page", mcpOriginal)
        assertEquals(listOf("title", "archived", "priority"), mcpSpecs.map { it.key })
        assertEquals(
            listOf(EditableParamKind.TEXT, EditableParamKind.BOOLEAN, EditableParamKind.INTEGER),
            mcpSpecs.map { it.kind },
        )
        assertTrue(
            isValidEditedToolInput(
                "mcp__notion__create_page",
                mcpOriginal,
                buildJsonObject {
                    put("database_id", "db_123")
                    put("title", "Updated Weekly Sync")
                    put("archived", true)
                    put("priority", 1)
                },
            ),
        )
        // Changing locked database_id must be rejected
        assertFalse(
            isValidEditedToolInput(
                "mcp__notion__create_page",
                mcpOriginal,
                buildJsonObject {
                    put("database_id", "db_999")
                    put("title", "Updated Weekly Sync")
                    put("archived", false)
                    put("priority", 2)
                },
            ),
        )
    }

    @Test
    fun `manages PrimitiveCardStateStore and ApprovalEditDraft lifecycle`() {
        PrimitiveCardStateStore.clearAll()
        val original = buildJsonObject {
            put("path", "drafts/post.md")
            put("text", "Hello world")
        }
        val draft1 = PrimitiveCardStateStore.approvalDraft("call_1", "workspace_write_file", original)
        val draft2 = PrimitiveCardStateStore.approvalDraft("call_1", "workspace_write_file", original)
        assertSame(draft1, draft2)
        assertFalse(draft1.isModified)
        assertTrue(draft1.isValid)

        draft1.set("text", "Hello Miffan")
        assertTrue(draft1.isModified)
        assertTrue(draft1.isValid)
        assertEquals("Hello Miffan", draft1.buildEditedJson()["text"]?.jsonPrimitive?.content)

        draft1.set("path", "../bad/path")
        assertFalse(draft1.isValid)

        draft1.reset()
        assertFalse(draft1.isModified)
        assertTrue(draft1.isValid)

        PrimitiveCardStateStore.remove("call_1")
        val draft3 = PrimitiveCardStateStore.approvalDraft("call_1", "workspace_write_file", original)
        assertFalse(draft1 === draft3)
    }

    @Test
    fun `formats settled question answers and folded summary`() {
        val inputJson = """
            {
              "title": "Export Settings",
              "layout": "stacked",
              "questions": [
                {
                  "id": "format",
                  "question": "Format",
                  "selection_type": "single",
                  "option_items": [
                    {"value": "pdf", "label": "PDF Document"},
                    {"value": "md", "label": "Markdown"}
                  ]
                },
                {
                  "id": "dpi",
                  "question": "Resolution",
                  "selection_type": "slider",
                  "min": 72,
                  "max": 300,
                  "unit": "dpi"
                }
              ]
            }
        """.trimIndent()
        val answerJson = """{"answers":{"format":"pdf","dpi":"150"}}"""
        val tool = UIMessagePart.Tool(
            toolCallId = "ask_1",
            toolName = "ask_user",
            input = inputJson,
            output = emptyList(),
            approvalState = ToolApprovalState.Answered(answer = answerJson),
        )
        val spec = tool.userQuestionSpec()
        val pairs = extractSettledQuestionAnswers(tool, spec)
        assertEquals(2, pairs.size)
        assertEquals("PDF Document", pairs[0].second)
        assertEquals("150 dpi", pairs[1].second)
        assertEquals(
            "Export Settings — PDF Document · 150 dpi",
            formatSettledQuestionSummary(spec, pairs),
        )
    }

    @Test
    fun `records card edit attribution note and attaches it to tool output`() {
        val originalTool = UIMessagePart.Tool(
            toolCallId = "cal_1",
            toolName = "calendar_create",
            input = """{"title":"产品周会","start":"2026-11-14T15:00","all_day":false}""",
            output = emptyList(),
            approvalState = ToolApprovalState.Pending,
            metadata = buildJsonObject { put("thought_signature", "sig_abc") },
        )
        val editedJson = buildJsonObject {
            put("title", "项目复盘")
            put("start", "2026-11-14T16:00")
            put("all_day", false)
        }

        val updatedTool = originalTool.withEditedCardInput(editedJson)
        // The settled record can tell the user's edit apart from the partner's original call
        assertFalse(originalTool.isEditedOnCard())
        assertTrue(updatedTool.isEditedOnCard())
        assertFalse(originalTool.withEditedCardInput(originalTool.inputAsJson().jsonObject).isEditedOnCard())
        // Preserves existing provider metadata
        assertEquals("sig_abc", updatedTool.metadata?.get("thought_signature")?.jsonPrimitive?.content)

        // Attaches user_modified_on_card to JSON tool output without breaking existing keys
        val rawOutput = listOf(
            UIMessagePart.Text("""{"success":true,"event_id":42,"title":"项目复盘"}"""),
        )
        val annotated = appendCardEditNoteToToolOutput(updatedTool, rawOutput)
        val parsed = json.parseToJsonElement((annotated.first() as UIMessagePart.Text).text).jsonObject
        assertEquals(true, parsed["success"]?.jsonPrimitive?.content?.toBoolean())
        assertEquals("42", parsed["event_id"]?.jsonPrimitive?.content)
        val note = parsed["user_modified_on_card"]?.jsonPrimitive?.content.orEmpty()
        assertTrue(note.contains("changed \"title\" from \"产品周会\" to \"项目复盘\""))
        assertTrue(note.contains("changed \"start\" from \"2026-11-14T15:00\" to \"2026-11-14T16:00\""))
    }
}

package me.ayuilos.miffan.data.ai.tools.local

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.rerere.ai.core.InputSchema
import me.rerere.ai.core.Tool

internal fun buildAskUserTool(): Tool = Tool(
    name = "ask_user",
    description = """
        Ask the user one or more questions or present an interactive form card when you need clarification, preferences, parameters, or confirmation.
        Compose each question using a behavior primitive ('selection_type'):
        - 'single' / 'multi': Single or multi-choice selection (supports 'list', 'chips', or 'segmented' presentation, plus rich option subtitles/tags via 'option_items').
        - 'slider' / 'stepper': Numeric range or +/- stepper control with 'min', 'max', 'step', and 'unit'.
        - 'datetime': Native date/time picker ('mode': 'date', 'time', or 'datetime') with optional quick-pick 'options'.
        - 'toggle': Boolean switch (when no options are given) or multi-switch group (when options are given).
        - 'reorder': Priority ranking list that the user can reorder up/down.
        - 'text': Free-text input with optional tap-to-fill suggestion 'options'.
        Use 'layout': 'stacked' to display multiple related controls together in one form card (supports 'visible_when' conditional fields), or 'paged' (default) to ask one question at a time.
        The answers will be returned as a JSON object mapping question IDs to the user's responses.
    """.trimIndent().replace("\n", " "),
    parameters = {
        InputSchema.Obj(
            properties = buildJsonObject {
                put("title", buildJsonObject {
                    put("type", "string")
                    put("description", "Optional overall card title (especially useful when layout is 'stacked')")
                })
                put("layout", buildJsonObject {
                    put("type", "string")
                    put(
                        "enum",
                        buildJsonArray {
                            add("paged")
                            add("stacked")
                        }
                    )
                    put(
                        "description",
                        "Card layout: 'paged' (wizard style, one question at a time, default) or 'stacked' (all visible questions combined in a single form card)"
                    )
                })
                put("questions", buildJsonObject {
                    put("type", "array")
                    put("description", "List of questions or interactive behavior blocks to present to the user")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("id", buildJsonObject {
                                put("type", "string")
                                put("description", "Unique identifier for this question")
                            })
                            put("question", buildJsonObject {
                                put("type", "string")
                                put("description", "The question text or field title to display to the user")
                            })
                            put("description", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional helper text or subtitle explaining the question")
                            })
                            put("options", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Optional list of option strings (used for single/multi/toggle/reorder choices, or as quick-pick suggestions for text/slider/stepper/datetime)"
                                )
                                put("items", buildJsonObject {
                                    put("type", "string")
                                })
                            })
                            put("option_items", buildJsonObject {
                                put("type", "array")
                                put(
                                    "description",
                                    "Optional list of rich option objects with subtitle ('supporting') and badge ('tag'). When provided, takes precedence over 'options'."
                                )
                                put("items", buildJsonObject {
                                    put("type", "object")
                                    put("properties", buildJsonObject {
                                        put("value", buildJsonObject {
                                            put("type", "string")
                                            put("description", "Option value returned in answers (defaults to label)")
                                        })
                                        put("label", buildJsonObject {
                                            put("type", "string")
                                            put("description", "Primary display title of the option")
                                        })
                                        put("supporting", buildJsonObject {
                                            put("type", "string")
                                            put("description", "Optional secondary description shown under the label")
                                        })
                                        put("tag", buildJsonObject {
                                            put("type", "string")
                                            put("description", "Optional short badge shown on the option (e.g. 'Recommended')")
                                        })
                                    })
                                    put("required", buildJsonArray {
                                        add("label")
                                    })
                                })
                            })
                            put("selection_type", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("text")
                                        add("single")
                                        add("multi")
                                        add("slider")
                                        add("stepper")
                                        add("datetime")
                                        add("toggle")
                                        add("reorder")
                                    }
                                )
                                put(
                                    "description",
                                    "Behavior primitive: text (free text input, default), single (pick one), multi (pick multiple), slider (range slider), stepper (+/- numeric stepper), datetime (date/time picker), toggle (switch or switch group), reorder (rank items by priority)"
                                )
                            })
                            put("presentation", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("list")
                                        add("chips")
                                        add("segmented")
                                    }
                                )
                                put(
                                    "description",
                                    "Visual style for options: 'list' (vertical cards, default), 'chips' (compact filter chips), or 'segmented' (horizontal segment buttons for 2-4 single-choice options)"
                                )
                            })
                            put("placeholder", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional custom placeholder for text input")
                            })
                            put("default_value", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional initial value (e.g. default option value, initial number for slider/stepper, 'true'/'false' for toggle, or ISO date/time)")
                            })
                            put("required", buildJsonObject {
                                put("type", "boolean")
                                put("description", "Whether this question must be answered before submitting (default true)")
                            })
                            put("allow_custom", buildJsonObject {
                                put("type", "boolean")
                                put("description", "When true on 'single' or 'multi', adds a text field so the user can also type a custom answer")
                            })
                            put("min", buildJsonObject {
                                put("type", "number")
                                put("description", "Minimum value for 'slider' or 'stepper' (default 0)")
                            })
                            put("max", buildJsonObject {
                                put("type", "number")
                                put("description", "Maximum value for 'slider' or 'stepper' (default 100)")
                            })
                            put("step", buildJsonObject {
                                put("type", "number")
                                put("description", "Step increment for 'slider' or 'stepper' (default 1)")
                            })
                            put("unit", buildJsonObject {
                                put("type", "string")
                                put("description", "Optional unit label for 'slider' or 'stepper' (e.g. '%', 'min', 'kcal')")
                            })
                            put("mode", buildJsonObject {
                                put("type", "string")
                                put(
                                    "enum",
                                    buildJsonArray {
                                        add("date")
                                        add("time")
                                        add("datetime")
                                    }
                                )
                                put("description", "Picker mode when selection_type is 'datetime' (default 'datetime')")
                            })
                            put("min_select", buildJsonObject {
                                put("type", "integer")
                                put("description", "Minimum number of selections required for 'multi'")
                            })
                            put("max_select", buildJsonObject {
                                put("type", "integer")
                                put("description", "Maximum number of selections allowed for 'multi'")
                            })
                            put("visible_when", buildJsonObject {
                                put("type", "object")
                                put("description", "Optional condition to show this question only when another question has a specific answer")
                                put("properties", buildJsonObject {
                                    put("id", buildJsonObject {
                                        put("type", "string")
                                        put("description", "ID of the question this condition depends on")
                                    })
                                    put("equals", buildJsonObject {
                                        put("type", "string")
                                        put("description", "Show when the target question's answer equals (or includes) this value")
                                    })
                                    put("not_equals", buildJsonObject {
                                        put("type", "string")
                                        put("description", "Show when the target question's answer does not equal this value")
                                    })
                                    put("in", buildJsonObject {
                                        put("type", "array")
                                        put("description", "Show when the target question's answer matches any of these values")
                                        put("items", buildJsonObject {
                                            put("type", "string")
                                        })
                                    })
                                })
                                put("required", buildJsonArray {
                                    add("id")
                                })
                            })
                        })
                        put("required", buildJsonArray {
                            add("id")
                            add("question")
                        })
                    })
                })
            },
            required = listOf("questions")
        )
    },
    needsApproval = { true },
    execute = {
        error("ask_user tool should be handled by HITL flow")
    }
)

package me.rerere.rikkahub.data.ai.tools

/**
 * Trusted, read-only instructions for the extension-management tools bundled with the app.
 *
 * This definition intentionally contains no filesystem references. Additional documents are
 * loaded from [BuiltInSkillDefinition.bundledFiles] by `use_skill`.
 */
val extensionManagementBuiltInSkill = BuiltInSkillDefinition(
    name = "extension-management",
    description = "Safely inspect, preview, and apply supported RikkaHub extension configuration changes.",
    body = """
        Use this skill when the user asks to inspect, configure, enable, disable, or bind RikkaHub extensions.

        Follow this workflow in order:

        1. Call `extensions_catalog` to inspect current extensions, assistants, and binding state. Never assume identifiers or current configuration.
        2. Call `extensions_preview_changes` with the requested operations. Treat its normalized preview and validation result as authoritative. If validation fails, correct the request or explain the unsupported part.
        3. Clearly summarize the preview for the user, including additions, updates, bindings, and unbindings.
        4. Call `extensions_apply_changes` with the opaque `previewId` returned by `extensions_preview_changes`. The apply tool itself pauses for the user's explicit approval before executing. Never invent or alter a previewId, and never treat broad or earlier authorization as approval for the pending tool call.
        5. Report the apply result accurately. Skill, MCP, tool, prompt, and workspace binding changes may only become active on the next conversation turn.

        Safety rules:

        - Never ask tools to reveal secrets. Catalog and previews must keep API keys, authorization headers, OAuth tokens, cookies, and similar credentials redacted.
        - Never reconstruct a redacted value or place one in chat, a preview, or a tool argument.
        - Never bypass preview or approval, including when the user says to apply changes in the same message. The preview must be shown before approval is accepted.
        - Do not claim a change succeeded until `extensions_apply_changes` reports success.
        - Do not translate an unsupported request into a broader or destructive operation.

        Read [the MVP operation reference](references/mvp-operations.md) before preparing changes.
    """.trimIndent(),
    bundledFiles = mapOf(
        "references/mvp-operations.md" to """
            # Extension-management MVP operations

            The MVP supports:

            - Inspecting the extension catalog, assistants, and current binding state.
            - Creating or updating quick messages.
            - Creating or updating mode prompt injections.
            - Binding or unbinding existing quick messages, mode prompt injections, lorebooks, skills, and MCP servers for an assistant.
            - Setting or clearing an assistant workspace.
            - Enabling or disabling an existing supported local tool for an assistant.
            - Enabling, disabling, or configuring an assistant's external web search option without exposing credentials.

            The MVP does not support:

            - Deleting skills or workspaces, or deleting their files.
            - Creating, editing, or deleting MCP server definitions.
            - Reading, setting, or changing credentials, secret headers, API keys, or OAuth state.
            - Granting Android system permissions.
            - Installing workspace root filesystems or running setup commands.

            If only part of a request is supported, preview only the supported operations and explicitly list what was omitted. Do not substitute a different operation.
        """.trimIndent(),
    ),
)

# AI Extension Management Development Plan

## Goal

Add an opt-in, built-in extension-management capability that lets an assistant inspect Miffan's
extension configuration, prepare validated changes, and apply those changes only after explicit user
approval.

The implementation must keep the existing manual UI as a fallback and must not expose structured
credential fields to the model. Catalog responses omit full prompt/quick-message bodies to reduce the
risk of sending sensitive user-authored content.

## Product principles

1. A Skill contains workflow instructions; Kotlin Tools perform reads and writes.
2. Extension management is disabled for an assistant by default.
3. Read-only inventory operations may run automatically.
4. Every write operation produces a human-readable preview and requires approval.
5. Tools accept narrow, typed operations instead of a replacement `Settings` document.
6. UI and AI tools share one domain service so reference cleanup and validation cannot diverge.
7. MCP connection fields and credential values are never returned to the model; prompt bodies are
   omitted from catalog responses.

## Scope

### Milestone 1: safe MVP

- Opt-in extension-management permission per assistant.
- Read-only catalog of assistants, quick messages, mode injections, lorebooks, skills, MCP servers,
  local tools, and workspaces.
- Create or update quick messages and mode injections.
- Bind or unbind existing quick messages, mode injections, lorebooks, and MCP servers from an
  assistant.
- Select or clear an assistant workspace.
- Enable or disable supported local tools and external web search.
- Preview and validate a proposed change before applying it.
- Require the existing tool-approval flow for all writes.
- Unit tests for validation, secret redaction, reference lookup, and mutations.

### Milestone 2: managed resources

- Lorebook and lorebook-entry CRUD.
- Skill creation/editing and trusted import from file or GitHub.
- Custom tool-call UI showing a localized, human-readable change summary.
- Operation history and best-effort undo for settings-only changes.

### Milestone 3: privileged operations

- MCP server create/update/delete, connectivity testing, and explicit secret-entry flows.
- OAuth hand-off without exposing tokens to the model.
- Workspace create/rename, Rootfs installation, and guarded deletion.
- Auditing, rollback, rate limits, and additional security hardening.

## Architecture

### Domain layer

`ExtensionManagementService` owns catalog generation, validation, preview, and mutations. It delegates
to `SettingsStore` and `WorkspaceRepository`, and never returns secret values.

The service exposes stable resource references (`type` + `id` or skill name). Name lookup may be used
for convenience, but a preview resolves it to a stable identifier before applying changes.

### AI layer

The built-in management Skill explains the supported resource types and the required
inspect-preview-apply workflow. Management tools are injected only when the current assistant has
explicitly enabled the capability.

Initial tool surface:

- `extensions_catalog`: return a credential-redacted inventory without prompt bodies.
- `extensions_preview_changes`: validate operations and return a normalized change summary.
- `extensions_apply_changes`: apply a server-issued, one-use preview capability after revalidation;
  the capability binds the canonical summary rendered in the approval UI and always requires
  approval.

### UI layer

Milestone 1 reuses the existing tool approval state machine. A switch in assistant local-tool settings
controls access. Milestone 2 adds a dedicated extension-management tool renderer and an entry from the
Extensions page.

## Workstreams

### A. Domain service

- Define serializable catalog, operation, preview, and result models.
- Implement redacted inventory generation.
- Implement narrow mutation functions using a compare-and-update settings transaction.
- Validate assistant/resource existence and enum values.
- Preserve unrelated settings and clean dangling references.
- Add unit tests.

### B. Capability gate and built-in Skill

- Add an assistant-level extension-management option, default off.
- Surface it in assistant local-tool settings with a clear security warning.
- Add built-in Skill instructions that cannot be edited or deleted as a user Skill.
- Ensure ordinary assistants do not receive management schemas or instructions.

### C. Management tools and chat integration

- Define the three Tool schemas and JSON result format.
- Register tools in `ChatService` only when opted in and the model supports tool calling.
- Mark the apply tool as always requiring approval.
- Ensure a configuration change is reported as taking effect on the next model turn where necessary.

### D. UX and verification

- Add a readable tool renderer for catalog/preview/apply results.
- Add tests for approval gating and ChatService tool registration.
- Run focused unit tests, then the app unit-test suite and a Debug compile.

## Milestone 1 acceptance criteria

1. Existing assistants behave exactly as before after upgrade.
2. Enabling extension management exposes the built-in instructions and management tools only to that
   assistant.
3. The catalog contains no secret header values, OAuth tokens, or client secrets.
4. A user request such as "create a study mode and bind it to this assistant" produces a preview before
   any write.
5. Rejecting approval leaves settings unchanged.
6. Approving applies only the displayed operations and preserves unrelated settings.
7. Invalid or stale identifiers fail without partial settings mutation.
8. Existing manual extension-management screens remain functional.
9. The pending approval card renders canonical server-issued summaries; altering that display data
   invalidates the one-use preview capability.

## Deferred risks

- Imported Skills are untrusted prompt content and must not grant themselves management access.
- MCP URLs can target local-network resources and require stronger confirmation before automated CRUD.
- Android runtime and special permissions cannot be silently granted by a Tool.
- Workspace and Rootfs deletion are destructive filesystem operations and need a separate confirmation
  design.
- Configuration tools are snapshotted for a generation; newly enabled tools generally become available
  on the next model turn.

# AI Skill Installation Development Plan

## Goal

Extend the guarded extension-management capability so an opted-in assistant can discover a Skill
listed on skills.sh, preview the exact GitHub snapshot, workspace destination, and risks, and install
it into the assistant's bound workspace only after the user approves the canonical preview.

A workspace Skill remains untrusted prompt content. It is discovered automatically for assistants
bound to that workspace on their next conversation turn and is never loaded during installation.

## External protocol findings

- skills.sh pages identify GitHub-backed skills as
  `https://skills.sh/{owner}/{repository}/{skill-slug}`.
- The documented `/api/v1` API requires a Vercel OIDC bearer token. A native Android application
  must not embed such a credential.
- The open-source skills CLI currently uses the unauthenticated `/api/search` endpoint for
  discovery. Miffan may use it as an explicitly unstable, best-effort search provider, but
  installation must not trust its metadata or depend on it being available.
- Actual installation content is fetched from GitHub's public API and raw-content host at a pinned
  commit. Miffan never executes `npx`, Git, setup scripts, or any file from the package.

Primary references:

- https://skills.sh/docs
- https://skills.sh/docs/api
- https://github.com/vercel-labs/skills/blob/main/src/find.ts
- https://github.com/vercel-labs/skills/blob/main/src/blob.ts
- https://docs.github.com/en/rest/git/trees

## MVP workflow

1. `skills_search` queries the best-effort skills.sh discovery endpoint and returns only canonical
   ids, GitHub sources, install counts, and skills.sh URLs.
2. `skills_preview_install` accepts an exact supported skills.sh URL and fixes the destination to
   the current assistant's bound workspace. It fails when no valid workspace is bound.
3. The source client resolves the GitHub repository's default branch to a commit SHA, obtains its
   tree, finds one unambiguous Skill directory, and downloads text files from that fixed commit.
4. The domain service validates the package and stores the exact approved bytes in a short-lived,
   one-use in-memory preview.
5. `skills_apply_install` pauses in the existing tool-approval UI. The pending card renders summary
   data bound into the preview capability rather than model-provided text.
6. Approval revalidates the workspace identity and atomically installs the cached bytes under
   `/workspace/.miffan/skills` if the name is still unused.

## Hard security boundaries

- HTTPS only; structured skills.sh and GitHub hosts only; no userinfo, custom ports, fragments, or
  lookalike domains.
- GitHub requests use an application-controlled host allowlist and do not follow arbitrary
  redirects.
- Reject truncated trees, symlinks, submodules, Git LFS pointers, binary/NUL content, invalid UTF-8,
  ambiguous Skill roots, path traversal, absolute paths, backslashes, control characters, duplicate
  normalized paths, and excessive depth.
- A repository-root `SKILL.md` is accepted only when it is the repository's sole tree entry; this
  prevents a successful install that silently drops referenced support files.
- Limit package file count, per-file bytes, `SKILL.md` bytes, and total bytes for mobile use.
- Require a root `SKILL.md`, valid frontmatter, and a strict lowercase kebab-case Skill name.
- Reject existing names and reserved built-in names. MVP has no overwrite or update operation.
- Never accept a model-selected filesystem path or arbitrary workspace id. The current assistant's
  workspace id is supplied by the app, fixed into the preview, and revalidated during apply.
- Publish through the workspace mutation lock, enforce workspace storage limits, reject symbolic
  links in the destination chain, and keep staged bytes outside the shell-visible files directory.
- Search text, remote descriptions, README text, and Skill bodies are untrusted and never inserted
  into approval summaries or management system prompts.
- `allowed-tools` is displayed only as an untrusted declaration; it is not described as an enforced
  sandbox permission.
- Audit results and install counts are advisory signals, never proof that a Skill is safe.

## Workstreams

### Source adapters

- Best-effort skills.sh catalog search with strict result decoding and graceful unavailability.
- Pinned GitHub Tree/raw fetcher with host, response-size, and redirect checks.

### Domain service

- Package validation and deterministic SHA-256 manifest digest.
- Stable application-generated risk categories and canonical approval summary.
- Ten-minute, one-use preview capability containing no remote prompt body.
- Atomic no-overwrite installation into the bound workspace with identity revalidation.

### AI and UI integration

- Register all installation tools only behind the existing assistant extension-management opt-in
  and model tool-call ability.
- Extend the trusted built-in Skill with the discover-preview-approve-install workflow.
- Add compact search, preview, apply, error, and pending-approval renderers.
- Escape all Skill metadata inserted into the `<available_skills>` system-prompt block.

## Acceptance criteria

1. An opted-out assistant receives no Skill installation schemas or instructions.
2. A request to install any Skill can search, choose a canonical result, preview it, and pause for
   approval without running a remote command.
3. Rejecting approval leaves the Skill directory and assistant settings unchanged.
4. Approval installs exactly the bytes and commit shown in the preview, once, without overwrite.
5. New installation never creates a global binding; workspace discovery makes the Skill available
   only to assistants bound to that workspace, starting on the next turn.
6. Traversal, symlink, submodule, LFS, binary, ambiguous, oversized, malformed, and conflicting
   packages fail before preview authorization.
7. The model and approval card never receive remote Skill bodies or descriptions.
8. A changed or forged preview capability cannot install any package.
9. Existing global bindings are copied one-way into their assistant's bound workspace; global Skill
   directories remain only as hidden recovery sources and never enter new conversation contexts.

## Deferred

- Private repositories and GitHub authentication.
- Stable hosted skills.sh `/api/v1` proxy and security-audit integration.
- Well-known sources and Skill packs.
- Updates, overwrite, provenance UI, uninstall, rollback history, and automatic telemetry.
- Cross-workspace copying during installation.

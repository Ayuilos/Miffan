# Remote Miffan Help Skill Development Plan

## Goal

Give every tool-capable assistant a small, trusted built-in Skill that can answer questions about
using Miffan without bundling the complete product manual in the APK. The Skill catalog and loading
instructions remain in the app; versioned help documents are published with the Miffan website and
loaded only when a question needs them.

The first release is intentionally retrieval-based rather than embedding-based. Topic metadata and
keywords select a small document, and the selected document is returned as a normal client Tool
result. This keeps the behavior provider-neutral and avoids adding a local model or vector database.

## Product principles

1. The built-in Skill describes when and how to ask for help; Kotlin code performs network access,
   validation, caching, and locale selection.
2. Remote help is read-only, requires no Tool approval, and is available to every assistant whose
   model supports client Tools.
3. The app never asks a model provider to fetch a URL. It fetches a fixed first-party HTTPS origin
   itself and returns the selected text to the provider.
4. Only the selected topic enters the model context. The complete manual is never injected into the
   system prompt or a single Tool result.
5. Published documents are versioned and immutable. The small manifest can change independently and
   points to the current compatible document set.
6. A network failure should produce an actionable Tool result and use a previously validated cached
   copy when one exists.
7. Remote content is trusted application content. Redirects, origins, paths, response sizes, and
   optional content hashes must therefore be validated before text reaches the model.

## Initial scope

### Website

- Publish a machine-readable manifest below `/skills/miffan-help/`.
- Publish a first Chinese document set grouped by user task rather than source-code module.
- Include stable topic identifiers, discovery keywords, relative content paths, and SHA-256 hashes.
- Give the manifest a short cache lifetime and versioned documents a long immutable cache lifetime.
- Keep a contributor-facing README next to the content contract.

### Android

- Add the always-available `miffan-help` built-in Skill definition.
- Add a provider-neutral `miffan_help` Tool with a narrow input schema.
- Fetch only from the configured Miffan website origin.
- Select locale using exact locale, language match, then the manifest default.
- Select a topic by stable identifier when supplied, with query/keyword matching as a fallback.
- Validate paths, response size, content type where practical, and a published SHA-256 hash.
- Cache validated topic documents in application cache storage.
- Return structured status and document metadata together with the Markdown body.

### Documentation and validation

- Document the content protocol, publishing workflow, cache behavior, and versioning policy.
- Unit-test manifest parsing, locale/topic selection, unsafe paths, hash mismatch, successful loading,
  cache fallback, and useful failure results.
- Build the website and run focused Android tests before the feature is considered ready.

## Content roadmap

The first published set groups closely related tasks into a small number of documents so retrieval
remains predictable. Later releases can split a document while retaining aliases for its former
topic ID.

### P0: first-use and failure recovery

- Getting started, provider credentials, and selecting the global chat model.
- Provider and model setup, model abilities, and connection tests.
- Chat basics, model switching, message operations, and queued follow-ups.
- API, proxy, model, streaming, and Tool-call troubleshooting.

### P1: core Miffan workflows

- Assistant creation/import, basic settings, prompts, memory, and custom requests.
- Search-service configuration and per-chat local/model search selection.
- Attachments, supported document extraction, media, ASR, and TTS.
- Quick messages, prompt injections, lorebooks, MCP, and local Tools.
- Workspace creation/binding, scoped files, terminal, and workspace-owned Skills.
- Local, WebDAV, and S3 backup/restore, including deliberately excluded credentials and Workspace
  data.

### P2: complete product coverage

- Conversation history, branches, folders, favorites, export, and statistics.
- Preferences, appearance, notifications, network settings, storage management, and updates.
- Web server, translator, image generation, and rich-content rendering.

Every topic should record exact UI labels, aliases users are likely to say, capability prerequisites,
common mistakes, related topics, and the app versions for which its navigation paths were verified.

## Request flow

```text
User asks how to use Miffan
        |
        v
Model sees miffan-help in the built-in Skill catalog
        |
        v
Model calls use_skill(name = "miffan-help")
        |
        v
Skill instructions tell it to call miffan_help with a topic or query
        |
        v
Android loads the manifest and one matching topic from the fixed website origin
        |
        v
Validated Markdown is returned as a Tool result
        |
        v
Model answers in the user's language and states uncertainty when the document is incomplete
```

This two-stage flow preserves the existing Skill abstraction: `use_skill` performs progressive
disclosure of the small workflow instructions, while `miffan_help` performs remote retrieval. The
remote document is data for the Skill, not an executable Skill package.

## Content contract

The root manifest is JSON with an integer `schemaVersion`, a string `skillVersion`, a
`defaultLocale`, supported `locales`, and `topics`. Topic paths are same-origin relative paths and may
contain a `{locale}` placeholder. A topic has a stable `id`, human-facing metadata, discovery
keywords, a `path`, and a locale-to-lowercase-hexadecimal-digest `sha256` map. The manifest may
also advertise an `appVersionRange`; clients that do not yet evaluate it keep ignoring it as an
optional compatibility hint.

Clients must ignore unknown JSON fields so the manifest can gain optional metadata without a schema
version bump. Removing or changing required fields, changing path interpretation, or changing hash
semantics requires a new schema version. A published versioned document must never be edited in
place; corrections publish a new Skill version and update the manifest.

## Trust and failure boundaries

- Accept HTTPS and the configured Miffan host only.
- Resolve relative topic paths against that origin and reject user info, host changes, traversal,
  fragments, and unexpected schemes.
- Bound manifest and document response sizes before reading them into memory.
- Validate a topic hash before caching or returning it.
- Do not execute code blocks, follow links in Markdown, or treat fetched text as a new Tool schema.
- Make remote instructions explicitly subordinate to application and conversation safety rules.
- Avoid logging document bodies or full network error responses.
- Prefer a stale validated topic over no answer, but mark the result as cached/stale.

## Rollout

### Milestone 1: static Chinese MVP

- One root manifest and one `zh-CN` topic set on the existing Cloudflare-hosted website.
- Built-in Skill and Tool enabled for tool-capable models.
- Deterministic topic/keyword routing, disk cache, integrity checks, and unit tests.
- No settings screen and no embeddings.

### Milestone 2: product context and localization

- Add English and other locales without changing stable topic IDs.
- Pass safe page identifiers and selected feature flags to improve retrieval.
- Add a Help entry point that starts a conversation with a suggested question.
- Add anonymized, opt-in retrieval diagnostics if needed to find documentation gaps.

### Milestone 3: larger knowledge base

- Generate manifest hashes and validate internal links in CI.
- Add app-version compatibility ranges per topic/document set.
- Keep multiple document sets in the manifest, or introduce app-version-specific manifests, so an
  older APK can continue selecting its last compatible immutable topic set after the latest docs
  move on.
- Consider server-side lexical or semantic search only after deterministic routing quality is measured.
- Add signed manifests if the threat model or hosting workflow later requires protection beyond HTTPS
  and repository/deployment access controls.

## Acceptance criteria

1. The release APK does not contain the full help document set.
2. A tool-capable assistant can discover `miffan-help` without an assistant setting or workspace.
3. Asking a covered usage question loads no more than the manifest plus one topic document.
4. OpenAI-, Google-, Anthropic-, and compatible providers use the same client Tool implementation.
5. A changed host, unsafe path, oversized response, or hash mismatch is rejected and not cached.
6. A validated cached topic can answer the same question when the network is unavailable.
7. Unsupported topics and first-use offline failures return clear, non-crashing Tool results.
8. Website and focused Android builds/tests pass.

## Deferred decisions

- A user-visible switch to disable remote help. The initial Tool only runs when the model selects it,
  and makes a first-party request with no conversation text beyond the narrow topic/query parameters.
- Vector search. It adds storage, operational, and privacy complexity that is not justified for the
  initial number of topics.
- Screenshots and video. They materially increase transfer size and require a separate rendering and
  accessibility contract.
- Editing help content from the app. The website repository remains the source of truth for the MVP.
- Transactional fallback across document-set updates. The MVP validates and caches the manifest and
  each topic independently; an atomic Cloudflare static deployment plus pre-deploy hash validation
  makes partial publication unlikely, but a later client should retain the previous manifest/topic
  pair until the first topic from a new set has also been validated.
- Fully localized manifest metadata. Schema v1 shares topic titles, descriptions, and keywords while
  localizing bodies; a future schema should localize discovery metadata before adding languages whose
  queries cannot be routed reliably by shared aliases.

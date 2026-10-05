---
name: locale-tui-localization
description: Use this skill when users request i18n/localization updates for Android string resources: adding localized keys, translating strings.xml, filling missing translations, or moving hardcoded UI text into resources.
---

# Localization

You write the translations yourself, or delegate them to a model you choose. `locale-tui` only
validates and writes `strings.xml`; this workflow never calls a translation API and needs no
`OPENAI_API_KEY`.

## When to use

- Adding a user-visible string (all configured languages, unless the user says otherwise).
- Translating or fixing `strings.xml` across locales.
- Finding untranslated text: missing keys, or Chinese literals hardcoded in Kotlin.

## Commands

Run from the repository root.

```bash
uv run --directory locale-tui src/main.py missing [-m app] [--json]   # missing keys, Chinese English sources
uv run --directory locale-tui src/main.py hardcoded [-m app]          # Chinese string literals in source
uv run --directory locale-tui src/main.py apply strings.json [-m app] [--dry-run]
```

Modules and languages come from `locale-tui/config.yml` (currently `values`, `values-zh`,
`values-zh-rTW`, `values-ja`, `values-ko-rKR`, `values-ru`; modules `app`, `search`, ...).

`apply` takes `{"key": {"values": "English", "values-zh": "…", …}}`. New keys need every
language; existing keys may update only some. It escapes `'` and `"`, rejects placeholder
mismatches (`%1$s`, `%d`, …), unknown language codes and Chinese English sources, and writes each
file once. Write the JSON under the job's temporary directory, not the repository.

## Workflow

1. **Collect** what needs text: new keys from the task, `missing`, or `hardcoded`. Of the
   `hardcoded` hits, move only user-visible text (labels, messages, content descriptions,
   accessibility labels) into resources. Leave search keywords, provider and brand names, prompts
   and data meant for models, log messages and debug-only pages in code.
2. **Name keys** by page or feature prefix (`setting_page_`, `im_`, `whale_theme_`, …), reusing
   an existing key when the text and meaning match.
3. **Translate.** Choose who does it by size and nuance:
   - Up to about 30 strings: translate them yourself in the same turn.
   - Larger batches: split them by feature and hand each group to a sub-agent, choosing its
     model. A strong mid-tier model (for example `sonnet` in Claude Code) suits ordinary UI copy.
     Use the most capable model for brand voice, character personas, onboarding and promotional
     text. An agent without sub-agents translates the batches itself.
     Avoid small models for Japanese and Korean UI copy. Sub-agents return JSON only; you merge
     their results and run `apply` once, so no two writers touch the same `strings.xml`.
   - Give whoever translates the glossary below plus the surrounding screen, so short labels are
     translated in context.
4. **Apply** with `--dry-run` first, then for real. Replace the literals in code with
   `stringResource(R.string.…)` (Compose) or `context.getString(…)` outside composition.
5. **Verify**: `missing` reports the module complete, and `./gradlew :app:lintDebug
   :app:assembleDebug` passes. Report the files and keys changed.

## Translation guidelines

- App context: an AI chat client for many model providers, with an IM-style "Easy chat" mode and
  companion characters (Miffan the rice bowl, the "Blue Whale" 蓝色大肥鱼).
- Keep placeholders, `\n` and escapes exactly. Keep UI text concise; match the source's tone
  (friendly, plain, no exclamation spam).
- Do not translate brand or technical names: OpenAI, Anthropic, OpenRouter, MCP, API, Token.
- Chinese AI terms: prompt → 提示词 (not 提示), context → 上下文, model → 模型,
  inference → 推理. Easy chat → 轻松聊天 / 輕鬆聊天 / かんたんチャット / 간편 채팅 / Простой чат.
- Traditional Chinese uses Taiwan vocabulary (資料, 設定, 訊息), not converted Simplified.
- Language names in pickers are endonyms and stay untranslated (`translatable="false"`).

## Constraints

- English (`values`) is the source; never leave Chinese as the English text.
- If the user asks to skip localization, add only `values` (and `values-zh` when useful) and say so.
- Never commit API keys or translation scratch files.

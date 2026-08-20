<div align="center">
  <img src="docs/assets/branding/miffan-icon.svg" alt="Miffan app icon" width="120" />
  <h1>Miffan</h1>

An independent, community-maintained open-source Android AI chat client.

[简体中文](README_ZH_CN.md) | [繁體中文](README_ZH_TW.md) | English
</div>

Miffan is a community fork based on [RikkaHub](https://github.com/rikkahub/rikkahub). It keeps the multi-provider chat experience while maintaining an independent application identity, release line, signing certificate, and visual brand.

## Download

Download releases from [GitHub Releases](https://github.com/Ayuilos/Miffan/releases).

- Application ID: `me.ayuilos.miffan.app`
- Deep-link scheme: `miffan://`
- Miffan can be installed alongside upstream RikkaHub.
- App data and settings are independent; use export/import to migrate content.

## Features

- Material 3 interface with dark mode
- Multiple OpenAI-, Gemini-, and Claude-compatible providers
- OpenAI Codex subscription sign-in
- Images, documents, PDF, DOCX, and other multimodal inputs
- Local workspaces and terminal tools
- MCP, search, memory, translation, and prompt extensions
- Embedded web client
- Message branches, Markdown, code highlighting, formulas, tables, and Mermaid

## Build

```bash
./gradlew assembleDebug
./gradlew test
./gradlew lint
```

`app/google-services.json` is optional. Builds without an authorized configuration disable analytics and crash reporting.

Production release instructions are documented in [docs/releasing.md](docs/releasing.md).

## License and attribution

Miffan remains licensed under the [GNU Affero General Public License v3.0](LICENSE). Copyright notices and upstream attribution are retained as required by the license.

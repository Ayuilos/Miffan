# Miffan Feature Compatibility Matrix

Last reviewed: August 24, 2026

This matrix describes capabilities implemented by the Miffan client. A check mark means the client
has an integration; the selected provider, endpoint, account, and model must also support the feature.
Compatible gateways sometimes expose only a subset of the underlying protocol.

Legend: **✓** built in · **Conditional** depends on provider/model · **—** not applicable

## Model protocols

| Integration | Authentication | Streaming | Reasoning | Tool calls | Multimodal input | Model discovery |
| --- | --- | --- | --- | --- | --- | --- |
| OpenAI Chat Completions-compatible | API key or custom headers | ✓ | Conditional | Conditional | Conditional | Conditional |
| OpenAI Responses-compatible | API key or custom headers | ✓ | Conditional | Conditional | Conditional | Conditional |
| OpenAI Codex | Eligible ChatGPT subscription sign-in | ✓ | Conditional | Conditional | Conditional | Account-provided models |
| Google Gemini API | API key | ✓ | Conditional | Conditional | Conditional | ✓ |
| Google Vertex AI | Service-account configuration | ✓ | Conditional | Conditional | Conditional | Configured models |
| Anthropic Claude-compatible | API key or custom headers | ✓ | Conditional | Conditional | Conditional | Conditional |

Across these integrations, Miffan supports custom base URLs, request paths, headers and body fields,
HTTP/SOCKS5 proxies, connection tests, per-model capability settings, context limits, and optional
balance queries where an endpoint provides them.

## Content and conversation

| Capability | Support | Notes |
| --- | --- | --- |
| Text chat and streaming | ✓ | Editing, regeneration, branches, folders, favorites, and full-text search |
| Reasoning display and tool results | Conditional | Requires a compatible model/endpoint |
| Images, audio, video, and file inputs | Conditional | Depends on model modality and provider protocol |
| Document text extraction | ✓ | PDF, DOCX, PPTX, and EPUB are parsed locally when used as prompts |
| Rich response rendering | ✓ | Markdown, HTML, code highlighting, LaTeX, tables, Mermaid, images, and diffs |
| Image generation | Conditional | Requires a configured model/endpoint with image output |
| Conversation export | ✓ | Markdown and image export |
| Android share and selected-text translation | ✓ | Share content into an assistant or translate selected text in a floating window |

## Search and page retrieval

| Category | Built-in integrations |
| --- | --- |
| Search providers | Bing, RikkaHub, Zhipu, Doubao, Tavily, Exa, SearXNG, LinkUp, Brave, Metaso, Ollama, Perplexity, Firecrawl, Jina, Bocha, Grok, Tinyfish, Serper |
| Custom search | JavaScript adapter |
| Model-native search | Conditional; currently exposed for compatible Gemini models |
| Page retrieval | Provider-specific scraping where available, plus configurable content services |

Search results and queries are sent to the selected search endpoint. Keys, quotas, geographic
availability, and scraping behavior differ by service.

## Speech and audio

| Capability | Built-in integrations |
| --- | --- |
| Speech recognition | OpenAI Realtime, DashScope, Volcengine, MiMo, Step |
| Text-to-speech | Android system, OpenAI, OpenRouter, Gemini, MiniMax, Qwen, Groq, xAI, MiMo, ElevenLabs, Step, Fish Audio |
| Assistant audio playback | ✓ |

## Assistants, tools, and extensions

| Capability | Support | Notes |
| --- | --- | --- |
| Isolated assistant configuration | ✓ | Model, prompt, memory, parameters, history, tools, visual identity, and injections |
| MCP | ✓ | SSE and Streamable HTTP, OAuth, and per-assistant server selection |
| Skills | ✓ | Workspace-owned Skills under `/workspace/.miffan/skills` are discovered automatically; legacy global bindings migrate one-way on use |
| Built-in local tools | ✓ | Time, clipboard, JavaScript, TTS, user questions, screen time, calendar, and extension management |
| Local Linux workspace | ✓ | Per-workspace files, editor, terminal, and AI file/shell tools; PRoot is not a security container |
| Web access to conversations | ✓ | Optional embedded server, localhost/LAN modes, password authentication, and mDNS |

## Storage and portability

| Capability | Support |
| --- | --- |
| Local conversations, settings, and files | ✓ |
| Selective local backup and restore | ✓ |
| WebDAV backup | ✓ |
| S3-compatible backup | ✓ |
| Chatbox import | Providers and conversations |
| Cherry Studio import | Providers |
| RikkaHub migration | Compatible backup import; the two apps remain isolated and can coexist |

For data-flow details and security boundaries, see [PRIVACY.md](../PRIVACY.md) and
[SECURITY.md](../SECURITY.md).

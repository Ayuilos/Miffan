# Miffan Privacy Notice

Last updated: August 24, 2026

Miffan is an open-source Android client, not a hosted AI service. The project does not operate a
central server that receives every conversation. Most app data stays on your device, while content
is sent to the model providers and optional services that you choose to configure.

This notice describes the behavior of the source code in this repository. A distributor may ship a
different build configuration, so verify the origin and settings of the APK you install.

## Data Miffan handles

| Data | Stored or processed | When it leaves the device |
| --- | --- | --- |
| Conversations, branches, titles, memories, assistant prompts, and usage metadata | Local app database and app-private files | When needed for a request to a configured model or tool service |
| Images, audio, documents, and workspace files | App-private files or a user-selected location | When attached to a model request, uploaded to a configured service, synchronized, or explicitly shared |
| Provider credentials and settings | Android app-private settings storage | Sent only to the configured endpoint for authentication; Miffan does not add an app-specific encrypted vault |
| Search, speech, translation, MCP, and tool inputs | Local processing where supported | Sent to the specific service selected for that feature |
| Backups | Local file, WebDAV, or S3-compatible storage chosen by the user | When the user creates or schedules that backup |
| Diagnostic information | Local request/crash logs when enabled or generated | Only when a configured analytics/crash service is enabled or the user shares a report |

Miffan does not sell personal data. The project maintainer normally cannot see your local
conversations or credentials. Providers, MCP servers, search services, speech services, sync
destinations, and other endpoints have their own logging, retention, training, and billing policies.
Review them before use.

## Network services

Miffan makes network requests only for app features, including:

- model generation, model discovery, account authentication, and optional balance queries;
- web search, page retrieval, speech recognition, text-to-speech, translation, and image generation;
- MCP servers, Skills discovery or installation, and other user-enabled tools;
- local web access, WebDAV or S3-compatible backups, and update checks;
- optional analytics or crash reporting in builds with an authorized Firebase configuration.

A model request can include system prompts, assistant instructions, conversation history,
attachments, tool schemas, tool results, memories, and workspace context. Minimize what you send and
use separate assistants or conversations when different privacy boundaries are needed.

## Device permissions

Miffan requests Android permissions only for related features. Depending on device and Android
version, these include camera, microphone, notifications, local-network discovery, calendar access,
and usage-access statistics. Sensitive or special access still requires the Android permission flow
or a system settings grant. Selected-text translation receives the selected text only after the user
chooses Miffan from Android's text action menu.

## Logs, analytics, and crash reports

Request logging is optional. Although authorization and other sensitive headers are redacted, a
request body can contain prompts, conversation text, tool data, or attachment metadata. Do not enable
request logs for sensitive work, and review logs before sharing them.

Firebase components are included only when an authorized `app/google-services.json` is present at
build time. Builds without it keep Firebase analytics and crash reporting disabled. When analytics is
available and enabled, Miffan records product events rather than prompt contents. A local crash
handler can also retain a stack trace on the device for manual review and sharing.

## Storage, backup, and deletion

Android's app sandbox protects local data from ordinary apps, but a rooted or compromised device,
debug access, malicious same-UID code, or an exposed backup can bypass that boundary. Depending on
Android version, device vendor, and backup settings, Android backup or device transfer may copy
eligible app data. Treat the device, screen lock, backup account, exported backup files, and remote
backup destination as part of your security boundary.

You can delete conversations, assistants, files, providers, logs, and workspaces from Miffan. To
remove all local app data, use Android's **Clear storage** control or uninstall the app. Copies already
sent to a provider or stored in a local/remote backup must be deleted from that service or location
separately and remain subject to its retention rules.

## Changes and questions

Material changes to this notice will be committed to the repository. For privacy questions, open a
[GitHub issue](https://github.com/Ayuilos/Miffan/issues) without including credentials or private
conversation content. Report security vulnerabilities through the private process in
[SECURITY.md](SECURITY.md).

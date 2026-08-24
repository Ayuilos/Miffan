# Security Policy

## Supported versions

Security fixes are targeted at the latest GitHub release and the current `master` branch. Older APKs
may not receive fixes; reproduce an issue on a current build when possible.

## Reporting a vulnerability

GitHub private vulnerability reporting is not currently enabled for this repository. For a
vulnerability that could expose credentials, conversations, files, device data, or remote systems,
open a minimal [GitHub issue](https://github.com/Ayuilos/Miffan/issues/new) titled
`[Security] Private contact request`. State only the affected version and how the maintainer can
reach you; do not disclose reproduction steps, secrets, or private user data in public.

After a private channel is established, include the impact, prerequisites, reproduction steps, and
any suggested mitigation. Miffan is a community project and cannot promise a response SLA, but
reports will be triaged and coordinated on a best-effort basis.

## Security model

Miffan is a powerful client that connects device data to user-selected services. Its primary local
boundary is the Android application sandbox. It is not an end-to-end encrypted messaging service,
a credential vault, or a sandbox for arbitrary untrusted code.

### Credentials and network endpoints

- Provider keys, OAuth tokens, MCP credentials, proxy credentials, web-server passwords, and backup
  credentials are stored in Android app-private settings. Miffan does not add an app-specific
  encrypted vault, so protect the device, backup account, and exported backups.
- Custom base URLs, proxies, MCP servers, search adapters, and sync destinations can receive data or
  alter responses. Use HTTPS and endpoints you trust.
- Miffan redacts sensitive headers in request logs, but request bodies can still contain prompts,
  files, and tool data. Keep request logging off for sensitive work.

### Tools, MCP, and Skills

- Tool calls can read or change data within the permissions granted to the app. Review approval
  cards and enable only the tools an assistant needs.
- MCP servers are external trust boundaries. Their tools and OAuth flows are controlled by the
  configured server.
- Installed Skills are untrusted prompt content. Guarded installation validates package structure
  and does not run package setup scripts, but an enabled Skill can still influence model behavior.
  Review `SKILL.md` and supporting files before binding it to an assistant.

### Local Linux workspaces

The PRoot workspace is a compatibility environment, not a VM or kernel-enforced container. Shell
processes run under Miffan's Android UID and inherit the app's permissions. A malicious command may
attempt to access or damage other app-private data, use the network, or consume device resources.
Run only trusted commands and read the detailed [workspace security model](workspace/SECURITY.md).

### Local web server

Localhost-only mode is the safest default. LAN mode makes the service reachable from the local
network; use a strong unique password, avoid untrusted Wi-Fi, and stop the server when it is not
needed. The embedded server does not by itself make an untrusted network safe or replace TLS at an
external reverse proxy.

### Builds and updates

Install APKs from the project's [GitHub Releases](https://github.com/Ayuilos/Miffan/releases) or
build from reviewed source. Android treats differently signed builds as different trust identities.
Do not install a replacement APK from an unknown source merely because it uses the Miffan name or
icon.

## Recommended user hardening

1. Keep Android and Miffan current, use a screen lock, and avoid rooted production devices.
2. Give each provider a scoped key with spending and rate limits where supported.
3. Separate sensitive assistants from assistants that use external tools, MCP, Skills, or search.
4. Review domains, permission prompts, tool approvals, request logs, and backup contents.
5. Keep the local web server bound to localhost unless LAN access is necessary.
6. Revoke provider and OAuth credentials immediately if a device or backup is exposed.

For the corresponding data-flow and retention disclosures, see [PRIVACY.md](PRIVACY.md).

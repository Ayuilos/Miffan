# Upstream synchronization policy

Miffan is an independent project. The `upstream` remote (`rikkahub/rikkahub`) is a code input that maintainers may inspect and selectively incorporate; it is not the source of Miffan product versions, release cadence, tags, branding, package identity, or signing policy.

## Sync flow

1. Configure the remote once with `git config remote.upstream.tagOpt --no-tags`, then fetch the upstream code branch explicitly with `git fetch --no-tags upstream master`.
2. Create a short-lived branch named `upstream-sync/<date-or-reference>` from current Miffan `master`.
3. Review the upstream range and select the desired commits or merge point. Prefer a topology-preserving merge when provenance matters; use cherry-picks only when the selected scope is intentionally narrow.
4. Resolve conflicts in favor of Miffan product identity and compatibility rules. Pay special attention to `applicationId`, deep links, signing setup, version fields, update URLs, database migrations, package namespaces, branding, and Miffan-specific UI behavior.
5. Open an `upstream-sync` pull request into `master`. Record the upstream base and end commits, selected or excluded areas, conflict resolutions, and user-visible effects.
6. Require normal CI (`test`, `lint`, and `assembleDebug`) and any focused compatibility tests for the affected modules before merge.
7. Merge only after Miffan review. Delete the short-lived sync branch when normal repository policy permits.

Upstream tags must not trigger Miffan builds or releases. Do not mirror, push, rewrite, or automatically propagate them to `origin`. A sync may inform release notes, but it never determines the next Miffan SemVer or `versionCode`.

The persistent `remote.upstream.tagOpt` setting prevents ordinary fetches from following upstream tags. Keep `--no-tags` on explicit sync fetches as an additional safeguard.

## Release notes and provenance

For every Miffan release, classify the commit range by topology and keep these sections separate in both languages:

- **Miffan changes**: product work authored or adapted specifically for Miffan.
- **Synced from RikkaHub**: user-visible improvements actually incorporated by upstream syncs.

Do not list an upstream commit merely because it exists in the upstream range; verify that its effective diff is present after conflict resolution. Keep commit hashes and merge mechanics in the pull request or audit notes, not in user-facing Release Notes.

The independent Miffan version communicates Miffan compatibility and maturity only. Upstream provenance belongs in the sync PR and Release Notes, never in the version string.

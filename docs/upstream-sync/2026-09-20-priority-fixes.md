# Selective upstream sync — 2026-09-20 priority fixes

Miffan base: `915a4435c`.
Upstream common base: `08c2648b5a58c79e5eec9d39af1ffc85eb07e939`.
Reviewed upstream end: `6adc0cf184b784c68b8737d4e55daabe4cfbcde7`.
Previous selective adaptation: `2026-09-17.md`.

Selected upstream patches, adapted to the Miffan namespace and existing UI:

- `6e98691cd578580a446ad48efdee69f161cbac60`: omit the unsupported `name` field from Chat Completions tool-result messages; update the request serialization regression test.
- `7c1629d06fa92e609d9e9dd8db36a28a8fb5b1ea`: encode cropped chat attachments as JPEG at quality 90, matching the existing `.jpg` output file and avoiding slow PNG encoding.
- `445341e91a63fd7731ab61d7d715d3a12e155267`: complete favorite deletion before showing Undo and restore it in sequence; reset the swipe state before removal so an undone card remains visible.
- `b7f06db1642ff37b43b9855b73c98411f6353371`: isolate the reasoning timeline in an offscreen layer and clear its line behind the node, without painting an opaque square over a translucent card.

Deferred for separate review: conversation-session lifecycle (`4a3eefc1`), fork-title numbering (`458c16df`), Step-5 registry (`c3d6867c`), workspace multi-selection export (`21448350`), provider-default policy, dependency changes, and all upstream version bumps. Previously adapted patches remain excluded.

No Miffan package identity, signing, version, database schema, migrations, update endpoints, workspace permission boundary, or character rendering changes are intended in this sync. The four selected patches belong under **Synced from RikkaHub** in release notes; because they are namespace adaptations, the commit topology alone does not identify their provenance.

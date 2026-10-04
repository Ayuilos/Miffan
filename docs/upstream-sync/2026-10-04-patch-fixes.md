# Selective upstream sync — 2026-10-04 patch fixes

Miffan release baseline: `3.4.3` (`ade291c011ca7ca026a239ab75d67fe4531d138f`).
Miffan feature commit before adaptation: `044c8626`.
Previous reviewed upstream end: `ed3569c70`.
Reviewed upstream end: `85af5b910`.

Selected patches, adapted only for Miffan's application namespace:

- `a6dbb8cd2ba8302bd02fb2dbfdb9e06060e7c979`: explicitly disable strict mode for Responses API function tools so optional arguments remain optional; include the upstream request regression test.
- `2d5c51bd522917104dbe8b1667bede3c0fa4ebc5`: catch oversized clipboard transaction failures while copying request bodies and display feedback instead of crashing.
- `620e38ccb2858400b8360d51470862d0e3416b9c`: give the JSON string detail sheet its own selection container, avoiding coordinate conversion across independent windows when selecting text.

Deferred: media-generation refactoring, workspace distribution changes, provider registry/dependency updates, OAuth changes and version bumps. Fork-title patch `95fed05e` assumes upstream's copied-title policy; Miffan currently generates a fresh title for a fork, so applying it would change product behavior beyond a patch fix.

The selected effective diffs have been checked against the source patches. No package identity, signing, database schema, migrations, updater endpoint, character rendering or workspace permission changes come from this sync. These three adapted patches belong under **Synced from RikkaHub**; topology alone cannot identify their provenance.

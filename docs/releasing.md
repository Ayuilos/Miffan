# Miffan release process

Miffan is an independent project with its own version line, package identity, signing trust anchor, and GitHub Releases. RikkaHub remains an optional source of selected code improvements, not a source of Miffan product versions. See [upstream-sync.md](upstream-sync.md) for the upstream policy.

## Application identity and channels

Official Miffan releases keep all of these values stable:

- namespace: `me.ayuilos.miffan`
- application ID: `me.ayuilos.miffan.app`
- deep-link scheme: `miffan://`
- production signing certificate: the trust anchor recorded below

The release channels are intentionally isolated:

| Channel | Gradle build type | Application ID | Signing | Distribution |
| --- | --- | --- | --- | --- |
| Official / release candidate | `release` | `me.ayuilos.miffan.app` | Miffan production certificate | Verified draft GitHub Release, then explicit publication approval |
| Nightly | `nightly` | `me.ayuilos.miffan.app.nightly` | Ephemeral CI debug certificate | Disposable GitHub Actions artifact only |
| Local debug | `debug` | `me.ayuilos.miffan.app.debug` | Android debug certificate | Local or CI artifact |

Nightly never reads production signing secrets, never updates the fixed `nightly` tag, and never creates a GitHub Release. Its different application ID prevents a nightly APK from replacing or upgrading an official installation. Each hosted CI run uses a temporary debug signer that may differ from another run, so Nightly is a disposable test artifact and is not guaranteed to upgrade in place over an earlier Nightly. A fresh install may be required.

## Independent version scheme

New Miffan releases use standard Semantic Versioning without a `v` prefix:

- stable: `MAJOR.MINOR.PATCH`, for example `3.0.0`
- prerelease: `MAJOR.MINOR.PATCH-PRERELEASE`, for example `3.0.0-rc.1`
- tag and Release title: exactly the same as `versionName`

Do not encode an upstream RikkaHub version or sync revision in a new Miffan version. Historical versions such as `2.4.11-miffan.1` remain supported only as updater and upgrade-acceptance inputs.

`versionCode` is an Android upgrade counter, independent of SemVer. Every installable release APK that uses `me.ayuilos.miffan.app` and the production certificate must have a `versionCode` strictly greater than every previously distributed upgrade-capable APK. It does not need to encode the SemVer components.

The first candidate in the independent line is:

- `versionName`: `3.0.0-rc.1`
- `versionCode`: `178002`

The first stable release in the independent line is:

- `versionName`: `3.0.0`
- `versionCode`: `178003`

Future official releases must use a `versionCode` greater than `178003`.

## Update compatibility and safety boundary

The in-app updater accepts both generations of version tags:

- historical formal tags: `x.y.z-miffan.n`
- independent SemVer tags: `x.y.z` and syntactically valid prerelease tags such as `x.y.z-rc.n`

The official update source still ignores drafts and prereleases. GitHub's latest-release API excludes them, and Miffan also rejects release metadata marked `draft` or `prerelease`. If the API is unavailable, the Atom fallback skips standard SemVer prereleases and selects the next historical formal tag or stable SemVer tag.

Consequences for the transition:

- `2.4.11-miffan.1` can be upgraded in place to the production-signed `3.0.0-rc.1` APK by an explicit RC installation.
- The RC understands both version generations, so it will recognize a future stable `3.0.0` release.
- The official updater does not automatically offer the RC; prerelease participation is opt-in.

Official arm64 assets use `Miffan-<version>-arm64-v8a.apk`. The checksum companion uses the same name plus `.sha256`.

### In-app download sources

The updater reads `https://downloads.ayuilos.me/latest.json` by default. Users can set a trusted HTTPS directory in **Settings → Preferences → Network → APK 下载源**. An empty setting uses the official source, including after upgrading an older installation or restoring an older backup. Changing the source starts a fresh update check; the existing temporary update-check pause still applies.

Source order is **custom directory (if configured) → official directory → GitHub latest-release API → GitHub Atom feed**. A valid manifest is sufficient on its own; GitHub connectivity is not required for mirror updates. Each metadata request has a 12-second total timeout. A healthy but stale mirror is not compared with GitHub, so mirror operators must keep their manifest current.

Mirrors must expose the following paths relative to their configured directory (subdirectories are supported):

- `latest.json`: the same manifest as the official download site, with `version`, ISO-8601 `publishedAt`, `architecture` (`arm64-v8a`), `fileName`, positive `size`, `sha256`, `downloadUrl`, `checksumUrl`, and `releaseUrl`.
- `releases/<version>/Miffan-<version>-arm64-v8a.apk`: the original signed release APK.
- The corresponding `.apk.sha256` checksum file.

The manifest can retain the official download/checksum URLs or use matching URLs under the custom directory. The app rebases the versioned APK path onto the selected directory, so an unchanged copy of the official manifest works on a mirror. Arbitrary external APK URLs, prerelease tags, other architectures, and mismatched filenames are rejected. An optional `changelog` field supplies Markdown release notes; otherwise the details sheet links to GitHub for notes. Manifest checksum syntax is validated; installation authenticity remains enforced by Android's package signature check.

After metadata succeeds, APK download failures try the remaining directories and then the **same version's** GitHub asset. Pending download IDs and remaining URLs are saved locally; a permission-protected system download receiver handles terminal network/HTTP failures even after the settings screen is closed. Cancellation, paused downloads, storage errors, and existing destination files do not trigger source switching. There is no automatic APK installation, and the GitHub Release page remains available for manual recovery. System download scheduling/retries determine when an asynchronous failure becomes terminal; the 12-second timeout applies only to metadata requests.

## Production signing trust anchor

The first Miffan production certificate was created on 2026-08-19. It must remain unchanged for every official update:

- Subject: `CN=Miffan Release, OU=Android, O=Ayuilos, C=CN`
- SHA-256: `6C:4B:84:1A:D2:EF:14:8C:88:8D:38:41:1F:02:68:C6:C6:FA:90:8B:5E:0D:00:9E:A1:87:BF:AF:2F:1F:9D:31`
- Validity: 2026-08-19 through 2126-07-26

The authorized local keystore remains outside the repository at `~/Library/Application Support/Miffan/signing/miffan-release.jks`. Its password is stored in the macOS login Keychain under the service `Miffan Release Keystore Password`. Back up the keystore and credential separately.

GitHub copies of the keystore and signing properties must be stored as `KEY_BASE64` and `SIGNING_CONFIG` secrets in the `production` Environment. Do not put production signing secrets in repository-level nightly configuration, change their values in source, or reuse the certificate for `.nightly` builds.

After the Environment copies have been verified, delete repository-level secrets with the same `KEY_BASE64` and `SIGNING_CONFIG` names. Keeping those duplicates would let a future workflow read production signing material without passing through the `production` Environment approval gate.

For an authorized local build, configure the ignored root `local.properties`:

```properties
storeFile=<path-to-existing-keystore>
storePassword=<keystore-password>
keyAlias=<key-alias>
keyPassword=<key-password>
```

When these four entries are absent, `assembleRelease` may produce unsigned verification APKs. They are not production releases and must not be distributed.

`app/google-services.json` is optional. Only a configuration registered for `me.ayuilos.miffan.app` may be used; an upstream or nightly application ID configuration must not be substituted.

## Prepare a release

1. Start from an up-to-date, clean `master` and create a dedicated release branch.
2. Select and record the exact target commit. Do not silently build a moving branch head.
3. Set an independent SemVer `versionName` and a strictly increasing `versionCode` in `app/build.gradle.kts`.
4. Add bilingual notes at `docs/releases/<version>.md` using [the template](releases/TEMPLATE.md). Keep Miffan-owned changes separate from improvements incorporated from RikkaHub.
5. Require the pull-request CI workflow to pass `test`, `lint`, and `assembleDebug` before merging the target commit. CI does not repeat the same full verification after the protected PR is merged to `master`; it can still be started manually when needed.
6. Review the in-place upgrade acceptance below for any release that shares the official package name.

No upstream tag is a Miffan release trigger. Do not fetch-and-push, mirror, or automatically propagate upstream tags.

## Build a verified draft Release

Run `.github/workflows/release.yml` manually and provide all three explicit inputs:

- the target commit's full 40-character SHA;
- the exact SemVer `versionName`;
- the exact Android `versionCode`.

The workflow is gated by the `production` Environment. It verifies that the requested commit is the merge commit of a PR into `master`, and that the source PR head has a successful run of `.github/workflows/ci.yml`. It also verifies that the source version and notes agree, refuses to replace an existing tag, builds the production release without repeating the PR's full tests and debug lint, and checks:

- application ID is `me.ayuilos.miffan.app`;
- `versionName` and `versionCode` match the approved inputs;
- the staged APK is arm64-only;
- the signer SHA-256 matches the Miffan production trust anchor;
- the asset name follows the official convention.

`assembleRelease` still performs R8 optimization, packaging, and signing. Lint remains a required pull-request check, and the workflow never skips the artifact, signer, ABI, checksum, or provenance checks.

It then computes SHA-256, creates GitHub build provenance for the verified APK with `actions/attest@v4`, uploads the verified APK/checksum as a workflow artifact, and prepares a draft GitHub Release at the requested commit. A version containing a prerelease component is marked as a prerelease. The workflow does not make the draft public; publication remains a separate approval.

Download the APK and verify its build provenance before publication:

```bash
gh attestation verify <apk> -R Ayuilos/Miffan
```

Treat the signer verification, SHA-256 checksum, and build-provenance verification as separate required checks.

For local verification without publication:

```bash
./gradlew test
./gradlew lint
./gradlew assembleRelease

mkdir -p app/release
cp app/build/outputs/apk/release/app-arm64-v8a-release.apk \
  app/release/Miffan-<version>-arm64-v8a.apk
shasum -a 256 app/release/Miffan-<version>-arm64-v8a.apk
```

Never upload the universal or x86_64 APK to an official Release.

## In-place upgrade acceptance from 2.4.11-miffan.1

This release migration does not change the app's data ownership, database compatibility policy, or migration strategy. Validate upgrades with a disposable device or emulator that represents real user state:

1. Install the official, production-signed `2.4.11-miffan.1` APK.
2. Create representative local state: a conversation, assistant/provider settings without live credentials, and a workspace reference or backup marker.
3. Record the package, version, certificate digest, and a non-sensitive inventory of that state.
4. Install the candidate with `adb install -r Miffan-<version>-arm64-v8a.apk`. Do not uninstall the old app and do not clear app data.
5. Confirm Android reports the same package `me.ayuilos.miffan.app`, the higher `versionCode`, and the expected `versionName`.
6. Launch Miffan and confirm the existing conversations, settings, files, and database open normally. Exercise startup and any existing Room migrations; do not add a release-only data reset or migration bypass.
7. Confirm the updater in `3.0.0-rc.1` can parse and rank future `3.0.0` above the installed RC.

Also verify a fresh install, coexistence with RikkaHub, deep links, and Android 8.0 minimum support. Any certificate mismatch or data loss blocks publication.

## Publish after explicit approval

Review the draft's target commit, bilingual notes, signer, APK, and checksum. Only after explicit authorization should the draft be published. Use the version as the title and tag without a `v` prefix. Do not move, replace, delete, or reuse an existing release tag.

## Repository settings requiring manual administration

These GitHub settings are deliberately outside the source change and require a repository administrator:

- add a `master` ruleset requiring pull requests and the `CI / verify` status check;
- add a tag ruleset for independent Miffan SemVer tags and restrict tag creation/deletion;
- enable Release immutability and decide how to handle the existing non-immutable Releases;
- create or review the `production` Environment, copy or migrate `KEY_BASE64` and `SIGNING_CONFIG` there, verify the workflow can access the Environment copies, delete the repository-level secrets with those same names, and require authorized reviewers;
- verify Actions permissions allow the production workflow to prepare drafts only when explicitly dispatched.

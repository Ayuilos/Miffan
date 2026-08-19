# Miffan APK release process

Formal Miffan releases use the version in `app/build.gradle.kts` and a tag with the exact version number, without a `v` prefix. This project distributes APKs directly and does not publish an AAB to Google Play.

## Application identity

Miffan is installed as an application independent from upstream RikkaHub:

- namespace: `me.ayuilos.miffan`
- application ID: `me.ayuilos.miffan.app`
- deep-link scheme: `miffan://`

The separate application ID lets Miffan and upstream RikkaHub coexist on the same Android device even though they use different signing certificates. Their private data, permissions, databases, and settings are separate. Users who want to migrate existing data must export it from the old app and import it into Miffan.

## Version scheme

Keep the Miffan version distinct from upstream:

- `versionName`: `<upstream-version>-miffan.<fork-revision>`
- `versionCode`: `<upstream-version-code> * 1000 + <fork-revision>`

For example, the first Miffan release based on upstream `2.4.10` (`versionCode` 177) is `2.4.10-miffan.1` with `versionCode` 177001. Increment only the fork revision for releases on the same upstream base. After rebasing to a newer upstream release, use that release's version and code as the new base.

Miffan builds disable the upstream update feed. Configure a Miffan-owned feed before re-enabling automatic update checks.

## Prepare

1. Start from an up-to-date, clean `master` worktree and inspect the commits since the upstream base tag and the latest Miffan release tag.
2. Increment both `versionName` and `versionCode` according to the scheme above.
3. Add bilingual release notes to `docs/releases/<version>.md`. Keep the user-facing change list to no more than ten items and avoid implementation details.
4. Confirm the release notes with the user before creating a GitHub Release.

## Configure signing

Release signing credentials are local or CI secrets and must never be committed. For a local build, place the authorized keystore outside version control and configure these entries in the ignored root `local.properties` file:

```properties
storeFile=<path-to-existing-keystore>
storePassword=<keystore-password>
keyAlias=<key-alias>
keyPassword=<key-password>
```

The first Miffan release needs a dedicated signing key stored outside version control. Keep that key and its credentials backed up: every later Miffan APK must use the same certificate to upgrade an installed Miffan release without data loss.

If a new key must be created, generate it interactively outside the repository and omit passwords from shell history:

```bash
keytool -genkeypair -v \
  -keystore /secure/backup/location/miffan-release.jks \
  -alias miffan \
  -keyalg RSA -keysize 4096 -validity 10000
```

Record the certificate digest with `keytool -list -v`, back up the keystore and credentials separately, then configure the ignored `local.properties` entries above. Do not commit either file.

An Ayuilos production certificate was prepared before the Miffan rebrand. If it is selected as the first published Miffan certificate, its SHA-256 digest must remain the trust anchor for every later Miffan update:

- SHA-256: `6F:AF:FB:7E:4C:68:78:E1:59:87:7F:5C:EA:7B:80:06:85:EE:91:1F:41:9D:29:FB:BA:8D:79:DD:D1:19:2E:BC`
- Validity: 2026-08-19 through 2126-07-26

`app/google-services.json` is optional. When an authorized configuration for `me.ayuilos.miffan.app` is present, analytics and crash reporting are enabled. When it is absent, those integrations are disabled. A configuration registered for the upstream application ID must not be reused.

When the four signing entries are absent, `assembleRelease` produces unsigned APKs for build verification. Those artifacts are not installable production releases and must not be published.

## Verify and build

```bash
./gradlew test
./gradlew lint
./gradlew assembleRelease
```

Before distributing an APK, verify all of the following:

- the package name is `me.ayuilos.miffan.app`;
- the version name and code match the planned release;
- only the `arm64-v8a` APK is staged for the formal release;
- native libraries are compatible with 16 KB page-size devices;
- the APK signature matches the certificate selected for Miffan releases;
- the APK installs alongside upstream RikkaHub;
- the APK upgrades the previous Miffan release without removing app data.

The CI workflow verifies the arm64 APK signature before uploading it. An unsigned build therefore remains a local verification artifact instead of being published accidentally.

Record the staged APK's SHA-256 digest. Rename and copy the arm64 artifact to the ignored staging directory:

```bash
mkdir -p app/release
cp app/build/outputs/apk/release/app-arm64-v8a-release.apk app/release/Miffan-<version>-arm64-v8a.apk
shasum -a 256 app/release/Miffan-<version>-arm64-v8a.apk
```

## Publish after approval

Publishing is a separate, explicitly authorized action. After the user approves the notes, create the release with the version as the title and tag (no `v` prefix), attach only the staged arm64 APK, and use `docs/releases/<version>.md` as the description. Do not upload the universal or x86_64 APK.

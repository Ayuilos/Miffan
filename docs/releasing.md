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

Miffan checks the latest formal release published in `Ayuilos/Miffan` on GitHub. Keep the release tag identical to `versionName` and attach the arm64 APK using the name `Miffan-<version>-arm64-v8a.apk`; the in-app updater uses those GitHub Releases as its source of truth.

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

The first Miffan production certificate was created on 2026-08-19. Its identity must remain the trust anchor for every later Miffan update:

- Subject: `CN=Miffan Release, OU=Android, O=Ayuilos, C=CN`
- SHA-256: `6C:4B:84:1A:D2:EF:14:8C:88:8D:38:41:1F:02:68:C6:C6:FA:90:8B:5E:0D:00:9E:A1:87:BF:AF:2F:1F:9D:31`
- Validity: 2026-08-19 through 2126-07-26

The authorized local keystore is stored at `~/Library/Application Support/Miffan/signing/miffan-release.jks`; its password is stored in the macOS login Keychain under the service `Miffan Release Keystore Password`. Back up the keystore and its credential separately in secure, recoverable locations. The GitHub Actions copies are configured as the `KEY_BASE64` and `SIGNING_CONFIG` repository secrets.

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

# APK-only fork release process

Formal fork releases use the version in `app/build.gradle.kts` and a tag with the exact version number, without a `v` prefix. This project distributes APKs directly and does not build or publish an AAB for Google Play.

## Version scheme

Keep the fork version distinct from upstream:

- `versionName`: `<upstream-version>-ayuilos.<fork-revision>`
- `versionCode`: `<upstream-version-code> * 1000 + <fork-revision>`

For example, the first fork release based on upstream `2.4.10` (`versionCode` 177) is `2.4.10-ayuilos.1` with `versionCode` 177001. Increment only the fork revision for local releases on the same upstream base. After rebasing to a newer upstream release, use that release's version and code as the new base.

Fork builds disable the upstream update feed because an upstream APK signed with a different certificate cannot upgrade this installation. Configure a fork-owned feed before re-enabling automatic update checks.

## Prepare

1. Start from an up-to-date, clean `master` worktree and inspect the commits since the upstream base tag and the latest fork release tag.
2. Increment both `versionName` and `versionCode` according to the fork scheme above.
3. Add bilingual release notes to `docs/releases/<version>.md`. Keep the user-facing change list to no more than ten items and avoid implementation details.
4. Confirm the release notes with the user before creating a GitHub Release.

## Configure signing

Release signing credentials are local or CI secrets and must never be committed. For a local build, place the existing authorized keystore outside version control and configure these entries in the ignored root `local.properties` file:

```properties
storeFile=<path-to-existing-keystore>
storePassword=<keystore-password>
keyAlias=<key-alias>
keyPassword=<key-password>
```

The first fork release needs a dedicated, authorized signing key stored outside version control. Keep that key and its credentials backed up: every later APK must use the same certificate to upgrade the installed fork without data loss. Because the package name remains `me.rerere.rikkahub`, an upstream installation signed with the official certificate must be uninstalled before installing this fork; the two variants cannot coexist.

After the fork owner explicitly approves creating its first production key, generate it interactively outside the repository and omit passwords from shell history:

```bash
keytool -genkeypair -v \
  -keystore /secure/backup/location/rikkahub-ayuilos.jks \
  -alias rikkahub-ayuilos \
  -keyalg RSA -keysize 4096 -validity 10000
```

Record the certificate digest with `keytool -list -v`, back up the keystore and credentials separately, then configure the ignored `local.properties` entries above. Do not commit either file.

The initial Ayuilos fork production certificate was created for `2.4.10-ayuilos.1` and is the trust anchor for all future upgrades:

- Subject: `CN=Ayuilos RikkaHub Fork, OU=Android, O=Ayuilos, C=CN`
- SHA-256: `6F:AF:FB:7E:4C:68:78:E1:59:87:7F:5C:EA:7B:80:06:85:EE:91:1F:41:9D:29:FB:BA:8D:79:DD:D1:19:2E:BC`
- Validity: 2026-08-19 through 2126-07-26

`app/google-services.json` is optional and unrelated to Google Play distribution. When an authorized Firebase configuration is present, analytics and crash reporting are enabled. When it is absent, those integrations are disabled and the build must not use a fabricated placeholder file.

When the four signing entries are absent, `assembleRelease` produces unsigned APKs for build verification. Those artifacts are not installable production releases and must not be published.

## Verify and build

```bash
./gradlew test
./gradlew lint
./gradlew assembleRelease
```

Before distributing an APK, verify all of the following:

- the package name is `me.rerere.rikkahub`;
- the version name and code match the planned release;
- only the `arm64-v8a` APK is staged for the formal release;
- native libraries are compatible with 16 KB page-size devices;
- the APK signature matches the certificate used for previous fork releases, or is recorded as the initial fork certificate;
- the APK installs as an upgrade over the previous fork release without removing app data.

The nightly workflow enforces the same signature check before it uploads its single arm64 artifact. An unsigned build therefore remains a local verification artifact instead of being published accidentally.

Record the staged APK's SHA-256 digest. Rename and copy the arm64 artifact to the ignored staging directory:

```bash
mkdir -p app/release
cp app/build/outputs/apk/release/app-arm64-v8a-release.apk app/release/RikkaHub-<version>-arm64-v8a.apk
shasum -a 256 app/release/RikkaHub-<version>-arm64-v8a.apk
```

## Publish after approval

Publishing is a separate, explicitly authorized action. After the user approves the notes, create the release with the version as the title and tag (no `v` prefix), attach only the staged arm64 APK, and use `docs/releases/<version>.md` as the description. Do not upload the universal or x86_64 APK.

# Automatic APK mirror to Cloudflare R2

After a **stable GitHub Release is published**, the **Publish APK to R2** workflow copies its original ARM64 APK and checksum to:

- Bucket: `miffan-releases`
- Download origin: https://downloads.ayuilos.me
- Website manifest: https://downloads.ayuilos.me/latest.json

The website reads the manifest on each visit, so an APK release does not require a website redeploy. This workflow does not rebuild or re-sign the APK, publish draft releases, change the Android in-app updater, or modify DNS/mail settings.

## One-time activation

1. Merge the workflow and scripts into `master` before creating the next release tag.
2. In Cloudflare R2, create a dedicated token named **Miffan GitHub release mirror**:
   - Permission: **Object Read & Write**, not Admin Read & Write.
   - Scope: **only the `miffan-releases` bucket**.
   - Keep the account endpoint used in the script; do not grant DNS or other account permissions.
3. In [Miffan Actions secrets](https://github.com/Ayuilos/Miffan/settings/secrets/actions), store:
   - `MIFFAN_R2_ACCESS_KEY_ID`: the R2 **Access Key ID**.
   - `MIFFAN_R2_SECRET_ACCESS_KEY`: the R2 **Secret Access Key**.
   - These are the S3 credentials, **not** the API token value, a Global API Key, or the local Wrangler OAuth login.
4. Run **Publish APK to R2** manually on `master`, using the current stable tag, to verify the integration once. Check that both jobs succeed and the workflow summary links to the correct download.

Never paste keys into chat, source files, workflow YAML, release notes, or command arguments. The GitHub CLI can prompt for them without adding them to shell history:

```sh
gh secret set MIFFAN_R2_ACCESS_KEY_ID --repo Ayuilos/Miffan
gh secret set MIFFAN_R2_SECRET_ACCESS_KEY --repo Ayuilos/Miffan
```

The local Wrangler OAuth session cannot provide unattended GitHub Actions authentication. Bucket-scoped R2 credentials work with the S3 API; the Cloudflare management REST API requires broader permissions, so this workflow uses the runner's AWS CLI instead.

## Normal release flow

1. Use the existing **Prepare Production Release** workflow and its production approval/signing checks.
2. Review and publish the draft as a **stable** release; mark the intended version as GitHub's latest.
3. The mirror runs automatically. Draft, RC, nightly and other prerelease builds never replace the website's stable download.

The current production workflow only creates a draft; its protected production Environment and signing keys are unchanged. If a future workflow publishes releases with `GITHUB_TOKEN`, that publish event will not trigger another workflow. It must explicitly dispatch `publish-r2.yml` after publication instead. A normal dashboard publish or the user's authenticated GitHub CLI publish triggers this workflow.

## Safety and retry behavior

- Only the fixed repository, account, bucket, domain and ARM64 filename format are accepted.
- The original APK is checked against GitHub's asset digest and its published checksum.
- Versioned objects are create-only. An existing object must have identical bytes.
- The public APK is downloaded and checked before the latest pointer can change.
- The manifest comes from the authoritative S3 endpoint and updates use ETag conditional writes. Concurrent changes fail safely.
- Semantic-version and publication-date checks prevent older releases replacing newer ones.
- Only GitHub's current latest stable release may become the website latest.
- Reruns are idempotent; failed uploads can be retried without changing the APK.
- The credentialed job checks out trusted `master`, never arbitrary tag code. PR tests have no R2 credentials. Official checkout/setup actions are pinned to commit SHAs.
- No cache purge is needed: APK URLs are versioned and immutable; the latest manifest uses `no-cache`.

If a run fails, the previous latest remains usable unless only the final public-manifest verification failed after a successful pointer write. Inspect the error and rerun the workflow on `master` with the same stable tag. Do not overwrite an APK or edit a release digest to force a retry. If a token is revoked, rotate the two GitHub secrets and rerun.

Manual retry from the CLI:

```sh
gh workflow run publish-r2.yml --repo Ayuilos/Miffan --ref master -f release_tag=3.0.5
```

## Tests

Requires Node.js 24. Unit tests need no credentials, network, APK build or Android SDK:

```sh
node --test .github/scripts/publish_r2.test.mjs
```

For a read-only check of the real GitHub release (requires logged-in `gh`, downloads APK/checksum but performs no R2 writes):

```sh
node .github/scripts/publish_r2.mjs --verify-source
```

## Official references

- [R2 authentication and bucket-level permissions](https://developers.cloudflare.com/r2/api/tokens/)
- [R2 S3 API conditional writes](https://developers.cloudflare.com/r2/api/s3/api/)
- [GitHub release events](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#release)
- [Triggering a workflow from another workflow](https://docs.github.com/en/actions/how-tos/write-workflows/choose-when-workflows-run/trigger-a-workflow)

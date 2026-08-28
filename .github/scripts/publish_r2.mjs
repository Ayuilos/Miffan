import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { createReadStream, mkdtempSync, readFileSync, statSync, writeFileSync, appendFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const REPO = 'Ayuilos/Miffan';
export const BUCKET = 'miffan-releases';
export const ORIGIN = 'https://downloads.ayuilos.me';
const ENDPOINT = 'https://c2df10005f1eb0cb71b0e7b4d089cdb3.r2.cloudflarestorage.com';
const IMMUTABLE = 'public, max-age=31536000, immutable';

export function validTag(tag) {
  return typeof tag === 'string' && tag.length <= 64 &&
    /^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(tag);
}

export function parseManifest(value) {
  if (!value || !validTag(value.version) || value.architecture !== 'arm64-v8a' ||
      typeof value.publishedAt !== 'string' || !Number.isFinite(Date.parse(value.publishedAt)) ||
      !Number.isSafeInteger(value.size) || value.size <= 0 || value.size > 512_000_000 ||
      typeof value.sha256 !== 'string' || !/^[a-f0-9]{64}$/.test(value.sha256)) {
    throw new Error('Invalid stable release manifest.');
  }
  const fileName = 'Miffan-' + value.version.replace(/^v/, '') + '-arm64-v8a.apk';
  const downloadUrl = ORIGIN + '/releases/' + value.version + '/' + fileName;
  const releaseUrl = 'https://github.com/' + REPO + '/releases/tag/' + value.version;
  if (value.fileName !== fileName || value.downloadUrl !== downloadUrl ||
      value.checksumUrl !== downloadUrl + '.sha256' || value.releaseUrl !== releaseUrl) {
    throw new Error('Release manifest has an unexpected URL or filename.');
  }
  return {
    version: value.version, publishedAt: value.publishedAt, architecture: 'arm64-v8a',
    fileName, size: value.size, sha256: value.sha256, downloadUrl,
    checksumUrl: downloadUrl + '.sha256', releaseUrl,
  };
}

export function releaseFromGitHub(data) {
  if (data?.draft !== false || data?.prerelease !== false || !validTag(data.tag_name)) {
    throw new Error('Only published stable SemVer releases may be mirrored.');
  }
  const fileName = 'Miffan-' + data.tag_name.replace(/^v/, '') + '-arm64-v8a.apk';
  const assets = Array.isArray(data.assets) ? data.assets : [];
  const apks = assets.filter(asset => asset.name === fileName);
  const checksums = assets.filter(asset => asset.name === fileName + '.sha256');
  if (apks.length !== 1 || checksums.length !== 1 || !/^sha256:[a-f0-9]{64}$/.test(apks[0].digest ?? '')) {
    throw new Error('Expected exactly one ARM64 APK with a GitHub digest and its checksum file.');
  }
  const downloadUrl = ORIGIN + '/releases/' + data.tag_name + '/' + fileName;
  return parseManifest({
    version: data.tag_name, publishedAt: data.published_at, architecture: 'arm64-v8a',
    fileName, size: apks[0].size, sha256: apks[0].digest.slice(7),
    downloadUrl, checksumUrl: downloadUrl + '.sha256',
    releaseUrl: 'https://github.com/' + REPO + '/releases/tag/' + data.tag_name,
  });
}

export async function fileHash(file) {
  const hash = createHash('sha256');
  for await (const chunk of createReadStream(file)) hash.update(chunk);
  return hash.digest('hex');
}

export async function verifyFile(file, expected) {
  if (statSync(file).size !== expected.size) throw new Error('File size mismatch: ' + file);
  if (await fileHash(file) !== expected.sha256) throw new Error('SHA-256 mismatch: ' + file);
}

export function verifyChecksum(text, release) {
  const fields = text.trim().split(/\s+/);
  if (fields.length !== 2 || fields[0] !== release.sha256 ||
      fields[1].replace(/^\*/, '') !== release.fileName) throw new Error('Published checksum file mismatch.');
}

export function compareVersions(left, right) {
  if (!validTag(left) || !validTag(right)) throw new Error('Invalid version comparison.');
  const a = left.replace(/^v/, '').split('.').map(BigInt);
  const b = right.replace(/^v/, '').split('.').map(BigInt);
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] > b[i] ? 1 : -1;
  return 0;
}

export function shouldPromote(current, next, githubLatestTag) {
  if (current) {
    if (compareVersions(current.version, next.version) === 0 && current.sha256 !== next.sha256) {
      throw new Error('Refusing to replace the same version with different bytes.');
    }
    if (compareVersions(current.version, next.version) > 0 ||
        Date.parse(current.publishedAt) > Date.parse(next.publishedAt)) return false;
  }
  return githubLatestTag === next.version;
}

function run(command, args) {
  try {
    return execFileSync(command, args, {
      encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 300000,
      maxBuffer: 4 * 1024 * 1024,
      env: {
        ...process.env, AWS_EC2_METADATA_DISABLED: 'true', AWS_PAGER: '',
        AWS_REQUEST_CHECKSUM_CALCULATION: 'WHEN_REQUIRED',
        AWS_RESPONSE_CHECKSUM_VALIDATION: 'WHEN_REQUIRED',
      },
    });
  } catch (error) {
    // No command arguments or environment variables are included in errors.
    const stderr = String(error.stderr ?? '').trim();
    throw new Error(command + ' failed: ' + (stderr || 'timeout or nonzero exit status'));
  }
}

function githubRelease(tag) {
  if (tag && !validTag(tag)) throw new Error('Unsupported release tag.');
  return releaseFromGitHub(JSON.parse(run('gh', [
    'api', 'repos/' + REPO + '/releases/' + (tag ? 'tags/' + tag : 'latest'),
  ])));
}

function s3(operation, args) {
  return JSON.parse(run('aws', ['s3api', operation, '--endpoint-url', ENDPOINT,
    '--region', 'auto', '--no-cli-pager', '--output', 'json', '--bucket', BUCKET, ...args]) || '{}');
}

function getObject(key, file) {
  try {
    return s3('get-object', ['--key', key, file]);
  } catch (error) {
    if (/\(NoSuchKey\).*GetObject/.test(error.message)) return null;
    throw error; // Never interpret auth failures or network errors as missing objects.
  }
}

function putObject(key, file, metadata, condition) {
  const args = ['--key', key, '--body', file, '--content-type', metadata.contentType,
    '--cache-control', metadata.cacheControl, '--content-md5',
    createHash('md5').update(readFileSync(file)).digest('base64')];
  if (metadata.fileName) args.push('--content-disposition', 'attachment; filename="' + metadata.fileName + '"');
  args.push(condition.etag ? '--if-match' : '--if-none-match', condition.etag ?? '*');
  return s3('put-object', args);
}

export async function ensureImmutable(key, file, metadata, io) {
  const expected = { size: statSync(file).size, sha256: await fileHash(file) };
  const existing = await io.read(key);
  if (existing) {
    await verifyFile(existing.file, expected);
    return; // No overwrites of any versioned object, including checksum and metadata.
  }
  await io.write(key, file, metadata, {});
}

function publicDownload(url, file) {
  run('curl', ['--fail', '--silent', '--show-error', '--location', '--proto', '=https',
    '--proto-redir', '=https', '--retry', '3', '--retry-all-errors', '--retry-delay', '2',
    '--connect-timeout', '10', '--max-time', '240', '--output', file, url]);
}

// Dependency injection keeps failure and race tests independent of live credentials.
export async function publishRelease(release, files, io) {
  const prefix = 'releases/' + release.version + '/';
  await verifyFile(files.apk, release);
  verifyChecksum(readFileSync(files.checksum, 'utf8'), release);
  await io.immutable(prefix + release.fileName, files.apk, {
    contentType: 'application/vnd.android.package-archive', cacheControl: IMMUTABLE, fileName: release.fileName,
  });
  await io.immutable(prefix + release.fileName + '.sha256', files.checksum, {
    contentType: 'text/plain; charset=utf-8', cacheControl: IMMUTABLE, fileName: release.fileName + '.sha256',
  });
  await io.verifyPublicApk(release);
  await io.immutable(prefix + 'release.json', files.manifest, {
    contentType: 'application/json; charset=utf-8', cacheControl: IMMUTABLE,
  });

  // Read the authoritative S3 object, not a possibly stale CDN cache.
  const current = await io.readLatest();
  const previous = current ? parseManifest(current.value) : null;
  const latest = await io.githubLatest();
  if (!shouldPromote(previous, release, latest.version)) {
    return { promoted: false, message: 'Version mirrored; preserving the newer/current latest release.' };
  }
  if (JSON.stringify(previous) !== JSON.stringify(release)) {
    // R2 rejects the write if another publisher changed the pointer after readLatest().
    await io.writeLatest(files.manifest, current?.etag);
  }
  await io.verifyPublicManifest(release);
  return { promoted: true, message: 'Public APK and latest.json verified.' };
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length > 1 || (args.length === 1 && args[0] !== '--verify-source')) {
    throw new Error('Usage: node .github/scripts/publish_r2.mjs [--verify-source]');
  }
  const verifyOnly = args[0] === '--verify-source';
  if (!verifyOnly && (!process.env.AWS_ACCESS_KEY_ID || !process.env.AWS_SECRET_ACCESS_KEY)) {
    throw new Error('Configure MIFFAN_R2_ACCESS_KEY_ID and MIFFAN_R2_SECRET_ACCESS_KEY in GitHub Actions secrets.');
  }
  const release = githubRelease(process.env.RELEASE_TAG?.trim());
  const work = mkdtempSync(join(tmpdir(), 'miffan-r2-publish-'));
  console.log('Verifying release ' + release.version + '; working directory: ' + work);
  for (const name of [release.fileName, release.fileName + '.sha256']) {
    run('gh', ['release', 'download', release.version, '--repo', REPO, '--pattern', name, '--dir', work]);
  }
  const files = { apk: join(work, release.fileName), checksum: join(work, release.fileName + '.sha256'),
    manifest: join(work, 'release.json') };
  await verifyFile(files.apk, release);
  verifyChecksum(readFileSync(files.checksum, 'utf8'), release);
  writeFileSync(files.manifest, JSON.stringify(release, null, 2) + '\n');
  if (verifyOnly) {
    console.log('GitHub APK and checksum verified; no R2 writes performed.');
    return;
  }

  let sequence = 0;
  const io = {
    async read(key) {
      const file = join(work, 'remote-' + (++sequence));
      const metadata = getObject(key, file);
      return metadata ? { file, etag: metadata.ETag } : null;
    },
    async write(key, file, metadata, condition) { putObject(key, file, metadata, condition); },
    async immutable(key, file, metadata) { await ensureImmutable(key, file, metadata, io); },
    async verifyPublicApk(value) {
      const file = join(work, 'public.apk');
      publicDownload(value.downloadUrl + '?verify=' + Date.now(), file);
      await verifyFile(file, value);
    },
    async readLatest() {
      const current = await io.read('latest.json');
      if (!current) return null;
      if (!current.etag) throw new Error('R2 did not return an ETag for latest.json.');
      return { value: JSON.parse(readFileSync(current.file, 'utf8')), etag: current.etag };
    },
    async githubLatest() { return githubRelease(); },
    async writeLatest(file, etag) {
      putObject('latest.json', file, {
        contentType: 'application/json; charset=utf-8', cacheControl: 'no-cache, max-age=0, must-revalidate',
      }, { etag });
    },
    async verifyPublicManifest(value) {
      const file = join(work, 'public-latest.json');
      publicDownload(ORIGIN + '/latest.json?verify=' + Date.now(), file);
      if (JSON.stringify(parseManifest(JSON.parse(readFileSync(file, 'utf8')))) !== JSON.stringify(value)) {
        throw new Error('Public latest.json does not match the published version.');
      }
    },
  };
  const result = await publishRelease(release, files, io);
  console.log(result.message + '\n' + release.downloadUrl);
  if (process.env.GITHUB_STEP_SUMMARY) {
    appendFileSync(process.env.GITHUB_STEP_SUMMARY,
      '## R2 release mirror\n\n' + result.message + '\n\n- Version: ' + release.version +
      '\n- APK: [' + release.fileName + '](' + release.downloadUrl + ')\n- SHA-256: ' + release.sha256 +
      '\n- Website: https://miffan.ayuilos.me\n');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}

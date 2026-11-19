import { execFileSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { appendFileSync, createReadStream, mkdtempSync, readFileSync, statSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

export const REPO = 'Ayuilos/Miffan';
export const BUCKET = 'miffan-releases';
export const ORIGIN = 'https://downloads.ayuilos.me';
const ENDPOINT = 'https://c2df10005f1eb0cb71b0e7b4d089cdb3.r2.cloudflarestorage.com';
const IMMUTABLE = 'public, max-age=31536000, immutable';
const MAX_BINARY_SIZE = 67_108_864;
const MAX_CHECKSUM_SIZE = 65_536;

export const CONNECTOR_BINARIES = [
  'miffan-connector-darwin-arm64',
  'miffan-connector-darwin-amd64',
  'miffan-connector-linux-amd64',
  'miffan-connector-linux-arm64',
  'miffan-connector-windows-amd64.exe',
];
export const CHECKSUM_FILE = 'SHA256SUMS';

export function validConnectorTag(tag) {
  return typeof tag === 'string' && tag.length <= 64 &&
    /^relay-connector-v(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$/.test(tag);
}

export function versionFromTag(tag) {
  if (!validConnectorTag(tag)) throw new Error('Unsupported connector release tag.');
  return tag.slice('relay-connector-v'.length);
}

export function compareConnectorVersions(left, right) {
  const a = versionFromTag(left).split('.').map(BigInt);
  const b = versionFromTag(right).split('.').map(BigInt);
  for (let i = 0; i < 3; i++) if (a[i] !== b[i]) return a[i] > b[i] ? 1 : -1;
  return 0;
}

export function pickLatestConnectorRelease(releases) {
  const candidates = (Array.isArray(releases) ? releases : [])
    .filter(release => release?.draft === false && validConnectorTag(release.tag_name));
  if (candidates.length === 0) {
    throw new Error('No published connector release found.');
  }
  return candidates.reduce((best, item) => {
    const cmp = compareConnectorVersions(item.tag_name, best.tag_name);
    if (cmp !== 0) return cmp > 0 ? item : best;
    const itemTime = Date.parse(item.published_at);
    const bestTime = Date.parse(best.published_at);
    if (Number.isFinite(itemTime) && (!Number.isFinite(bestTime) || itemTime > bestTime)) {
      return item;
    }
    return best;
  });
}

export function connectorReleaseFromGitHub(data) {
  if (data?.draft !== false || !validConnectorTag(data.tag_name) ||
      typeof data.published_at !== 'string' || !Number.isFinite(Date.parse(data.published_at))) {
    throw new Error('Only published connector SemVer releases may be mirrored.');
  }
  const version = versionFromTag(data.tag_name);
  const prefix = 'connector/v' + version + '/';
  const baseUrl = ORIGIN + '/connector/v' + version;
  const releaseUrl = 'https://github.com/' + REPO + '/releases/tag/' + data.tag_name;
  const assets = Array.isArray(data.assets) ? data.assets : [];

  const binaries = CONNECTOR_BINARIES.map(name => {
    const matched = assets.filter(asset => asset?.name === name);
    if (matched.length !== 1) {
      throw new Error('Expected exactly one asset for ' + name);
    }
    const asset = matched[0];
    if (!Number.isSafeInteger(asset.size) || asset.size <= 0 || asset.size > MAX_BINARY_SIZE ||
        !/^sha256:[a-f0-9]{64}$/.test(asset.digest ?? '')) {
      throw new Error('Invalid size or GitHub digest for ' + name);
    }
    return {
      name,
      size: asset.size,
      sha256: asset.digest.slice(7),
      key: prefix + name,
      downloadUrl: baseUrl + '/' + name,
    };
  });

  const checksumMatched = assets.filter(asset => asset?.name === CHECKSUM_FILE);
  if (checksumMatched.length !== 1) {
    throw new Error('Expected exactly one asset for ' + CHECKSUM_FILE);
  }
  const checksumAsset = checksumMatched[0];
  if (!Number.isSafeInteger(checksumAsset.size) || checksumAsset.size <= 0 || checksumAsset.size > MAX_CHECKSUM_SIZE) {
    throw new Error('Invalid size for ' + CHECKSUM_FILE);
  }
  if (checksumAsset.digest !== undefined && checksumAsset.digest !== null &&
      !/^sha256:[a-f0-9]{64}$/.test(checksumAsset.digest)) {
    throw new Error('Invalid GitHub digest for ' + CHECKSUM_FILE);
  }

  return {
    tag: data.tag_name,
    version,
    publishedAt: data.published_at,
    prefix,
    baseUrl,
    releaseUrl,
    binaries,
    checksumFile: {
      name: CHECKSUM_FILE,
      size: checksumAsset.size,
      ...(checksumAsset.digest != null ? { sha256: checksumAsset.digest.slice(7) } : {}),
      key: prefix + CHECKSUM_FILE,
      downloadUrl: baseUrl + '/' + CHECKSUM_FILE,
    },
  };
}

export function verifyChecksumsFile(text, release) {
  if (typeof text !== 'string') throw new Error('Invalid SHA256SUMS content.');
  const expectedByName = new Map(release.binaries.map(binary => [binary.name, binary.sha256]));
  const seen = new Set();
  const lines = text.split(/\r?\n/).map(line => line.trim()).filter(Boolean);
  if (lines.length === 0) throw new Error('SHA256SUMS is empty.');
  for (const line of lines) {
    const fields = line.split(/\s+/);
    if (fields.length !== 2) throw new Error('Malformed SHA256SUMS line.');
    const [sha256, rawName] = fields;
    if (!/^[a-f0-9]{64}$/.test(sha256)) throw new Error('Invalid SHA-256 digest in SHA256SUMS.');
    const fileName = rawName.replace(/^\*/, '');
    if (!expectedByName.has(fileName)) throw new Error('Unexpected file in SHA256SUMS: ' + fileName);
    if (seen.has(fileName)) throw new Error('Duplicate file in SHA256SUMS: ' + fileName);
    if (expectedByName.get(fileName) !== sha256) throw new Error('SHA256SUMS mismatch for ' + fileName);
    seen.add(fileName);
  }
  if (seen.size !== expectedByName.size) {
    throw new Error('SHA256SUMS is missing one or more connector binaries.');
  }
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

function githubConnectorRelease(tag) {
  if (tag) {
    if (!validConnectorTag(tag)) throw new Error('Unsupported connector release tag.');
    return connectorReleaseFromGitHub(JSON.parse(run('gh', [
      'api', 'repos/' + REPO + '/releases/tags/' + tag,
    ])));
  }
  const releases = JSON.parse(run('gh', [
    'api', 'repos/' + REPO + '/releases?per_page=100',
  ]));
  return connectorReleaseFromGitHub(pickLatestConnectorRelease(releases));
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

export function isPreconditionFailed(error) {
  return /\bPreconditionFailed\b/.test(error?.message ?? '');
}

export async function ensureImmutable(key, file, metadata, io) {
  const expected = { size: statSync(file).size, sha256: await fileHash(file) };
  const existing = await io.read(key);
  if (existing) {
    await verifyFile(existing.file, expected);
    return;
  }
  try {
    await io.write(key, file, metadata, {});
  } catch (error) {
    if (!isPreconditionFailed(error)) throw error;
    const landed = await io.read(key);
    if (!landed) throw error;
    await verifyFile(landed.file, expected);
  }
}

function publicDownload(url, file) {
  run('curl', ['--fail', '--silent', '--show-error', '--location', '--proto', '=https',
    '--proto-redir', '=https', '--retry', '3', '--retry-all-errors', '--retry-delay', '2',
    '--connect-timeout', '10', '--max-time', '240', '--output', file, url]);
}

// Dependency injection keeps failure and race tests independent of live credentials.
export async function publishConnectorRelease(release, files, io) {
  for (const binary of release.binaries) {
    await verifyFile(files[binary.name], binary);
  }
  const checksumPath = files[CHECKSUM_FILE];
  const checksumExpected = {
    size: release.checksumFile.size,
    sha256: release.checksumFile.sha256 ?? await fileHash(checksumPath),
  };
  await verifyFile(checksumPath, checksumExpected);
  verifyChecksumsFile(readFileSync(checksumPath, 'utf8'), release);

  for (const binary of release.binaries) {
    await io.immutable(binary.key, files[binary.name], {
      contentType: 'application/octet-stream',
      cacheControl: IMMUTABLE,
      fileName: binary.name,
    });
  }
  await io.immutable(release.checksumFile.key, checksumPath, {
    contentType: 'text/plain; charset=utf-8',
    cacheControl: IMMUTABLE,
    fileName: CHECKSUM_FILE,
  });

  for (const binary of release.binaries) {
    await io.verifyPublicFile(binary, binary);
  }
  await io.verifyPublicFile(release.checksumFile, checksumExpected);

  return {
    version: release.version,
    baseUrl: release.baseUrl,
    message: 'Connector binaries and SHA256SUMS verified.',
  };
}

async function main() {
  const args = process.argv.slice(2);
  if (args.length > 1 || (args.length === 1 && args[0] !== '--verify-source')) {
    throw new Error('Usage: node .github/scripts/publish_connector_r2.mjs [--verify-source]');
  }
  const verifyOnly = args[0] === '--verify-source';
  if (!verifyOnly && (!process.env.AWS_ACCESS_KEY_ID || !process.env.AWS_SECRET_ACCESS_KEY)) {
    throw new Error('Configure MIFFAN_R2_ACCESS_KEY_ID and MIFFAN_R2_SECRET_ACCESS_KEY in GitHub Actions secrets.');
  }

  const release = githubConnectorRelease(process.env.RELEASE_TAG?.trim());
  const work = mkdtempSync(join(tmpdir(), 'miffan-connector-r2-publish-'));
  console.log('Verifying connector release ' + release.tag + '; working directory: ' + work);

  const files = {};
  for (const name of [...CONNECTOR_BINARIES, CHECKSUM_FILE]) {
    run('gh', ['release', 'download', release.tag, '--repo', REPO, '--pattern', name, '--dir', work]);
    files[name] = join(work, name);
  }

  if (verifyOnly) {
    for (const binary of release.binaries) {
      await verifyFile(files[binary.name], binary);
    }
    const checksumExpected = {
      size: release.checksumFile.size,
      sha256: release.checksumFile.sha256 ?? await fileHash(files[CHECKSUM_FILE]),
    };
    await verifyFile(files[CHECKSUM_FILE], checksumExpected);
    verifyChecksumsFile(readFileSync(files[CHECKSUM_FILE], 'utf8'), release);
    console.log('GitHub connector binaries and SHA256SUMS verified; no R2 writes performed.');
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
    async verifyPublicFile(asset, expected) {
      const file = join(work, 'public-' + (++sequence) + '-' + asset.name);
      publicDownload(asset.downloadUrl + '?verify=' + Date.now(), file);
      await verifyFile(file, expected);
    },
  };

  const result = await publishConnectorRelease(release, files, io);
  console.log(result.message + '\n' + result.baseUrl);
  if (process.env.GITHUB_STEP_SUMMARY) {
    const rows = [
      ...release.binaries.map(b => '| [' + b.name + '](' + b.downloadUrl + ') | `' + b.sha256 + '` |'),
      '| [' + release.checksumFile.name + '](' + release.checksumFile.downloadUrl + ') | `' +
        (release.checksumFile.sha256 ?? 'verified') + '` |',
    ].join('\n');
    appendFileSync(process.env.GITHUB_STEP_SUMMARY,
      '## R2 connector release mirror\n\n' + result.message +
      '\n\n- Version: `' + release.version + '`' +
      '\n- Base URL: `' + release.baseUrl + '`' +
      '\n- Release: ' + release.releaseUrl +
      '\n\n| File | SHA-256 |\n| --- | --- |\n' + rows + '\n');
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch(error => { console.error(error.message); process.exitCode = 1; });
}

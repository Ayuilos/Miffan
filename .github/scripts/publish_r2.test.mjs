import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  ORIGIN, validTag, parseManifest, releaseFromGitHub, compareVersions,
  shouldPromote, verifyFile, verifyChecksum, ensureImmutable, publishRelease,
} from './publish_r2.mjs';

const bytes = Buffer.from('signed APK fixture');
const digest = createHash('sha256').update(bytes).digest('hex');
function github(version = '3.0.5') {
  const name = 'Miffan-' + version.replace(/^v/, '') + '-arm64-v8a.apk';
  return {
    tag_name: version, published_at: '2026-08-28T08:21:48Z', draft: false, prerelease: false,
    assets: [{ name, size: bytes.length, digest: 'sha256:' + digest }, { name: name + '.sha256' }],
  };
}
const release = releaseFromGitHub(github());

function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'r2-publisher-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const files = { apk: join(dir, 'app.apk'), checksum: join(dir, 'app.sha256'), manifest: join(dir, 'release.json') };
  writeFileSync(files.apk, bytes);
  writeFileSync(files.checksum, digest + '  ' + release.fileName + '\n');
  writeFileSync(files.manifest, JSON.stringify(release, null, 2) + '\n');
  const events = [];
  const old = releaseFromGitHub({ ...github('3.0.4'), published_at: '2026-08-27T00:00:00Z' });
  const io = {
    async immutable(key) { events.push(['immutable', key]); },
    async verifyPublicApk() { events.push(['verify-apk']); },
    async readLatest() { return { value: old, etag: '"original-etag"' }; },
    async githubLatest() { return release; },
    async writeLatest(file, etag) {
      events.push(['latest', etag]);
      assert.deepEqual(JSON.parse(readFileSync(file, 'utf8')), release);
    },
    async verifyPublicManifest() { events.push(['verify-manifest']); },
  };
  return { dir, files, events, io };
}

test('accepts only stable, path-safe semantic versions', () => {
  for (const value of ['3.0.5', 'v3.0.5', '0.1.0', '100.200.300']) assert.equal(validTag(value), true);
  for (const value of ['', undefined, '3.0.5-rc.1', 'nightly', '3.0.5+build', '03.0.5',
    '../3.0.5', '3.0.5\n', '3.0.5;touch /tmp/x', '9'.repeat(100) + '.0.0']) assert.equal(validTag(value), false);
});

test('maps a published ARM64 APK into the website manifest contract', () => {
  assert.equal(release.downloadUrl, ORIGIN + '/releases/3.0.5/Miffan-3.0.5-arm64-v8a.apk');
  assert.equal(release.checksumUrl, release.downloadUrl + '.sha256');
  assert.deepEqual(parseManifest(release), release);
  assert.equal(releaseFromGitHub(github('v3.0.5')).fileName, release.fileName);
});

test('rejects drafts, prereleases, missing/ambiguous files, invalid hashes and sizes', () => {
  for (const change of [
    { draft: true }, { prerelease: true }, { tag_name: '3.0.5-rc.1' },
    { assets: [] }, { assets: [github().assets[0]] },
    { assets: [...github().assets, github().assets[0]] },
    { assets: [...github().assets, github().assets[1]] },
    { assets: [{ ...github().assets[0], digest: undefined }, github().assets[1]] },
    { assets: [{ ...github().assets[0], size: 512_000_001 }, github().assets[1]] },
    { assets: [{ ...github().assets[0], size: -1 }, github().assets[1]] },
  ]) assert.throws(() => releaseFromGitHub({ ...github(), ...change }));
});

test('rejects untrusted URLs and invalid manifest fields', () => {
  for (const change of [
    { downloadUrl: 'https://evil.example/app.apk' }, { checksumUrl: 'https://evil.example/hash' },
    { releaseUrl: 'https://evil.example' }, { fileName: '../app.apk' },
    { architecture: 'x86_64' }, { publishedAt: 'bad' }, { sha256: '0' }, { size: 0 }, { size: 1.5 },
  ]) assert.throws(() => parseManifest({ ...release, ...change }));
  for (const value of [null, undefined, {}, []]) assert.throws(() => parseManifest(value));
});

test('version ordering is semantic, not lexical or publication-date-only', () => {
  assert.equal(compareVersions('3.0.10', '3.0.9'), 1);
  assert.equal(compareVersions('v3.0.5', '3.0.5'), 0);
  assert.equal(compareVersions('2.99.99', '3.0.0'), -1);
  assert.equal(compareVersions('999999999999999999.0.0', '999999999999999998.0.0'), 1);
  assert.equal(shouldPromote(null, release, release.version), true);
  assert.equal(shouldPromote(null, release, '3.0.6'), false);
  assert.equal(shouldPromote(releaseFromGitHub(github('3.0.6')), release, release.version), false);
  assert.equal(shouldPromote({ ...release, publishedAt: '2026-08-29T00:00:00Z' }, release, release.version), false);
  assert.throws(() => shouldPromote({ ...release, sha256: '0'.repeat(64) }, release, release.version), /different bytes/);
});

test('verifies exact APK size, SHA-256, and checksum filename', async t => {
  const { files } = fixture(t);
  await verifyFile(files.apk, release);
  verifyChecksum(readFileSync(files.checksum, 'utf8'), release);
  verifyChecksum(digest + ' *' + release.fileName + '\r\n', release);
  await assert.rejects(verifyFile(files.apk, { ...release, size: release.size + 1 }), /size mismatch/);
  writeFileSync(files.apk, Buffer.alloc(bytes.length));
  await assert.rejects(verifyFile(files.apk, release), /SHA-256 mismatch/);
  assert.throws(() => verifyChecksum(digest + ' other.apk', release), /checksum/);
  assert.throws(() => verifyChecksum('0'.repeat(64) + ' ' + release.fileName, release), /checksum/);
});

test('immutable objects are retained if identical and rejected if changed', async t => {
  const { dir, files } = fixture(t);
  const remote = join(dir, 'remote.apk');
  writeFileSync(remote, bytes);
  let writes = 0;
  const io = {
    async read() { return { file: remote }; },
    async write() { writes++; },
  };
  await ensureImmutable('release.apk', files.apk, {}, io);
  assert.equal(writes, 0);
  writeFileSync(remote, Buffer.alloc(bytes.length));
  await assert.rejects(ensureImmutable('release.apk', files.apk, {}, io), /SHA-256 mismatch/);
  assert.equal(writes, 0);
  io.read = async () => null;
  io.write = async (_key, _file, _metadata, condition) => { assert.deepEqual(condition, {}); writes++; };
  await ensureImmutable('release.apk', files.apk, {}, io);
  assert.equal(writes, 1);
});

test('publishes latest only after public APK verification and passes the original ETag', async t => {
  const { files, events, io } = fixture(t);
  assert.equal((await publishRelease(release, files, io)).promoted, true);
  assert.deepEqual(events.map(event => event[0]),
    ['immutable', 'immutable', 'verify-apk', 'immutable', 'latest', 'verify-manifest']);
  assert.deepEqual(events[4], ['latest', '"original-etag"']);
});

test('failed public APK verification never changes the latest pointer', async t => {
  const { files, events, io } = fixture(t);
  io.verifyPublicApk = async () => { throw new Error('Public download failed'); };
  await assert.rejects(publishRelease(release, files, io), /Public download failed/);
  assert.equal(events.some(event => event[0] === 'latest'), false);
});

test('bad source APK fails before any R2 upload', async t => {
  const { files, events, io } = fixture(t);
  writeFileSync(files.apk, 'tampered');
  await assert.rejects(publishRelease(release, files, io), /size mismatch/);
  assert.deepEqual(events, []);
});

test('latest auth/read errors and malformed manifests fail closed', async t => {
  const { files, events, io } = fixture(t);
  io.readLatest = async () => { throw new Error('AccessDenied'); };
  await assert.rejects(publishRelease(release, files, io), /AccessDenied/);
  io.readLatest = async () => ({ value: {}, etag: '"etag"' });
  await assert.rejects(publishRelease(release, files, io), /Invalid stable release manifest/);
  assert.equal(events.some(event => event[0] === 'latest'), false);
});

test('a changed GitHub latest or a newer R2 version is never downgraded', async t => {
  const { files, events, io } = fixture(t);
  io.githubLatest = async () => releaseFromGitHub(github('3.0.6'));
  assert.equal((await publishRelease(release, files, io)).promoted, false);
  io.githubLatest = async () => release;
  io.readLatest = async () => ({ value: releaseFromGitHub(github('3.0.6')), etag: '"newer"' });
  assert.equal((await publishRelease(release, files, io)).promoted, false);
  assert.equal(events.some(event => event[0] === 'latest'), false);
});

test('idempotent reruns verify downloads without rewriting latest', async t => {
  const { files, events, io } = fixture(t);
  io.readLatest = async () => ({ value: release, etag: '"same"' });
  assert.equal((await publishRelease(release, files, io)).promoted, true);
  assert.equal(events.some(event => event[0] === 'latest'), false);
  assert.equal(events.at(-1)[0], 'verify-manifest');
});

test('conditional-write races fail without retrying an unconditional overwrite', async t => {
  const { files, events, io } = fixture(t);
  let attempts = 0;
  io.writeLatest = async (_file, etag) => {
    assert.equal(etag, '"original-etag"');
    attempts++;
    throw new Error('PreconditionFailed');
  };
  await assert.rejects(publishRelease(release, files, io), /PreconditionFailed/);
  assert.equal(attempts, 1);
  assert.equal(events.some(event => event[0] === 'verify-manifest'), false);
});

test('first publication uses create-only latest semantics', async t => {
  const { files, events, io } = fixture(t);
  io.readLatest = async () => null;
  await publishRelease(release, files, io);
  assert.deepEqual(events.find(event => event[0] === 'latest'), ['latest', undefined]);
});

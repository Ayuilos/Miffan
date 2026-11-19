import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import {
  CHECKSUM_FILE,
  CONNECTOR_BINARIES,
  ORIGIN,
  compareConnectorVersions,
  connectorReleaseFromGitHub,
  ensureImmutable,
  isPreconditionFailed,
  pickLatestConnectorRelease,
  publishConnectorRelease,
  validConnectorTag,
  verifyChecksumsFile,
  verifyFile,
  versionFromTag,
} from './publish_connector_r2.mjs';

function makeFixtures() {
  const binaries = {};
  const assets = [];
  const checksumLines = [];
  for (const name of CONNECTOR_BINARIES) {
    const content = Buffer.from('binary payload for ' + name);
    const sha256 = createHash('sha256').update(content).digest('hex');
    binaries[name] = { name, content, sha256 };
    assets.push({ name, size: content.length, digest: 'sha256:' + sha256 });
    checksumLines.push(sha256 + '  ' + name);
  }
  const checksumText = checksumLines.join('\n') + '\n';
  const checksumContent = Buffer.from(checksumText, 'utf8');
  const checksumSha256 = createHash('sha256').update(checksumContent).digest('hex');
  assets.push({ name: CHECKSUM_FILE, size: checksumContent.length, digest: 'sha256:' + checksumSha256 });
  return { binaries, checksumText, checksumContent, checksumSha256, assets };
}

const sample = makeFixtures();

function githubConnector(tag = 'relay-connector-v1.3.0', overrides = {}) {
  return {
    tag_name: tag,
    published_at: '2026-11-10T08:00:00Z',
    draft: false,
    prerelease: true,
    assets: sample.assets.map(a => ({ ...a })),
    ...overrides,
  };
}

const release = connectorReleaseFromGitHub(githubConnector());

function fixture(t) {
  const dir = mkdtempSync(join(tmpdir(), 'connector-r2-test-'));
  t.after(() => rmSync(dir, { recursive: true, force: true }));
  const files = {};
  for (const name of CONNECTOR_BINARIES) {
    const path = join(dir, name);
    writeFileSync(path, sample.binaries[name].content);
    files[name] = path;
  }
  const checksumPath = join(dir, CHECKSUM_FILE);
  writeFileSync(checksumPath, sample.checksumContent);
  files[CHECKSUM_FILE] = checksumPath;

  const events = [];
  const io = {
    async immutable(key, _file, metadata) {
      events.push(['immutable', key, metadata]);
    },
    async verifyPublicFile(asset, expected) {
      events.push(['verify-public', asset.downloadUrl, expected.sha256]);
    },
  };
  return { dir, files, events, io };
}

test('validates connector tags and strips prefix into SemVer', () => {
  for (const tag of ['relay-connector-v1.3.0', 'relay-connector-v0.0.1', 'relay-connector-v10.20.30']) {
    assert.equal(validConnectorTag(tag), true);
    assert.equal(versionFromTag(tag), tag.slice('relay-connector-v'.length));
  }
  for (const bad of [
    '', undefined, null, '4.10.3', 'v4.10.3', 'relay-connector-v1.3.0-rc.1',
    'relay-connector-v01.3.0', 'relay-connector-v1.3', 'relay-connector-v../1.3.0',
    'relay-connector-v1.3.0;rm -rf /', 'relay-connector-v' + '9'.repeat(60) + '.0.0',
  ]) {
    assert.equal(validConnectorTag(bad), false);
    assert.throws(() => versionFromTag(bad));
  }
});

test('picks the highest SemVer published connector release and ignores APK releases and drafts', () => {
  const releases = [
    { tag_name: '4.10.3', draft: false, published_at: '2026-11-18T00:00:00Z' },
    { tag_name: 'relay-connector-v2.0.0', draft: true, published_at: '2026-11-19T00:00:00Z' },
    { tag_name: 'relay-connector-v1.9.0', draft: false, published_at: '2026-11-15T00:00:00Z' },
    { tag_name: 'relay-connector-v1.10.0', draft: false, published_at: '2026-11-12T00:00:00Z' },
    { tag_name: 'relay-connector-v1.3.0', draft: false, published_at: '2026-11-10T00:00:00Z' },
  ];
  assert.equal(compareConnectorVersions('relay-connector-v1.10.0', 'relay-connector-v1.9.0'), 1);
  assert.equal(pickLatestConnectorRelease(releases).tag_name, 'relay-connector-v1.10.0');

  const tie = [
    { tag_name: 'relay-connector-v1.3.0', draft: false, published_at: '2026-11-10T00:00:00Z', id: 1 },
    { tag_name: 'relay-connector-v1.3.0', draft: false, published_at: '2026-11-11T00:00:00Z', id: 2 },
  ];
  assert.equal(pickLatestConnectorRelease(tie).id, 2);
  assert.throws(() => pickLatestConnectorRelease([{ tag_name: '4.10.3', draft: false }]));
});

test('maps all 6 assets under the official R2 connector prefix and allows prerelease', () => {
  assert.equal(release.version, '1.3.0');
  assert.equal(release.prefix, 'connector/v1.3.0/');
  assert.equal(release.baseUrl, ORIGIN + '/connector/v1.3.0');
  assert.equal(release.binaries.length, 5);
  for (const binary of release.binaries) {
    assert.equal(binary.key, 'connector/v1.3.0/' + binary.name);
    assert.equal(binary.downloadUrl, ORIGIN + '/connector/v1.3.0/' + binary.name);
  }
  assert.equal(release.checksumFile.key, 'connector/v1.3.0/SHA256SUMS');
  assert.equal(release.checksumFile.downloadUrl, ORIGIN + '/connector/v1.3.0/SHA256SUMS');
});

test('rejects drafts, missing or duplicate assets, invalid digests, and oversize binaries', () => {
  for (const override of [
    { draft: true },
    { tag_name: '4.10.3' },
    { published_at: 'not-a-date' },
    { assets: sample.assets.slice(1) },
    { assets: [...sample.assets, sample.assets[0]] },
    { assets: sample.assets.filter(a => a.name !== CHECKSUM_FILE) },
    { assets: sample.assets.map((a, i) => i === 0 ? { ...a, digest: undefined } : a) },
    { assets: sample.assets.map((a, i) => i === 0 ? { ...a, digest: 'sha256:bad' } : a) },
    { assets: sample.assets.map((a, i) => i === 0 ? { ...a, size: 67_108_865 } : a) },
    { assets: sample.assets.map((a, i) => i === 0 ? { ...a, size: 0 } : a) },
    { assets: sample.assets.map(a => a.name === CHECKSUM_FILE ? { ...a, digest: 'sha256:bad' } : a) },
  ]) {
    assert.throws(() => connectorReleaseFromGitHub(githubConnector('relay-connector-v1.3.0', override)));
  }

  // SHA256SUMS digest may be omitted by GitHub API.
  const withoutChecksumDigest = connectorReleaseFromGitHub(githubConnector('relay-connector-v1.3.0', {
    assets: sample.assets.map(a => a.name === CHECKSUM_FILE ? { name: a.name, size: a.size } : a),
  }));
  assert.equal(withoutChecksumDigest.checksumFile.sha256, undefined);
});

test('validates SHA256SUMS format and rejects mismatches, missing, extra, or duplicate entries', () => {
  verifyChecksumsFile(sample.checksumText, release);
  const starBinary = sample.checksumText.replace(/  /g, ' *').replace(/\n/g, '\r\n');
  verifyChecksumsFile(starBinary, release);

  assert.throws(() => verifyChecksumsFile('', release), /empty/);
  assert.throws(() => verifyChecksumsFile('not-valid', release), /Malformed/);
  assert.throws(() => verifyChecksumsFile('zzzz  ' + CONNECTOR_BINARIES[0], release), /Invalid SHA-256/);
  assert.throws(() => verifyChecksumsFile(sample.checksumText.replace(release.binaries[0].sha256, '0'.repeat(64)), release), /mismatch/);
  assert.throws(() => verifyChecksumsFile(sample.checksumText.split('\n').slice(1).join('\n'), release), /missing/);
  assert.throws(() => verifyChecksumsFile(sample.checksumText + '0'.repeat(64) + '  extra.bin\n', release), /Unexpected/);
  const firstLine = sample.checksumText.split('\n')[0];
  assert.throws(() => verifyChecksumsFile(sample.checksumText + firstLine + '\n', release), /Duplicate/);
});

test('immutable objects are retained if identical, fail if modified, and tolerate idempotent PreconditionFailed', async t => {
  const { dir, files } = fixture(t);
  const targetName = CONNECTOR_BINARIES[0];
  const remote = join(dir, 'remote.bin');
  writeFileSync(remote, sample.binaries[targetName].content);
  let writes = 0;
  const io = {
    async read() { return { file: remote }; },
    async write() { writes++; },
  };
  await ensureImmutable('connector/v1.3.0/' + targetName, files[targetName], {}, io);
  assert.equal(writes, 0);

  writeFileSync(remote, Buffer.from('different bytes'));
  await assert.rejects(ensureImmutable('connector/v1.3.0/' + targetName, files[targetName], {}, io), /mismatch/);
  assert.equal(writes, 0);

  const refused = () => new Error('aws failed: An error occurred (PreconditionFailed) when calling PutObject');
  assert.equal(isPreconditionFailed(refused()), true);
  assert.equal(isPreconditionFailed(new Error('AccessDenied')), false);

  let reads = 0;
  const raceIo = {
    async read() {
      reads++;
      return reads === 1 ? null : { file: remote };
    },
    async write() {
      writeFileSync(remote, sample.binaries[targetName].content);
      throw refused();
    },
  };
  await ensureImmutable('connector/v1.3.0/' + targetName, files[targetName], {}, raceIo);
  assert.equal(reads, 2);

  reads = 0;
  raceIo.read = async () => null;
  raceIo.write = async () => { throw refused(); };
  await assert.rejects(ensureImmutable('connector/v1.3.0/' + targetName, files[targetName], {}, raceIo), /PreconditionFailed/);
});

test('verifies source binaries and SHA256SUMS before any R2 write, uploads 6 objects, and verifies public downloads', async t => {
  const { files, events, io } = fixture(t);
  const res = await publishConnectorRelease(release, files, io);
  assert.equal(res.version, '1.3.0');
  assert.equal(res.baseUrl, ORIGIN + '/connector/v1.3.0');
  assert.equal(events.filter(e => e[0] === 'immutable').length, 6);
  assert.equal(events.filter(e => e[0] === 'verify-public').length, 6);
  assert.deepEqual(events.slice(0, 6).map(e => e[1]), [
    ...CONNECTOR_BINARIES.map(name => 'connector/v1.3.0/' + name),
    'connector/v1.3.0/SHA256SUMS',
  ]);
  assert.deepEqual(events.slice(6).map(e => e[1]), [
    ...CONNECTOR_BINARIES.map(name => ORIGIN + '/connector/v1.3.0/' + name),
    ORIGIN + '/connector/v1.3.0/SHA256SUMS',
  ]);
});

test('works when SHA256SUMS asset lacks GitHub API digest and still verifies public SHA256SUMS hash', async t => {
  const { files, events, io } = fixture(t);
  const relNoDigest = connectorReleaseFromGitHub(githubConnector('relay-connector-v1.3.0', {
    assets: sample.assets.map(a => a.name === CHECKSUM_FILE ? { name: a.name, size: a.size } : a),
  }));
  await publishConnectorRelease(relNoDigest, files, io);
  const checksumVerify = events.find(e => e[0] === 'verify-public' && e[1].endsWith('/SHA256SUMS'));
  assert.equal(checksumVerify[2], sample.checksumSha256);
});

test('fails closed before R2 writes on tampered binary or bad SHA256SUMS, and surfaces public verification errors', async t => {
  const { files, events, io } = fixture(t);
  writeFileSync(files[CONNECTOR_BINARIES[0]], 'tampered');
  await assert.rejects(publishConnectorRelease(release, files, io), /mismatch/);
  assert.deepEqual(events, []);

  // Restore binary, tamper SHA256SUMS keeping same size.
  writeFileSync(files[CONNECTOR_BINARIES[0]], sample.binaries[CONNECTOR_BINARIES[0]].content);
  writeFileSync(files[CHECKSUM_FILE], sample.checksumText.replace(release.binaries[0].sha256, 'f'.repeat(64)));
  const relNoSumDigest = connectorReleaseFromGitHub(githubConnector('relay-connector-v1.3.0', {
    assets: sample.assets.map(a => a.name === CHECKSUM_FILE ? { name: a.name, size: a.size } : a),
  }));
  await assert.rejects(publishConnectorRelease(relNoSumDigest, files, io), /mismatch/);
  assert.deepEqual(events, []);

  // Restore SHA256SUMS, fail public verification.
  writeFileSync(files[CHECKSUM_FILE], sample.checksumContent);
  io.verifyPublicFile = async () => { throw new Error('Public download failed'); };
  await assert.rejects(publishConnectorRelease(release, files, io), /Public download failed/);

  // Verify verifyFile directly.
  await verifyFile(files[CONNECTOR_BINARIES[0]], release.binaries[0]);
});

// Black-box conformance tests: the same cases run against `wrangler dev --local` and the Bun server.
// Servers are started by run.ts, which passes their URLs in the environment.

import { describe, expect, test } from "bun:test";
import { b64uDecode } from "../../core/crypto.ts";
import {
  Conn,
  HostControl,
  bytes,
  connectApp,
  getJson,
  openSession,
  sleep,
  upgradeAttempt,
  wsUrl,
  type ReadyMsg,
} from "../client/client.ts";
import { newDeviceKey, newHostKey, verifySession } from "../client/keys.ts";
import { ADMIN_TOKEN, ACCESS_TOKEN } from "./profiles.ts";

const A = process.env.RELAY_A!;
const B = process.env.RELAY_B!;
const C = process.env.RELAY_C!;
const IMPL = process.env.RELAY_IMPL!;

const today = () => new Date().toISOString().slice(0, 10);
const closeCodes = (c: { code: number; reason: string }) => `${c.code} ${c.reason}`;

// ---- /v1/info ------------------------------------------------------------------------------

describe("info", () => {
  test("serves the capability document without credentials", async () => {
    const r = await getJson(A + "/v1/info");
    expect(r.status).toBe(200);
    const j = r.json as any;
    expect(j.v).toBe(1);
    expect(j.impl).toBe(IMPL);
    expect(typeof j.version).toBe("string");
    expect(j.access).toBe("open");
    expect(j.capabilities).toEqual({ dgram: "none" });
    expect(j.edges).toEqual(["104.17.5.5", "104.19.8.8"]);
    expect(j.policy.access).toEqual({ mode: "open", registrationsPerIpPerDay: 60 });
    expect(j.policy.control).toEqual({ kbps: 100, burstKb: 32 });
    expect(j.policy.media).toEqual({ allowed: true, kbps: 200, burstKb: 64 });
    expect(j.policy.limits).toEqual({ sessionsPerHost: 2, idleTimeoutS: 3 });
    expect(j.policy.quotas.hostDailyMb).toBe(0.25);
    expect(j.policy.budget.mediaReserve).toBe(0.3); // default filled in
  });

  test("answers even when an access code is required", async () => {
    const j = (await getJson(B + "/v1/info")).json as any;
    expect(j.access).toBe("token");
    expect(j.policy.access.mode).toBe("token");
    expect(j.policy.media.allowed).toBe(false);
  });

  test("unknown paths and bad requests carry a JSON reason", async () => {
    const r = await getJson(A + "/v1/nothing");
    expect(r.status).toBe(404);
    expect((r.json as any).reason).toBe("bad_request");
    const bad = await upgradeAttempt(wsUrl(A, "/v1/host?pub=nope"));
    expect(bad.status).toBe(400);
    expect((bad.json as any).reason).toBe("bad_request");
    const badCh = await upgradeAttempt(wsUrl(A, `/v1/connect/${"a".repeat(26)}?ch=video`));
    expect(badCh.status).toBe(400);
    expect((badCh.json as any).reason).toBe("bad_request");
    const badHost = await upgradeAttempt(wsUrl(A, `/v1/connect/short?ch=control`));
    expect(badHost.status).toBe(400);
  });
});

// ---- host authentication -------------------------------------------------------------------

describe("host authentication", () => {
  test("a correct signature yields hello with policy, usage and capabilities", async () => {
    const key = await newHostKey();
    const host = await HostControl.connect(A, key);
    expect(host.hello.hostId).toBe(key.hostId);
    expect(host.hello.capabilities).toEqual({ dgram: "none" });
    expect(host.hello.policy.control).toEqual({ kbps: 100, burstKb: 32 });
    expect(host.hello.usage.day).toBe(today());
    expect(host.hello.usage.dayMb).toBe(0);
    expect(host.hello.usage.budget).toBe("ok");
    host.close();
  });

  test("a signature by another key is refused with 4401", async () => {
    const key = await newHostKey();
    const other = await newHostKey();
    const { conn, nonce, host } = await HostControl.connectRaw(A, key);
    conn.send(JSON.stringify({ t: "auth", sig: await other.signChallenge(host, nonce) }));
    expect(closeCodes(await conn.waitClosed())).toBe("4401 unauthorized");
  });

  test("a signature over the wrong host string is refused", async () => {
    const key = await newHostKey();
    const { conn, nonce } = await HostControl.connectRaw(A, key);
    conn.send(JSON.stringify({ t: "auth", sig: await key.signChallenge("evil.example", nonce) }));
    expect((await conn.waitClosed()).code).toBe(4401);
  });

  test("garbage instead of auth is refused", async () => {
    const key = await newHostKey();
    const { conn } = await HostControl.connectRaw(A, key);
    conn.send("hello?");
    expect((await conn.waitClosed()).code).toBe(4401);
  });

  test("a new connection replaces the old one only after it proves the key", async () => {
    const key = await newHostKey();
    const first = await HostControl.connect(A, key);

    // An impostor connection that fails verification must not disturb the first.
    const other = await newHostKey();
    const bad = await HostControl.connectRaw(A, key);
    bad.conn.send(JSON.stringify({ t: "auth", sig: await other.signChallenge(bad.host, bad.nonce) }));
    expect((await bad.conn.waitClosed()).code).toBe(4401);
    first.conn.send("ping");
    expect(await first.conn.nextText()).toBe("pong");
    expect(first.conn.closed).toBeUndefined();

    // Pending (unauthenticated) second connection: first still online.
    const second = await HostControl.connectRaw(A, key);
    await sleep(200);
    expect(first.conn.closed).toBeUndefined();
    second.conn.send(JSON.stringify({ t: "auth", sig: await key.signChallenge(second.host, second.nonce) }));
    expect(((await second.conn.nextJson()) as any).t).toBe("hello");
    expect(closeCodes(await first.conn.waitClosed())).toBe("4000 replaced");
    second.conn.close();
  });

  test("the relay answers text ping with pong on the control connection", async () => {
    const host = await HostControl.connect(A);
    host.conn.send("ping");
    expect(await host.conn.nextText()).toBe("pong");
    host.close();
  });

  test("a hint is accepted, an unknown one ignored", async () => {
    const key = await newHostKey();
    const { conn, nonce, host } = await HostControl.connectRaw(A, key, { hint: "wnam" });
    conn.send(JSON.stringify({ t: "auth", sig: await key.signChallenge(host, nonce) }));
    expect(((await conn.nextJson()) as any).t).toBe("hello");
    conn.close();
    const k2 = await newHostKey();
    const r = await HostControl.connectRaw(A, k2, { hint: "mars" });
    r.conn.close();
  });
});

// ---- sessions ------------------------------------------------------------------------------

describe("sessions", () => {
  test("host_offline when no connector is online", async () => {
    const key = await newHostKey();
    const r = await upgradeAttempt(wsUrl(A, `/v1/connect/${key.hostId}?ch=control`));
    expect(r.status).toBe(404);
    expect((r.json as any).reason).toBe("host_offline");
    expect(typeof (r.json as any).retryAfter).toBe("number");
  });

  test("host_offline after the connector goes away", async () => {
    const host = await HostControl.connect(A);
    host.close();
    await host.conn.waitClosed();
    await sleep(300);
    const r = await upgradeAttempt(wsUrl(A, `/v1/connect/${host.hostId}?ch=control`));
    expect((r.json as any)?.reason).toBe("host_offline");
  });

  test("pairing, ready, and byte-exact forwarding in both directions", async () => {
    const host = await HostControl.connect(A);
    const s = await openSession(host, "control");
    expect(s.open.sid).toMatch(/^[a-f0-9]{32}$/);
    expect(s.open.ch).toBe("control");
    expect(s.open.pace).toEqual({ kbps: 100, burstKb: 32 });
    expect(s.ready.sid).toBe(s.sid);
    expect(s.ready.ch).toBe("control");
    expect(s.ready.pace).toEqual({ kbps: 100, burstKb: 32 });
    expect(s.ready.policy.limits.sessionsPerHost).toBe(2);
    expect(s.ready.usage.day).toBe(today());

    const up = Uint8Array.from({ length: 1000 }, (_, i) => i % 251);
    s.app.send(up);
    expect(Array.from(await s.data.nextBinary())).toEqual(Array.from(up));
    const down = Uint8Array.from({ length: 3000 }, (_, i) => (i * 7) % 256);
    s.data.send(down);
    expect(Array.from(await s.app.nextBinary())).toEqual(Array.from(down));
    s.app.send("text from app");
    expect(await s.data.nextText()).toBe("text from app");
    s.data.send("text from host");
    expect(await s.app.nextText()).toBe("text from host");
    s.app.close();
    host.close();
  });

  test("text ping is answered by the relay and never forwarded", async () => {
    const host = await HostControl.connect(A);
    const s = await openSession(host);
    s.app.send("ping");
    expect(await s.app.nextText()).toBe("pong");
    s.data.send("ping");
    expect(await s.data.nextText()).toBe("pong");
    s.app.send("marker-1");
    expect(await s.data.nextText()).toBe("marker-1"); // would be "ping" if it had been forwarded
    s.data.send("marker-2");
    expect(await s.app.nextText()).toBe("marker-2");
    s.app.close();
    host.close();
  });

  test("closing one side closes the other with 1000 peer_closed", async () => {
    const host = await HostControl.connect(A);
    const s1 = await openSession(host);
    s1.app.close(1000, "bye");
    expect(closeCodes(await s1.data.waitClosed())).toBe("1000 peer_closed");

    const s2 = await openSession(host);
    s2.data.close(4403, "denied");
    expect(closeCodes(await s2.app.waitClosed())).toBe("1000 peer_closed");
    host.close();
  });

  test("a frame over the size limit closes the session with 4413", async () => {
    const host = await HostControl.connect(A);
    const ok = await openSession(host);
    ok.app.send(bytes(65_536));
    expect((await ok.data.nextBinary()).length).toBe(65_536);
    ok.app.close();

    const big = await openSession(host);
    big.app.send(bytes(65_537));
    expect(closeCodes(await big.app.waitClosed())).toBe("4413 frame_too_large");
    expect(closeCodes(await big.data.waitClosed())).toBe("1000 peer_closed");

    const text = await openSession(host);
    text.data.send("x".repeat(4097));
    expect(closeCodes(await text.data.waitClosed())).toBe("4413 frame_too_large");
    expect(closeCodes(await text.app.waitClosed())).toBe("1000 peer_closed");
    host.close();
  });

  test("a frame sent before ready is a violation", async () => {
    const host = await HostControl.connect(A);
    const { conn } = await connectApp(A, host.hostId, "control");
    conn.send("too early");
    const c = await conn.waitClosed();
    expect(c.code).toBe(1008);
    host.close();
  });

  test("the data endpoint refuses unknown sids and bad formats", async () => {
    const host = await HostControl.connect(A);
    expect((await upgradeAttempt(wsUrl(A, `/v1/host/${host.hostId}/session/${"0".repeat(32)}`))).status).toBe(404);
    expect((await upgradeAttempt(wsUrl(A, `/v1/host/${host.hostId}/session/xyz`))).status).toBe(400);
    host.close();
  });

  test("an idle session closes with 1000 idle; binary frames reset the timer", async () => {
    const host = await HostControl.connect(A); // idleTimeoutS = 3
    const s = await openSession(host);
    await sleep(2000);
    s.app.send(bytes(10));
    await s.data.nextBinary();
    const t1 = Date.now();
    await sleep(1500);
    expect(s.app.closed).toBeUndefined(); // 3.5 s after pairing, but only 1.5 s after the last frame
    s.app.send("ping"); // pings do not count as activity
    expect(await s.app.nextText()).toBe("pong");
    const c = await s.app.waitClosed(10_000);
    expect(closeCodes(c)).toBe("1000 idle");
    expect(Date.now() - t1).toBeGreaterThan(2000);
    expect(closeCodes(await s.data.waitClosed())).toBe("1000 idle");
    host.close();
  }, 20_000);

  test("end-to-end handshake messages are forwarded verbatim and the signature verifies", async () => {
    const host = await HostControl.connect(A);
    const device = await newDeviceKey();
    const s = await openSession(host);

    // Deliberately odd spacing and key order: the relay must not parse or re-serialize.
    const nonce = "bm9uY2Utbm9uY2Utbm9uY2Utbm9uY2Utbm9uY2U";
    const challenge = `{ "nonce":"${nonce}",  "t":"challenge" ,"v":1 }`;
    s.data.send(challenge);
    expect(await s.app.nextText()).toBe(challenge);

    const sig = await device.signSession(host.hostId, s.sid, "control", nonce);
    const authMsg = `{"t":"auth",  "device":"${device.pubB64u}","sig":"${sig}"}`;
    s.app.send(authMsg);
    const got = await s.data.nextText();
    expect(got).toBe(authMsg);

    const parsed = JSON.parse(got) as { device: string; sig: string };
    const ok = await verifySession(b64uDecode(parsed.device)!, b64uDecode(parsed.sig)!, host.hostId, s.sid, "control", nonce);
    expect(ok).toBe(true);
    // And a replay in another session would not verify.
    expect(await verifySession(b64uDecode(parsed.device)!, b64uDecode(parsed.sig)!, host.hostId, "f".repeat(32), "control", nonce)).toBe(false);

    s.data.send('{"t":"ok"}');
    expect(await s.app.nextText()).toBe('{"t":"ok"}');
    const ssh = Uint8Array.from({ length: 200 }, (_, i) => i);
    s.app.send(ssh);
    expect(Array.from(await s.data.nextBinary())).toEqual(Array.from(ssh));
    s.app.close();
    host.close();
  });
});

// ---- policy --------------------------------------------------------------------------------

describe("policy", () => {
  test("too_many_sessions at the per-host limit", async () => {
    const host = await HostControl.connect(A); // sessionsPerHost = 2
    const s1 = await openSession(host);
    const s2 = await openSession(host);
    const r = await upgradeAttempt(wsUrl(A, `/v1/connect/${host.hostId}?ch=control`));
    expect(r.status).toBe(429);
    expect((r.json as any).reason).toBe("too_many_sessions");
    s1.app.close();
    await s1.data.waitClosed();
    await sleep(300);
    const s3 = await openSession(host);
    expect(s3.ready.t).toBe("ready");
    s2.app.close();
    s3.app.close();
    host.close();
  });

  test("rate_limited when a direction exceeds 1.5x its allowance within 10 s", async () => {
    const host = await HostControl.connect(A); // control: 100 kbps, burst 32 KB => 236,652 bytes per 10 s
    const s = await openSession(host);
    for (let i = 0; i < 4; i++) s.app.send(bytes(65_536));
    const c = await s.app.waitClosed(5000);
    expect(closeCodes(c)).toBe("4429 rate_limited");
    expect(closeCodes(await s.data.waitClosed())).toBe("4429 rate_limited");
    host.close();
  });

  test("rate_limited when a direction sends more than 6000 frames within 10 s, however small", async () => {
    const host = await HostControl.connect(A);
    const s = await openSession(host);
    const one = bytes(1);
    for (let i = 0; i < 6_001; i++) s.data.send(one);
    expect(closeCodes(await s.data.waitClosed(10_000))).toBe("4429 rate_limited");
    expect(closeCodes(await s.app.waitClosed())).toBe("4429 rate_limited");
    host.close();
  });

  test("text beyond the end-to-end handshake is refused with 1008", async () => {
    const host = await HostControl.connect(A);
    const s = await openSession(host);
    s.app.send('{"t":"auth"}');
    s.app.send("second");
    expect(await s.data.nextText()).toBe('{"t":"auth"}');
    expect(await s.data.nextText()).toBe("second");
    s.app.send("ping"); // never counted
    expect(await s.app.nextText()).toBe("pong");
    s.app.send("third");
    expect(closeCodes(await s.app.waitClosed())).toBe("1008 bad_request");
    expect(closeCodes(await s.data.waitClosed())).toBe("1000 peer_closed");

    host.conn.send('{"t":"status"}'); // an authenticated control connection may only ping
    expect(closeCodes(await host.conn.waitClosed())).toBe("1008 bad_request");
  });

  test("media is subject to its own, higher allowance", async () => {
    const host = await HostControl.connect(A); // media: 200 kbps, burst 64 KB => 473,304 bytes per 10 s
    const s = await openSession(host, "media");
    expect(s.ready.ch).toBe("media");
    expect(s.open.pace).toEqual({ kbps: 200, burstKb: 64 });
    for (let i = 0; i < 4; i++) s.app.send(bytes(65_536)); // 262,144: over the control allowance, under media's
    for (let i = 0; i < 4; i++) await s.data.nextBinary();
    expect(s.app.closed).toBeUndefined();
    s.app.close();
    host.close();
  });

  test("quota_exhausted with retryAfter once the daily quota is used", async () => {
    const host = await HostControl.connect(A); // hostDailyMb = 0.25 MiB = 262,144 bytes
    for (let round = 0; round < 2; round++) {
      const s = await openSession(host);
      for (let i = 0; i < 3; i++) s.app.send(bytes(46_000));
      for (let i = 0; i < 3; i++) await s.data.nextBinary();
      s.app.close();
      await s.data.waitClosed();
      await sleep(200);
    }
    const r = await upgradeAttempt(wsUrl(A, `/v1/connect/${host.hostId}?ch=control`));
    expect(r.status).toBe(429);
    expect((r.json as any).reason).toBe("quota_exhausted");
    const retry = (r.json as any).retryAfter as number;
    expect(retry).toBeGreaterThan(0);
    expect(retry).toBeLessThanOrEqual(86_400);
    // Media is cut off by the same daily quota.
    const m = await upgradeAttempt(wsUrl(A, `/v1/connect/${host.hostId}?ch=media`));
    expect((m.json as any).reason).toBe("quota_exhausted");
    host.close();
  });

  test("order of checks: host_offline comes before everything else", async () => {
    const key = await newHostKey();
    const r = await upgradeAttempt(wsUrl(B, `/v1/connect/${key.hostId}?ch=media`), { authorization: `Bearer ${ACCESS_TOKEN}` });
    expect((r.json as any).reason).toBe("host_offline");
  });
});

// ---- metering ------------------------------------------------------------------------------

describe("metering", () => {
  test("usage in ready and hello reflects forwarded bytes and active minutes", async () => {
    const key = await newHostKey();
    const host = await HostControl.connect(A, key);
    const s = await openSession(host, "control");
    expect(s.ready.usage.dayMb).toBe(0);
    expect(s.ready.usage.dayActiveMin).toBe(0);

    s.app.send(bytes(30_000));
    s.data.send(bytes(20_000));
    s.app.send(bytes(10_000));
    for (let i = 0; i < 2; i++) await s.data.nextBinary();
    await s.app.nextBinary();
    s.app.send("text frames are not metered");
    await s.data.nextText();
    s.app.close();
    await s.data.waitClosed();
    await sleep(300);

    const media = await openSession(host, "media");
    // Control: 60,000 bytes; at the moment media's ready was built, nothing on media yet.
    const u = media.ready.usage;
    expect(u.day).toBe(today());
    expect(u.month).toBe(today().slice(0, 7));
    expect(u.dayMb).toBeCloseTo(60_000 / 1048576, 3);
    expect(u.monthControlMb).toBeCloseTo(60_000 / 1048576, 3);
    expect(u.monthMediaMb).toBe(0);
    expect([1, 2]).toContain(u.dayActiveMin); // one UTC minute, two if the test straddled a boundary
    expect(u.budget).toBe("ok");
    expect(new Date(u.resetsAt).getTime()).toBeGreaterThan(Date.now());
    expect(u.resetsAt).toMatch(/T00:00:00Z$/);

    media.data.send(bytes(25_000));
    await media.app.nextBinary();
    media.app.close();
    await media.data.waitClosed();
    host.close();
    await host.conn.waitClosed();
    await sleep(300);

    // A fresh control connection sees both channels, and `hello` carries the same numbers.
    const again = await HostControl.connect(A, key);
    expect(again.hello.usage.dayMb).toBeCloseTo(85_000 / 1048576, 3);
    expect(again.hello.usage.monthControlMb).toBeCloseTo(60_000 / 1048576, 3);
    expect(again.hello.usage.monthMediaMb).toBeCloseTo(25_000 / 1048576, 3);
    expect(again.hello.usage.dayActiveMin).toBe(u.dayActiveMin);
    again.close();
  });
});

// ---- access code and admin -----------------------------------------------------------------

describe("access code", () => {
  test("requests without a valid code get 401 unauthorized", async () => {
    const key = await newHostKey();
    const none = await upgradeAttempt(wsUrl(B, `/v1/host?pub=${key.pubB64u}`));
    expect(none.status).toBe(401);
    expect((none.json as any).reason).toBe("unauthorized");
    const wrong = await upgradeAttempt(wsUrl(B, `/v1/host?pub=${key.pubB64u}`), { authorization: "Bearer nope" });
    expect(wrong.status).toBe(401);
    const app = await upgradeAttempt(wsUrl(B, `/v1/connect/${key.hostId}?ch=control`));
    expect(app.status).toBe(401);
    expect((app.json as any).reason).toBe("unauthorized");
    const prefix = await upgradeAttempt(wsUrl(B, `/v1/connect/${key.hostId}?ch=control`), { authorization: `Bearer ${ACCESS_TOKEN.slice(0, -1)}` });
    expect(prefix.status).toBe(401);
  });

  test("with the code, the whole flow works; the data connection needs only the sid", async () => {
    const host = await HostControl.connect(B, undefined, { token: ACCESS_TOKEN });
    const s = await openSession(host, "control", { token: ACCESS_TOKEN });
    expect(s.ready.policy.access.mode).toBe("token");
    s.app.send(bytes(10));
    expect((await s.data.nextBinary()).length).toBe(10);
    s.app.close();
    host.close();
  });

  test("media_disabled when the relay offers no media channel", async () => {
    const host = await HostControl.connect(B, undefined, { token: ACCESS_TOKEN });
    const r = await upgradeAttempt(wsUrl(B, `/v1/connect/${host.hostId}?ch=media`), { authorization: `Bearer ${ACCESS_TOKEN}` });
    expect(r.status).toBe(403);
    expect((r.json as any).reason).toBe("media_disabled");
    host.close();
  });
});

describe("daily message quota", () => {
  test("past the quota the host's running sessions are cut with 4429 and new ones refused; ready carries the count", async () => {
    const opts = { token: ACCESS_TOKEN };
    const host = await HostControl.connect(B, undefined, opts); // hostDailyMessages = 100
    expect(host.hello.usage).toMatchObject({ dayMessages: 0 });
    expect(host.hello.policy.quotas.hostDailyMessages).toBe(100);
    const first = await openSession(host, "control", opts);
    for (let i = 0; i < 60; i++) first.app.send(bytes(1));
    for (let i = 0; i < 60; i++) await first.data.nextBinary();
    const second = await openSession(host, "control", opts);
    expect((second.ready.usage as any).dayMessages).toBe(60);
    for (let i = 0; i < 41; i++) second.data.send(bytes(1)); // the 101st message
    for (const c of [first.app, first.data, second.app, second.data]) {
      expect(closeCodes(await c.waitClosed())).toBe("4429 quota_exhausted");
    }
    const refused = await upgradeAttempt(wsUrl(B, `/v1/connect/${host.hostId}?ch=control`), { authorization: `Bearer ${ACCESS_TOKEN}` });
    expect(refused.status).toBe(429);
    expect((refused.json as any).reason).toBe("quota_exhausted");
    expect((refused.json as any).retryAfter).toBeGreaterThan(0);
    host.close();
  });
});

describe("admin", () => {
  test("/v1/admin/stats needs the admin token", async () => {
    for (const base of [A, B, C]) {
      expect((await getJson(base + "/v1/admin/stats")).status).toBe(401);
      expect((await getJson(base + "/v1/admin/stats", { authorization: "Bearer wrong" })).status).toBe(401);
      expect((await getJson(base + "/v1/admin/stats", { authorization: `Bearer ${ACCESS_TOKEN}` })).status).toBe(401);
      const ok = await getJson(base + "/v1/admin/stats", { authorization: `Bearer ${ADMIN_TOKEN}` });
      expect(ok.status).toBe(200);
      const j = ok.json as any;
      expect(j.day).toBe(today());
      expect(["ok", "media_paused", "closed"]).toContain(j.budget);
      expect(typeof j.usedMb).toBe("number");
      expect(typeof j.requests).toBe("number");
      expect(j.limits).toHaveProperty("globalDailyRequests");
    }
    // Server A has carried sessions by now (metering flushes when a session closes).
    const a = (await getJson(A + "/v1/admin/stats", { authorization: `Bearer ${ADMIN_TOKEN}` })).json as any;
    expect(a.requests).toBeGreaterThan(0);
  });
});

// ---- global budget -------------------------------------------------------------------------

async function adminStats(): Promise<any> {
  return (await getJson(C + "/v1/admin/stats", { authorization: `Bearer ${ADMIN_TOKEN}` })).json;
}

async function waitBudget(state: string): Promise<void> {
  for (let i = 0; i < 50; i++) {
    if ((await adminStats()).budget === state) return;
    await sleep(100);
  }
  throw new Error(`budget never reached ${state}: ` + JSON.stringify(await adminStats()));
}

describe("global budget", () => {
  test("media pauses first, then everything new is refused; running control sessions finish", async () => {
    expect((await adminStats()).budget).toBe("ok");

    // 300,000 of 524,288 bytes: less than 50% remains => media_paused.
    const h1 = await HostControl.connect(C);
    const s1 = await openSession(h1);
    for (let i = 0; i < 5; i++) s1.app.send(bytes(60_000));
    for (let i = 0; i < 5; i++) await s1.data.nextBinary();
    s1.app.close();
    await s1.data.waitClosed();
    await waitBudget("media_paused");

    const h2 = await HostControl.connect(C); // fresh host: asks the budget itself
    expect(h2.hello.usage.budget).toBe("media_paused");
    const media = await upgradeAttempt(wsUrl(C, `/v1/connect/${h2.hostId}?ch=media`));
    expect(media.status).toBe(503);
    expect((media.json as any).reason).toBe("relay_busy");
    expect((media.json as any).retryAfter).toBeGreaterThan(0);

    // Control still works, and its ready reports the state.
    const s2 = await openSession(h2, "control");
    expect(s2.ready.usage.budget).toBe("media_paused");
    for (let i = 0; i < 5; i++) s2.app.send(bytes(60_000));
    for (let i = 0; i < 5; i++) await s2.data.nextBinary();
    await sleep(100);
    s2.data.close();
    await s2.app.waitClosed();
    await waitBudget("closed");

    // Closed: new sessions are refused on both channels...
    for (const ch of ["control", "media"]) {
      const r = await upgradeAttempt(wsUrl(C, `/v1/connect/${h2.hostId}?ch=${ch}`));
      expect(r.status).toBe(503);
      expect((r.json as any).reason).toBe("relay_busy");
    }
    // ...and a new host cannot even get a session (it can still connect to the relay).
    const h3 = await HostControl.connect(C);
    expect(h3.hello.usage.budget).toBe("closed");
    const r3 = await upgradeAttempt(wsUrl(C, `/v1/connect/${h3.hostId}?ch=control`));
    expect((r3.json as any).reason).toBe("relay_busy");
    h1.close();
    h2.close();
    h3.close();
  });
});


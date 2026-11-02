// Engine behaviour with a fake platform and a manual clock: timers, write-back cadence, hibernation
// recovery, enforcement. Wire-level behaviour is covered by the conformance suite.

import { describe, expect, test } from "bun:test";
import { parseConfig, type RelayConfig } from "../../core/config.ts";
import { Engine, type SessAtt } from "../../core/engine.ts";
import { HostLedger } from "../../core/ledger.ts";
import { MIB, utcDay } from "../../core/time.ts";
import { sqlite } from "../../bun/relay.ts";
import { newHostKey, type HostKey } from "../client/keys.ts";
import { FakeSock, World } from "./fake.ts";

const bin = (n: number) => new Uint8Array(n);

function setup(cfgInput: unknown = {}) {
  const w = new World();
  const cfg = parseConfig(cfgInput);
  const engine = new Engine(w.platform(), cfg);
  return { w, cfg, engine };
}

/** Wake: a new Engine over the same sockets and storage, as after Durable Object hibernation. */
function wake(w: World, cfg: RelayConfig): Engine {
  return new Engine(w.platform(), cfg);
}

async function connectHost(w: World, engine: Engine, key: HostKey, host = "relay.test"): Promise<FakeSock> {
  const res = await engine.start({ kind: "host", hostId: key.hostId, pub: key.pub }, w.requestCtx(host));
  expect(res.status).toBe(200); // the fake accept returns a plain response
  const s = w.socks.at(-1)!;
  const nonce = s.json(0).nonce;
  await engine.onMessage(s, JSON.stringify({ t: "auth", sig: await key.signChallenge(host, nonce) }));
  return s;
}

async function openSession(w: World, engine: Engine, key: HostKey, host: FakeSock, ch: "control" | "media" = "control") {
  const res = await engine.start({ kind: "connect", hostId: key.hostId, ch }, w.requestCtx());
  expect(res.status).toBe(200);
  const app = w.socks.at(-1)!;
  const open = host.texts.filter((t) => t.t === "open").at(-1);
  const dres = await engine.start({ kind: "data", hostId: key.hostId, sid: open.sid }, w.requestCtx());
  expect(dres.status).toBe(200);
  const data = w.socks.at(-1)!;
  return { app, data, sid: open.sid as string, ready: app.texts.find((t) => t.t === "ready") };
}

const stored = (w: World, key: HostKey) => new HostLedger(sqlite(w.db), key.hostId).totals(w.t);

describe("control connection", () => {
  test("challenge, auth, hello; a bad signature is 4401", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    expect(host.texts[0].t).toBe("challenge");
    expect(host.texts[1]).toMatchObject({ t: "hello", hostId: key.hostId, capabilities: { dgram: "none" } });
    expect(host.texts[1].usage.dayMb).toBe(0);

    await engine.start({ kind: "host", hostId: key.hostId, pub: key.pub }, w.requestCtx());
    const bad = w.socks.at(-1)!;
    const other = await newHostKey();
    await engine.onMessage(bad, JSON.stringify({ t: "auth", sig: await other.signChallenge("relay.test", bad.json(0).nonce) }));
    expect(bad.closedWith).toEqual([4401, "unauthorized"]);
    expect(host.closedWith).toBeUndefined();
  });

  test("the old connection is replaced only once the new one has authenticated", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    const first = await connectHost(w, engine, key);
    await engine.start({ kind: "host", hostId: key.hostId, pub: key.pub }, w.requestCtx());
    const second = w.socks.at(-1)!;
    expect(first.closedWith).toBeUndefined();
    await engine.onMessage(second, JSON.stringify({ t: "auth", sig: await key.signChallenge("relay.test", second.json(0).nonce) }));
    expect(first.closedWith).toEqual([4000, "replaced"]);
    expect(second.texts.at(-1).t).toBe("hello");
  });

  test("the signature covers the Host header the connector saw", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    await engine.start({ kind: "host", hostId: key.hostId, pub: key.pub }, w.requestCtx("relay.test"));
    const s = w.socks.at(-1)!;
    await engine.onMessage(s, JSON.stringify({ t: "auth", sig: await key.signChallenge("elsewhere.test", s.json(0).nonce) }));
    expect(s.closedWith?.[0]).toBe(4401);
  });

  test("no answer within 10 s: the alarm closes it with 4401", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    await engine.start({ kind: "host", hostId: key.hostId, pub: key.pub }, w.requestCtx());
    const s = w.socks.at(-1)!;
    expect(w.alarm).toBe(w.t + 10_000);
    w.t += 9_000;
    await engine.onAlarm();
    expect(s.closedWith).toBeUndefined();
    w.t += 1_000;
    await engine.onAlarm();
    expect(s.closedWith).toEqual([4401, "unauthorized"]);
    expect(w.alarm).toBeNull();
  });

  test("a late auth is refused even before the alarm runs", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    await engine.start({ kind: "host", hostId: key.hostId, pub: key.pub }, w.requestCtx());
    const s = w.socks.at(-1)!;
    w.t += 10_500;
    await engine.onMessage(s, JSON.stringify({ t: "auth", sig: await key.signChallenge("relay.test", s.json(0).nonce) }));
    expect(s.closedWith?.[0]).toBe(4401);
  });

  test("only new hosts count against the per-IP registration limit", async () => {
    const { w, engine } = setup();
    w.registrationLimit = 1;
    const key = await newHostKey();
    await connectHost(w, engine, key);
    expect(w.registrations).toBe(1);
    await connectHost(w, engine, key); // already registered: no second count
    expect(w.registrations).toBe(1);
    const stranger = await newHostKey();
    const e2 = new Engine(w.platform(), parseConfig({}));
    const res = await e2.start({ kind: "host", hostId: stranger.hostId, pub: stranger.pub }, w.requestCtx());
    expect(res.status).toBe(429);
    expect(await res.json()).toEqual({ reason: "too_many_registrations", retryAfter: 60 });
  });

  test("a host is counted and given storage only once it proves its key", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    await engine.start({ kind: "host", hostId: key.hostId, pub: key.pub }, w.requestCtx());
    const silent = w.socks.at(-1)!;
    expect(w.ipChecks).toBe(1);
    w.t += 11_000;
    await engine.onAlarm();
    expect(silent.closedWith).toEqual([4401, "unauthorized"]);
    expect(w.registrations).toBe(0);
    expect(sqlite(w.db).all("SELECT name FROM sqlite_master")).toEqual([]);

    // A wrong signature leaves nothing either.
    await engine.start({ kind: "host", hostId: key.hostId, pub: key.pub }, w.requestCtx());
    const bad = w.socks.at(-1)!;
    const other = await newHostKey();
    await engine.onMessage(bad, JSON.stringify({ t: "auth", sig: await other.signChallenge("relay.test", bad.json(0).nonce) }));
    expect(bad.closedWith?.[0]).toBe(4401);
    expect(w.registrations).toBe(0);
    expect(sqlite(w.db).all("SELECT name FROM sqlite_master")).toEqual([]);

    const host = await connectHost(w, engine, key);
    expect(host.texts.at(-1).t).toBe("hello");
    expect(w.registrations).toBe(1);
    expect(new HostLedger(sqlite(w.db), key.hostId).registered()).toBe(true);
    expect("reg" in host.a).toBe(false); // the address does not outlive the handshake
  });

  test("an unreachable budget service does not stop a new host from registering", async () => {
    const { w, engine } = setup();
    w.budget.checkIp = async () => {
      throw new Error("down");
    };
    w.budget.registerIp = async () => {
      throw new Error("down");
    };
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    expect(host.texts.at(-1).t).toBe("hello");
    expect(new HostLedger(sqlite(w.db), key.hostId).registered()).toBe(true);
  });

  test("an unregistered, refused host leaves nothing in storage", async () => {
    const { w, engine } = setup();
    w.registrationLimit = 0;
    const key = await newHostKey();
    await engine.start({ kind: "host", hostId: key.hostId, pub: key.pub }, w.requestCtx());
    expect(sqlite(w.db).all("SELECT name FROM sqlite_master")).toEqual([]);
  });
});

describe("sessions", () => {
  test("connect: open to the connector, then pairing, ready, forwarding", async () => {
    const { w, engine } = setup({ policy: { control: { kbps: 250, burstKb: 10 } } });
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const { app, data, sid, ready } = await openSession(w, engine, key, host);
    const open = host.texts.find((t) => t.t === "open");
    expect(open).toEqual({ t: "open", sid, ch: "control", pace: { kbps: 250, burstKb: 10 } });
    expect(sid).toMatch(/^[a-f0-9]{32}$/);
    expect(ready).toMatchObject({ t: "ready", sid, ch: "control", pace: { kbps: 250, burstKb: 10 } });
    expect(ready.policy.control).toEqual({ kbps: 250, burstKb: 10 });

    await engine.onMessage(app, bin(5));
    expect(data.sent).toEqual([bin(5)]);
    await engine.onMessage(data, "hello");
    expect(app.sent.at(-1)).toBe("hello");
    await engine.onMessage(app, "ping");
    expect(app.sent.at(-1)).toBe("pong");
    expect(data.sent.length).toBe(1);
  });

  test("a client frame before ready is a violation", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    await engine.start({ kind: "connect", hostId: key.hostId, ch: "control" }, w.requestCtx());
    const app = w.socks.at(-1)!;
    await engine.onMessage(app, "early");
    expect(app.closedWith).toEqual([1008, "bad_request"]);
    expect(host.texts.some((t) => t.t === "open")).toBe(true);
  });

  test("host_timeout: the pairing deadline is 10 s", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    await connectHost(w, engine, key);
    await engine.start({ kind: "connect", hostId: key.hostId, ch: "control" }, w.requestCtx());
    const app = w.socks.at(-1)!;
    w.t += 9_990;
    await engine.onAlarm();
    expect(app.closedWith).toBeUndefined();
    w.t += 20;
    await engine.onAlarm();
    expect(app.closedWith).toEqual([4408, "host_timeout"]);
  });

  test("a data connection for an unknown, used or closed sid is refused", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const s = await openSession(w, engine, key, host);
    for (const sid of ["0".repeat(32), s.sid]) {
      const r = await engine.start({ kind: "data", hostId: key.hostId, sid }, w.requestCtx());
      expect(r.status).toBe(404);
    }
  });

  test("closing one side closes the other with 1000 peer_closed; frames over the limit with 4413", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const a = await openSession(w, engine, key, host);
    engine.onClose(a.app);
    expect(a.data.closedWith).toEqual([1000, "peer_closed"]);

    const b = await openSession(w, engine, key, host);
    await engine.onMessage(b.app, bin(65_536));
    expect(b.data.sent.length).toBe(1);
    await engine.onMessage(b.app, bin(65_537));
    expect(b.app.closedWith).toEqual([4413, "frame_too_large"]);
    expect(b.data.closedWith).toEqual([1000, "peer_closed"]);

    const c = await openSession(w, engine, key, host);
    await engine.onMessage(c.data, "x".repeat(4096));
    expect(c.app.sent.at(-1)).toBe("x".repeat(4096));
    await engine.onMessage(c.data, "é".repeat(2049)); // 4098 bytes in UTF-8
    expect(c.data.closedWith).toEqual([4413, "frame_too_large"]);
  });

  test("idle timeout counts binary frames only, per session", async () => {
    const { w, engine } = setup({ policy: { limits: { sessionsPerHost: 4, idleTimeoutS: 100 } } });
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const s = await openSession(w, engine, key, host);
    w.t += 60_000;
    await engine.onMessage(s.app, bin(1));
    await engine.onMessage(s.app, "ping");
    w.t += 60_000; // 120 s since pairing but 60 s since the last binary frame
    await engine.onAlarm();
    expect(s.app.closedWith).toBeUndefined();
    w.t += 40_000;
    await engine.onAlarm();
    expect(s.app.closedWith).toEqual([1000, "idle"]);
    expect(s.data.closedWith).toEqual([1000, "idle"]);
  });

  test("rate limiting uses the channel's pace and a 10 s window", async () => {
    // 80 kbps, burst 10 KB: 1.5 x (100,000 + 10,240) = 165,360 bytes per 10 s
    const { w, engine } = setup({ policy: { control: { kbps: 80, burstKb: 10 } } });
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const s = await openSession(w, engine, key, host);
    await engine.onMessage(s.app, bin(60_000));
    await engine.onMessage(s.app, bin(60_000));
    await engine.onMessage(s.data, bin(60_000)); // the other direction has its own window
    await engine.onMessage(s.data, bin(60_000));
    expect(s.app.closedWith).toBeUndefined();
    await engine.onMessage(s.app, bin(45_360)); // total 165,360: at the limit, allowed
    expect(s.app.closedWith).toBeUndefined();
    await engine.onMessage(s.app, bin(1));
    expect(s.app.closedWith).toEqual([4429, "rate_limited"]);
    expect(s.data.closedWith).toEqual([4429, "rate_limited"]);

    const t = await openSession(w, engine, key, host);
    for (let i = 0; i < 4; i++) await engine.onMessage(t.app, bin(40_000));
    w.t += 10_000;
    for (let i = 0; i < 4; i++) await engine.onMessage(t.app, bin(40_000)); // earlier bytes have left the window
    expect(t.app.closedWith).toBeUndefined();
  });

  test("the sessions limit counts unpaired sessions", async () => {
    const { w, engine } = setup({ policy: { limits: { sessionsPerHost: 2, idleTimeoutS: 300 } } });
    const key = await newHostKey();
    await connectHost(w, engine, key);
    for (let i = 0; i < 2; i++) expect((await engine.start({ kind: "connect", hostId: key.hostId, ch: "control" }, w.requestCtx())).status).toBe(200);
    const third = await engine.start({ kind: "connect", hostId: key.hostId, ch: "control" }, w.requestCtx());
    expect(third.status).toBe(429);
    expect((await third.json()).reason).toBe("too_many_sessions");
  });
});

describe("metering", () => {
  test("write-back: immediately after a quiet spell, then at most every 30 s", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const s = await openSession(w, engine, key, host);

    await engine.onMessage(s.app, bin(1000));
    expect(stored(w, key).dayControl).toBe(0); // in memory only
    expect(w.alarm).toBeLessThan(w.t + 100); // lastFlush was never: due at once
    w.t += 20;
    await engine.onAlarm();
    expect(stored(w, key).dayControl).toBe(1000);
    expect(w.reports).toEqual([{ bytes: 1000, minutes: 1 }]);

    w.t += 1_000;
    await engine.onMessage(s.app, bin(2000));
    await engine.onMessage(s.data, bin(3000));
    const flushedAt = new HostLedger(sqlite(w.db), key.hostId).meta().lastFlush;
    expect(w.alarm).toBe(flushedAt + 30_000);

    w.t = flushedAt + 29_000;
    await engine.onAlarm();
    expect(stored(w, key).dayControl).toBe(1000); // too early
    w.t = flushedAt + 30_000;
    await engine.onAlarm();
    expect(stored(w, key).dayControl).toBe(6000);
    expect(w.reports.at(-1)).toEqual({ bytes: 5000, minutes: 0 });
    expect(w.reports.length).toBe(2);
  });

  test("closing a session writes back at once", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const s = await openSession(w, engine, key, host, "media");
    await engine.onMessage(s.app, bin(4000));
    engine.onClose(s.app);
    await w.settle();
    expect(stored(w, key).dayMedia).toBe(4000);
    expect(stored(w, key).monthMedia).toBe(4000);
  });

  test("usage includes bytes not yet written; ready and hello agree", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const a = await openSession(w, engine, key, host);
    for (let i = 0; i < 4; i++) await engine.onMessage(a.app, bin(MIB / 16));
    for (let i = 0; i < 4; i++) await engine.onMessage(a.data, bin(MIB / 16));
    const b = await openSession(w, engine, key, host, "media");
    expect(b.ready.usage).toMatchObject({ day: "2026-11-01", dayMb: 0.5, monthControlMb: 0.5, monthMediaMb: 0, dayActiveMin: 1, budget: "ok" });
  });

  test("active minutes are counted once per UTC minute, across sessions and hibernation", async () => {
    const { w, engine, cfg } = setup();
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const a = await openSession(w, engine, key, host);
    const b = await openSession(w, engine, key, host);

    await engine.onMessage(a.app, bin(10));
    await engine.onMessage(b.app, bin(10)); // same minute, other session
    w.t += 20_000;
    await engine.onMessage(a.data, bin(10)); // same minute still
    expect(new HostLedger(sqlite(w.db), key.hostId).totals(w.t).dayActiveMin).toBe(0);

    // Hibernate: all memory is lost, only attachments and storage remain.
    const e2 = wake(w, cfg);
    await e2.onMessage(a.app, bin(10)); // same minute: must not count again
    w.t += 40_000; // next minute
    await e2.onMessage(a.app, bin(10));
    w.t += 20;
    await e2.onAlarm();
    await w.settle();
    expect(stored(w, key).dayActiveMin).toBe(2);
    expect(stored(w, key).dayControl).toBe(50);
    // And once more after a second wake, in the second minute.
    const e3 = wake(w, cfg);
    await e3.onMessage(b.app, bin(10));
    e3.onClose(b.app);
    await w.settle();
    expect(stored(w, key).dayActiveMin).toBe(2);
  });

  test("unflushed counts survive hibernation and are written by the next alarm", async () => {
    const { w, engine, cfg } = setup();
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const s = await openSession(w, engine, key, host, "media");
    await engine.onMessage(s.app, bin(777));
    w.t += 20;
    await engine.onAlarm(); // first flush
    w.t += 1_000;
    await engine.onMessage(s.app, bin(5000));
    const woken = wake(w, cfg);
    expect(woken.isIdle()).toBe(false);
    const u = (await woken.start({ kind: "connect", hostId: key.hostId, ch: "control" }, w.requestCtx())).status;
    expect(u).toBe(200);
    const app2 = w.socks.at(-1)!;
    const open = host.texts.filter((t) => t.t === "open").at(-1);
    await woken.start({ kind: "data", hostId: key.hostId, sid: open.sid }, w.requestCtx());
    expect(app2.texts.find((t) => t.t === "ready").usage.monthMediaMb).toBeCloseTo((777 + 5000) / MIB, 3);
    w.t += 30_000;
    await woken.onAlarm();
    expect(stored(w, key).dayMedia).toBe(777 + 5000);
  });

  test("counts left unwritten before midnight keep their day when the object wakes after it", async () => {
    const { w, cfg, engine } = setup();
    w.t = Date.UTC(2026, 10, 1, 23, 59, 0);
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const s = await openSession(w, engine, key, host);
    await engine.onMessage(s.app, bin(1000));
    w.t += 1000;
    await engine.onAlarm(); // first write-back after a quiet spell is immediate
    await w.settle();
    w.t = Date.UTC(2026, 10, 1, 23, 59, 55);
    await engine.onMessage(s.app, bin(6000)); // the alarm for it never runs before midnight
    // Evicted; the alarm wakes a new object after midnight.
    w.t = Date.UTC(2026, 10, 2, 0, 0, 30);
    const woken = wake(w, cfg);
    await woken.onAlarm();
    await w.settle();
    expect(sqlite(w.db).all("SELECT day, bytes FROM usage_day ORDER BY day")).toEqual([{ day: "2026-11-01", bytes: 7000 }]);
    // New traffic after the wake belongs to the new day.
    await woken.onMessage(s.app, bin(50));
    woken.onClose(s.app);
    await w.settle();
    expect(sqlite(w.db).all("SELECT day, bytes FROM usage_day ORDER BY day")).toEqual([
      { day: "2026-11-01", bytes: 7000 },
      { day: "2026-11-02", bytes: 50 },
    ]);
  });

  test("a day change flushes the previous day's counts to the previous day", async () => {
    const { w, engine } = setup();
    w.t = Date.UTC(2026, 10, 1, 23, 59, 50);
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const s = await openSession(w, engine, key, host);
    await engine.onMessage(s.app, bin(100));
    w.t = Date.UTC(2026, 10, 2, 0, 0, 5);
    await engine.onMessage(s.app, bin(200));
    await w.settle();
    const ledger = new HostLedger(sqlite(w.db), key.hostId);
    const rows = sqlite(w.db).all<{ day: string; bytes: number }>("SELECT day, bytes FROM usage_day ORDER BY day");
    expect(rows).toEqual([{ day: "2026-11-01", bytes: 100 }]);
    engine.onClose(s.app);
    await w.settle();
    expect(sqlite(w.db).all("SELECT day, bytes FROM usage_day ORDER BY day")).toEqual([
      { day: "2026-11-01", bytes: 100 },
      { day: "2026-11-02", bytes: 200 },
    ]);
    expect(utcDay(w.t)).toBe("2026-11-02");
    expect(ledger.totals(w.t).dayControl).toBe(200);
  });
});

describe("enforcement and budget", () => {
  test("a media session is cut with 4429 at the write-back after its quota is used; control continues", async () => {
    const { w, engine } = setup({ policy: { quotas: { hostDailyMb: 0, hostDailyActiveMin: 0, hostMonthlyControlMb: 0, hostMonthlyMediaMb: 0.001 } } }); // ~1048 bytes
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const media = await openSession(w, engine, key, host, "media");
    const control = await openSession(w, engine, key, host, "control");
    await engine.onMessage(control.app, bin(5000));
    await engine.onMessage(media.app, bin(2000));
    expect(media.app.closedWith).toBeUndefined(); // nothing is enforced between write-backs
    w.t += 20;
    await engine.onAlarm();
    await w.settle();
    expect(media.app.closedWith).toEqual([4429, "quota_exhausted"]);
    expect(media.data.closedWith).toEqual([4429, "quota_exhausted"]);
    expect(control.app.closedWith).toBeUndefined();
    // The connector is told about the change.
    expect(host.texts.filter((t) => t.t === "usage").length).toBe(1);
  });

  test("a media session is cut with 4503 when the budget tightens; the reply to the report decides", async () => {
    const { w, engine } = setup({ policy: { budget: { globalDailyMb: 100, globalDailyActiveHours: 0, mediaReserve: 0.3 } } });
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const media = await openSession(w, engine, key, host, "media");
    const control = await openSession(w, engine, key, host, "control");
    await engine.onMessage(media.app, bin(10));
    w.budgetState = "media_paused";
    w.t += 20;
    await engine.onAlarm();
    await w.settle();
    expect(media.app.closedWith).toEqual([4503, "relay_busy"]);
    expect(control.app.closedWith).toBeUndefined();
    expect(host.texts.filter((t) => t.t === "usage").at(-1).usage.budget).toBe("media_paused");
    // New sessions: media refused, control allowed. `closed` stops control too.
    w.t += 11_000; // let the cached state expire so the engine asks again
    const m = await engine.start({ kind: "connect", hostId: key.hostId, ch: "media" }, w.requestCtx());
    expect(m.status).toBe(503);
    const c = await engine.start({ kind: "connect", hostId: key.hostId, ch: "control" }, w.requestCtx());
    expect(c.status).toBe(200);
    w.budgetState = "closed";
    w.t += 11_000;
    expect((await engine.start({ kind: "connect", hostId: key.hostId, ch: "control" }, w.requestCtx())).status).toBe(503);
  });

  test("the budget service is asked at session start only when the cached state is old, and not at all when unlimited", async () => {
    const limited = setup({ policy: { budget: { globalDailyMb: 100, globalDailyActiveHours: 0, mediaReserve: 0.3 } } });
    const key = await newHostKey();
    const host = await connectHost(limited.w, limited.engine, key);
    const before = limited.w.stateCalls;
    for (let i = 0; i < 3; i++) await limited.engine.start({ kind: "connect", hostId: key.hostId, ch: "control" }, limited.w.requestCtx());
    expect(limited.w.stateCalls).toBe(before); // hello already fetched it: within the cache window
    limited.w.t += 11_000;
    await limited.engine.start({ kind: "connect", hostId: key.hostId, ch: "control" }, limited.w.requestCtx());
    expect(limited.w.stateCalls).toBe(before + 1);
    expect(host.closedWith).toBeUndefined();

    const open = setup();
    await connectHost(open.w, open.engine, key);
    expect(open.w.stateCalls).toBe(0);
  });

  test("budget reports carry increments only, never one per message", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const s = await openSession(w, engine, key, host);
    for (let i = 0; i < 200; i++) await engine.onMessage(s.app, bin(100));
    expect(w.reports.length).toBe(0);
    w.t += 20;
    await engine.onAlarm();
    expect(w.reports).toEqual([{ bytes: 20_000, minutes: 1 }]);
  });

  test("an unreachable budget service does not break sessions; the increment is retried", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const s = await openSession(w, engine, key, host);
    const ok = w.budget.report;
    w.budget.report = async () => {
      throw new Error("down");
    };
    await engine.onMessage(s.app, bin(500));
    w.t += 20;
    await engine.onAlarm();
    expect(stored(w, key).dayControl).toBe(500); // local metering is unaffected
    w.budget.report = ok;
    w.t += 30_000;
    await engine.onMessage(s.app, bin(100));
    await engine.onAlarm();
    expect(w.reports.at(-1)).toEqual({ bytes: 600, minutes: 1 });
  });
});

describe("logging", () => {
  test("logs carry the hostId prefix and event names, never frame contents or addresses", async () => {
    const { w, engine } = setup();
    const key = await newHostKey();
    const host = await connectHost(w, engine, key);
    const s = await openSession(w, engine, key, host);
    await engine.onMessage(s.app, "SECRET-PAYLOAD");
    await engine.onMessage(s.app, new TextEncoder().encode("SECRET-BINARY"));
    const dump = JSON.stringify(w.logs);
    expect(dump).not.toContain("SECRET");
    expect(dump).not.toContain("203.0.113");
    expect(dump).not.toContain(key.hostId); // only the first 8 characters
    expect(w.logs.every((l) => typeof l.h === "string" && (l.h as string).length <= 8)).toBe(true);
    expect(w.logs.some((l) => l.ev === "host_online" && l.h === key.hostId.slice(0, 8))).toBe(true);
  });
});

void ({} as SessAtt);

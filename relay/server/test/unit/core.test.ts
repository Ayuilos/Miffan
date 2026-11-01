import { Database } from "bun:sqlite";
import { describe, expect, test } from "bun:test";
import { GlobalBudget, IpRegistrations, budgetState } from "../../core/budget.ts";
import { ConfigError, DEFAULT_CONFIG, infoDocument, parseConfig, publicPolicy } from "../../core/config.ts";
import { b64uDecode, b64uEncode, base32Lower, hostIdFromPub, secretEquals, verifyHostSignature } from "../../core/crypto.ts";
import { routeRequest } from "../../core/http.ts";
import { HostLedger, buildUsage, counted, quotaStatus } from "../../core/ledger.ts";
import { checkNewSession, mediaSessionVerdict } from "../../core/policy.ts";
import { RateWindow, allowanceBytes } from "../../core/ratelimit.ts";
import { MIB, nextUtcMidnight, nextUtcMonth, utcDay } from "../../core/time.ts";
import { sqlite } from "../../bun/relay.ts";
import { newHostKey } from "../client/keys.ts";

const T = Date.UTC(2026, 10, 15, 12, 30, 0); // a Sunday, mid-month

describe("config", () => {
  test("empty input yields the documented defaults", () => {
    expect(parseConfig("")).toEqual(DEFAULT_CONFIG);
    expect(parseConfig(undefined)).toEqual(DEFAULT_CONFIG);
    expect(parseConfig("{}")).toEqual(DEFAULT_CONFIG);
    expect(DEFAULT_CONFIG.policy.limits).toEqual({ sessionsPerHost: 4, idleTimeoutS: 300 });
    expect(DEFAULT_CONFIG.policy.quotas.hostDailyMb).toBe(500);
    expect(DEFAULT_CONFIG.policy.budget.mediaReserve).toBe(0.3);
    expect(DEFAULT_CONFIG.registrationsPerIpPerDay).toBe(5);
  });

  test("partial sections are merged over the defaults", () => {
    const c = parseConfig({ policy: { media: { allowed: false }, quotas: { hostDailyMb: 1.5 } }, edges: ["1.2.3.4"] });
    expect(c.policy.media).toEqual({ allowed: false, kbps: 4000, burstKb: 4096 });
    expect(c.policy.quotas.hostDailyMb).toBe(1.5);
    expect(c.policy.quotas.hostMonthlyControlMb).toBe(2048);
    expect(c.edges).toEqual(["1.2.3.4"]);
  });

  test("every problem is reported", () => {
    let msg = "";
    try {
      parseConfig({ accessMode: "closed", policy: { control: { kbps: -1 }, media: { allowed: "yes" }, budget: { mediaReserve: 2 } }, edges: "x" });
    } catch (e) {
      expect(e).toBeInstanceOf(ConfigError);
      msg = (e as Error).message;
    }
    for (const part of ["accessMode", "control.kbps", "media.allowed", "mediaReserve", "edges"]) expect(msg).toContain(part);
    expect(() => parseConfig("{not json")).toThrow(ConfigError);
    expect(() => parseConfig("[]")).toThrow(ConfigError);
  });

  test("info and public policy have the protocol shape", () => {
    const info = infoDocument(parseConfig({ accessMode: "token" }), "bun");
    expect(info).toMatchObject({ v: 1, impl: "bun", access: "token", capabilities: { dgram: "none" }, edges: [] });
    const p = publicPolicy(DEFAULT_CONFIG);
    expect(Object.keys(p).sort()).toEqual(["access", "budget", "control", "limits", "media", "quotas"]);
    expect(p.access).toEqual({ mode: "open", registrationsPerIpPerDay: 5 });
  });
});

describe("crypto", () => {
  test("base64url round trip and strictness", () => {
    for (const n of [0, 1, 2, 3, 31, 32, 33]) {
      const b = crypto.getRandomValues(new Uint8Array(n));
      expect(Array.from(b64uDecode(b64uEncode(b))!)).toEqual(Array.from(b));
    }
    expect(b64uEncode(new Uint8Array([251, 255]))).toBe("-_8");
    expect(b64uDecode("a+b")).toBeNull(); // standard alphabet is not accepted
    expect(b64uDecode("abc=")).toBeNull(); // padding is not accepted
    expect(b64uDecode("a")).toBeNull();
  });

  test("base32 matches RFC 4648 vectors (lowercase, unpadded)", () => {
    const enc = (s: string) => base32Lower(new TextEncoder().encode(s));
    expect(enc("")).toBe("");
    expect(enc("f")).toBe("my");
    expect(enc("fo")).toBe("mzxq");
    expect(enc("foo")).toBe("mzxw6");
    expect(enc("foob")).toBe("mzxw6yq");
    expect(enc("fooba")).toBe("mzxw6ytb");
    expect(enc("foobar")).toBe("mzxw6ytboi");
  });

  test("hostId is 26 characters of base32(SHA-256(pub))", async () => {
    const k = await newHostKey();
    expect(k.hostId).toMatch(/^[a-z2-7]{26}$/);
    expect(await hostIdFromPub(k.pub)).toBe(k.hostId);
    const sha = new Uint8Array(await crypto.subtle.digest("SHA-256", k.pub));
    expect(k.hostId).toBe(base32Lower(sha).slice(0, 26));
  });

  test("host signatures verify only for the exact host, nonce and key", async () => {
    const k = await newHostKey();
    const other = await newHostKey();
    const sig = b64uDecode(await k.signChallenge("relay.example", "NONCE"))!;
    expect(await verifyHostSignature(k.pub, "relay.example", "NONCE", sig)).toBe(true);
    expect(await verifyHostSignature(k.pub, "RELAY.example", "NONCE", sig)).toBe(true); // host is lower-cased
    expect(await verifyHostSignature(k.pub, "relay.example", "NONCE2", sig)).toBe(false);
    expect(await verifyHostSignature(k.pub, "evil.example", "NONCE", sig)).toBe(false);
    expect(await verifyHostSignature(other.pub, "relay.example", "NONCE", sig)).toBe(false);
    expect(await verifyHostSignature(k.pub, "relay.example", "NONCE", sig.slice(1))).toBe(false);
    expect(await verifyHostSignature(new Uint8Array(5) as never, "relay.example", "NONCE", sig)).toBe(false);
  });

  test("secretEquals", async () => {
    expect(await secretEquals("abc", "abc")).toBe(true);
    expect(await secretEquals("abc", "abd")).toBe(false);
    expect(await secretEquals("abc", "abcd")).toBe(false);
    expect(await secretEquals(null, "abc")).toBe(false);
    expect(await secretEquals("", "")).toBe(false); // an unset secret never matches
    expect(await secretEquals("abc", undefined)).toBe(false);
  });
});

describe("routing", () => {
  const open = DEFAULT_CONFIG;
  const tokenCfg = parseConfig({ accessMode: "token" });
  const ws = { upgrade: "websocket" };
  const req = (path: string, headers: Record<string, string> = {}, method = "GET") => new Request("http://r.test" + path, { method, headers });
  const status = async (r: Promise<unknown>) => {
    const x = await r;
    return x instanceof Response ? [x.status, ((await x.json()) as any).reason] : x;
  };

  test("info needs no credentials", async () => {
    expect(await routeRequest(req("/v1/info"), tokenCfg, {})).toEqual({ kind: "info" });
  });

  test("shape errors are bad_request", async () => {
    expect(await status(routeRequest(req("/nope"), open, {}))).toEqual([404, "bad_request"]);
    expect(await status(routeRequest(req("/v1/info", {}, "POST"), open, {}))).toEqual([405, "bad_request"]);
    expect(await status(routeRequest(req("/v1/host?pub=zz", ws), open, {}))).toEqual([400, "bad_request"]);
    expect(await status(routeRequest(req("/v1/host?pub=" + b64uEncode(new Uint8Array(32))), open, {}))).toEqual([400, "bad_request"]); // no upgrade
    expect(await status(routeRequest(req("/v1/connect/" + "a".repeat(26) + "?ch=x", ws), open, {}))).toEqual([400, "bad_request"]);
    expect(await status(routeRequest(req("/v1/connect/" + "a".repeat(26), ws), open, {}))).toEqual([400, "bad_request"]);
    expect(await status(routeRequest(req("/v1/host/" + "a".repeat(26) + "/session/nothex", ws), open, {}))).toEqual([400, "bad_request"]);
  });

  test("the access code is checked first, and a missing server secret refuses everything", async () => {
    const pub = b64uEncode(new Uint8Array(32).fill(7));
    expect(await status(routeRequest(req("/v1/host?pub=" + pub, ws), tokenCfg, { accessToken: "s" }))).toEqual([401, "unauthorized"]);
    expect(await status(routeRequest(req("/v1/host?pub=" + pub, { ...ws, authorization: "Bearer x" }), tokenCfg, { accessToken: "s" }))).toEqual([401, "unauthorized"]);
    expect(await status(routeRequest(req("/v1/host?pub=" + pub, { ...ws, authorization: "Bearer s" }), tokenCfg, { accessToken: "s" }))).toMatchObject({ kind: "host" });
    // The access code is not needed for the data connection or when the relay is open.
    expect(await status(routeRequest(req(`/v1/host/${"a".repeat(26)}/session/${"0".repeat(32)}`, ws), tokenCfg, { accessToken: "s" }))).toMatchObject({ kind: "data" });
    expect(await status(routeRequest(req("/v1/host?pub=" + pub, { ...ws, authorization: "Bearer s" }), tokenCfg, {}))).toEqual([401, "unauthorized"]);
    // 401 wins over a bad channel
    expect(await status(routeRequest(req("/v1/connect/" + "a".repeat(26) + "?ch=x", ws), tokenCfg, { accessToken: "s" }))).toEqual([401, "unauthorized"]);
  });

  test("admin stats need the admin token", async () => {
    expect(await status(routeRequest(req("/v1/admin/stats"), open, {}))).toEqual([401, "unauthorized"]);
    expect(await status(routeRequest(req("/v1/admin/stats", { authorization: "Bearer a" }), open, { adminToken: "a" }))).toEqual({ kind: "admin" });
    expect(await status(routeRequest(req("/v1/admin/stats", { authorization: "Bearer b" }), open, { adminToken: "a" }))).toEqual([401, "unauthorized"]);
  });

  test("hostId is derived from pub and hints are filtered", async () => {
    const k = await newHostKey();
    const r = (await routeRequest(req(`/v1/host?pub=${k.pubB64u}&hint=wnam`, ws), open, {})) as any;
    expect(r.hostId).toBe(k.hostId);
    expect(r.hint).toBe("wnam");
    expect(((await routeRequest(req(`/v1/host?pub=${k.pubB64u}&hint=moon`, ws), open, {})) as any).hint).toBeUndefined();
  });
});

describe("rate window", () => {
  test("allowance is 1.5 x (kbps*10/8*1000 + burst)", () => {
    expect(allowanceBytes({ kbps: 1000, burstKb: 2048 })).toBe(Math.round(1.5 * (1_250_000 + 2_097_152)));
    expect(allowanceBytes({ kbps: 100, burstKb: 32 })).toBe(236_652);
  });

  test("a sliding ten second window", () => {
    const w = new RateWindow();
    const t0 = 1_000_000_000;
    w.add(t0, 100);
    w.add(t0 + 4000, 200);
    expect(w.total(t0 + 4000)).toBe(300);
    expect(w.total(t0 + 9999)).toBe(300);
    expect(w.total(t0 + 10_000)).toBe(200); // the first second has left the window
    expect(w.total(t0 + 14_000)).toBe(0);
    w.add(t0 + 14_000, 5);
    expect(w.total(t0 + 14_000)).toBe(5);
    expect(w.wouldExceed(t0 + 14_000, 995, 1000)).toBe(false);
    expect(w.wouldExceed(t0 + 14_000, 996, 1000)).toBe(true);
    expect(w.wouldExceed(t0 + 14_000, 1e9, 0)).toBe(false); // 0 = unlimited
  });
});

describe("ledger", () => {
  const fresh = () => sqlite(new Database(":memory:"));

  test("a host that never registered leaves no tables behind", () => {
    const sql = fresh();
    const l = new HostLedger(sql, "h1");
    expect(l.registered()).toBe(false);
    expect(l.totals(T).dayControl).toBe(0);
    expect(sql.all("SELECT name FROM sqlite_master")).toEqual([]);
    l.markRegistered();
    expect(l.registered()).toBe(true);
    expect(new HostLedger(sql, "h2").registered()).toBe(false);
  });

  test("commit accumulates per day and channel; month sums days", () => {
    const sql = fresh();
    const l = new HostLedger(sql, "h1");
    l.ensure();
    const day2 = T - 86_400_000;
    l.commit(day2, utcDay(day2), { control: 1000, media: 0, minutes: 3 }, 10);
    l.commit(T, utcDay(T), { control: 500, media: 2000, minutes: 2 }, 20);
    l.commit(T + 1000, utcDay(T), { control: 500, media: 0, minutes: 1 }, 21);
    const t = new HostLedger(sql, "h1").totals(T); // a new instance reads it back from storage
    expect(t).toMatchObject({ day: "2026-11-15", month: "2026-11", dayControl: 1000, dayMedia: 2000, monthControl: 2000, monthMedia: 2000, dayActiveMin: 3 });
    expect(new HostLedger(sql, "h1").meta()).toEqual({ lastMinute: 21, lastFlush: T + 1000 });
    expect(new HostLedger(sql, "other").totals(T).monthControl).toBe(0);
  });

  test("rows from earlier months are dropped on commit", () => {
    const sql = fresh();
    const l = new HostLedger(sql, "h1");
    l.ensure();
    const lastMonth = Date.UTC(2026, 9, 31, 23, 0, 0);
    l.commit(lastMonth, utcDay(lastMonth), { control: 999, media: 0, minutes: 1 }, 1);
    l.commit(T, utcDay(T), { control: 1, media: 0, minutes: 1 }, 2);
    expect(sql.all("SELECT day FROM usage_day").map((r: any) => r.day)).toEqual(["2026-11-15"]);
    expect(l.totals(T).monthControl).toBe(1);
  });

  test("usage object: rounding, pending counts, resetsAt", () => {
    const policy = DEFAULT_CONFIG.policy;
    const totals = { day: "2026-11-15", month: "2026-11", dayControl: 3 * MIB, dayMedia: 0, monthControl: 10 * MIB, monthMedia: 5 * MIB, dayActiveMin: 4 };
    const u = buildUsage(T, totals, { control: MIB / 2, media: 0, minutes: 1 }, "ok", policy);
    expect(u).toEqual({
      day: "2026-11-15",
      month: "2026-11",
      dayMb: 3.5,
      dayActiveMin: 5,
      monthControlMb: 10.5,
      monthMediaMb: 5,
      budget: "ok",
      resetsAt: "2026-11-16T00:00:00Z",
    });
  });

  test("quota status: daily covers both channels, monthly its own channel, reset times differ", () => {
    const p = parseConfig({ policy: { quotas: { hostDailyMb: 10, hostDailyActiveMin: 0, hostMonthlyControlMb: 100, hostMonthlyMediaMb: 200 } } }).policy;
    const base = { dayBytes: 0, dayActiveMin: 0, monthControlBytes: 0, monthMediaBytes: 0 };
    expect(quotaStatus(p, base, "control", T).exhausted).toBe(false);
    const day = quotaStatus(p, { ...base, dayBytes: 10 * MIB }, "media", T);
    expect(day).toMatchObject({ exhausted: true, resetAt: nextUtcMidnight(T) });
    const monthControl = { ...base, monthControlBytes: 100 * MIB };
    expect(quotaStatus(p, monthControl, "control", T)).toMatchObject({ exhausted: true, resetAt: nextUtcMonth(T) });
    expect(quotaStatus(p, monthControl, "media", T).exhausted).toBe(false);
    // Daily and monthly both exhausted: the later reset wins.
    expect(quotaStatus(p, { ...monthControl, dayBytes: 10 * MIB }, "control", T).resetAt).toBe(nextUtcMonth(T));
    // Active minutes
    const am = parseConfig({ policy: { quotas: { hostDailyMb: 0, hostDailyActiveMin: 30, hostMonthlyControlMb: 0, hostMonthlyMediaMb: 0 } } }).policy;
    expect(quotaStatus(am, { ...base, dayActiveMin: 29 }, "control", T).exhausted).toBe(false);
    expect(quotaStatus(am, { ...base, dayActiveMin: 30 }, "control", T).exhausted).toBe(true);
    // 0 = unlimited
    const none = parseConfig({ policy: { quotas: { hostDailyMb: 0, hostDailyActiveMin: 0, hostMonthlyControlMb: 0, hostMonthlyMediaMb: 0 } } }).policy;
    expect(quotaStatus(none, { dayBytes: 1e15, dayActiveMin: 1e9, monthControlBytes: 1e15, monthMediaBytes: 1e15 }, null, T).exhausted).toBe(false);
  });
});

describe("policy checks", () => {
  const policy = parseConfig({ policy: { limits: { sessionsPerHost: 2, idleTimeoutS: 300 } } }).policy;
  const zero = { dayBytes: 0, dayActiveMin: 0, monthControlBytes: 0, monthMediaBytes: 0 };
  const base = { ch: "control" as const, hostOnline: true, policy, budget: "ok" as const, counted: zero, sessions: 0, now: T };

  test("an unobstructed request passes", () => {
    expect(checkNewSession(base)).toBeNull();
  });

  test("checks apply in the order of PROTOCOL 6.4", () => {
    const noMedia = parseConfig({ policy: { media: { allowed: false }, limits: { sessionsPerHost: 1, idleTimeoutS: 300 }, quotas: { hostDailyMb: 1 } } }).policy;
    const everything = { ...base, ch: "media" as const, policy: noMedia, budget: "closed" as const, counted: { ...zero, dayBytes: 5 * MIB }, sessions: 1 };
    expect(checkNewSession({ ...everything, hostOnline: false })).toMatchObject({ status: 404, reason: "host_offline" });
    expect(checkNewSession(everything)).toMatchObject({ status: 403, reason: "media_disabled" });
    const allowed = { ...everything, policy: { ...noMedia, media: { ...noMedia.media, allowed: true } } };
    expect(checkNewSession(allowed)).toMatchObject({ status: 503, reason: "relay_busy" });
    expect(checkNewSession({ ...allowed, budget: "media_paused" })).toMatchObject({ status: 503, reason: "relay_busy" });
    expect(checkNewSession({ ...allowed, budget: "ok" })).toMatchObject({ status: 429, reason: "quota_exhausted" });
    expect(checkNewSession({ ...allowed, budget: "ok", counted: zero })).toMatchObject({ status: 429, reason: "too_many_sessions" });
  });

  test("media_paused only affects media; closed affects both", () => {
    expect(checkNewSession({ ...base, budget: "media_paused" })).toBeNull();
    expect(checkNewSession({ ...base, ch: "media", budget: "media_paused" })).toMatchObject({ reason: "relay_busy" });
    expect(checkNewSession({ ...base, budget: "closed" })).toMatchObject({ reason: "relay_busy" });
  });

  test("retryAfter points at the reset", () => {
    const q = parseConfig({ policy: { quotas: { hostDailyMb: 1 } } }).policy;
    const r = checkNewSession({ ...base, policy: q, counted: { ...zero, dayBytes: MIB } })!;
    expect(r.retryAfter).toBe((nextUtcMidnight(T) - T) / 1000);
  });

  test("running media sessions are cut by budget or media quota, never by a control-only quota", () => {
    const p = parseConfig({ policy: { quotas: { hostDailyMb: 0, hostDailyActiveMin: 0, hostMonthlyControlMb: 1, hostMonthlyMediaMb: 1 } } }).policy;
    expect(mediaSessionVerdict(p, "ok", zero, T)).toBeNull();
    expect(mediaSessionVerdict(p, "media_paused", zero, T)).toEqual({ code: "relay_busy" });
    expect(mediaSessionVerdict(p, "closed", zero, T)).toEqual({ code: "relay_busy" });
    expect(mediaSessionVerdict(p, "ok", { ...zero, monthControlBytes: 9 * MIB }, T)).toBeNull();
    expect(mediaSessionVerdict(p, "ok", { ...zero, monthMediaBytes: MIB }, T)).toEqual({ code: "quota_exhausted" });
  });

  test("counted() adds pending to stored totals", () => {
    const c = counted(
      { day: "d", month: "m", dayControl: 1, dayMedia: 2, monthControl: 3, monthMedia: 4, dayActiveMin: 5 },
      { control: 10, media: 20, minutes: 1 },
    );
    expect(c).toEqual({ dayBytes: 33, dayActiveMin: 6, monthControlBytes: 13, monthMediaBytes: 24 });
  });
});

describe("global budget", () => {
  test("state thresholds", () => {
    const b = { globalDailyMb: 10, globalDailyActiveHours: 0, mediaReserve: 0.3 };
    expect(budgetState(b, 0, 0)).toBe("ok");
    expect(budgetState(b, 6.9 * MIB, 0)).toBe("ok"); // 31% left
    expect(budgetState(b, 7.1 * MIB, 0)).toBe("media_paused"); // 29% left
    expect(budgetState(b, 10 * MIB, 0)).toBe("closed");
    expect(budgetState({ ...b, globalDailyMb: 0 }, 1e12, 0)).toBe("ok"); // unlimited
    const hours = { globalDailyMb: 0, globalDailyActiveHours: 1, mediaReserve: 0.5 };
    expect(budgetState(hours, 0, 29)).toBe("ok");
    expect(budgetState(hours, 0, 31)).toBe("media_paused");
    expect(budgetState(hours, 0, 60)).toBe("closed");
    // The tighter of the two budgets decides.
    expect(budgetState({ globalDailyMb: 100, globalDailyActiveHours: 1, mediaReserve: 0.1 }, 0, 60)).toBe("closed");
    expect(budgetState({ ...b, mediaReserve: 0 }, 9.9 * MIB, 0)).toBe("ok");
  });

  test("totals persist for the day and reset on the next", () => {
    const sql = sqlite(new Database(":memory:"));
    const b = { globalDailyMb: 1, globalDailyActiveHours: 0, mediaReserve: 0.5 };
    const g = new GlobalBudget(sql, b);
    expect(g.report(T, 0.6 * MIB, 3)).toBe("media_paused");
    expect(new GlobalBudget(sql, b).state(T)).toBe("media_paused"); // survives a restart
    expect(g.report(T, 0.5 * MIB, 0)).toBe("closed");
    expect(g.stats(T, 7)).toMatchObject({ day: "2026-11-15", budget: "closed", usedMb: 1.1, registrationsToday: 7 });
    expect(g.state(T + 86_400_000)).toBe("ok");
    expect(sql.all("SELECT day FROM global_day").length).toBe(1);
  });
});

describe("ip registrations", () => {
  test("limit per IP and day; 0 = unlimited; only hashes are stored", async () => {
    const sql = sqlite(new Database(":memory:"));
    const r = new IpRegistrations(sql);
    for (let i = 0; i < 3; i++) expect(await r.register(T, "198.51.100.7", 3)).toEqual({ ok: true });
    const refused = await r.register(T, "198.51.100.7", 3);
    expect(refused.ok).toBe(false);
    expect((refused as any).retryAfter).toBe((nextUtcMidnight(T) - T) / 1000);
    expect(await r.register(T, "198.51.100.8", 3)).toEqual({ ok: true }); // another address
    expect(r.total(T)).toBe(4);
    expect(await r.register(T, "198.51.100.7", 0)).toEqual({ ok: true });

    const dump = JSON.stringify(sql.all("SELECT * FROM reg_ip")) + JSON.stringify(sql.all("SELECT * FROM reg_salt"));
    expect(dump).not.toContain("198.51.100");

    // A midnight purge removes everything even if nobody registers the next day.
    r.purge(T + 86_400_000);
    expect(sql.all("SELECT * FROM reg_ip")).toEqual([]);
    expect(sql.all("SELECT * FROM reg_salt")).toEqual([]);

    // Next day: counts and the salt are replaced, older rows are gone.
    const tomorrow = T + 86_400_000;
    expect(await r.register(tomorrow, "198.51.100.7", 3)).toEqual({ ok: true });
    expect(sql.all("SELECT DISTINCT day FROM reg_ip").map((x: any) => x.day)).toEqual([utcDay(tomorrow)]);
    expect(sql.all("SELECT day FROM reg_salt").length).toBe(1);
  });
});

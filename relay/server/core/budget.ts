// Global daily budget and per-IP registration counting (PROTOCOL §6.3, §6.4; RELAY_PLAN §5.1).

import type { Policy } from "./config.ts";
import { sha256, b64uEncode, randomBytes } from "./crypto.ts";
import type { Sql } from "./sql.ts";
import { MIB, nextUtcMidnight, secondsUntil, utcDay } from "./time.ts";

export type BudgetState = "ok" | "media_paused" | "closed";

/**
 * `closed` when any configured daily budget is used up; `media_paused` when the remaining share of
 * any configured budget has fallen below `mediaReserve`; otherwise `ok`. Budgets of 0 are unlimited.
 */
export function budgetState(b: Policy["budget"], bytes: number, activeMinutes: number): BudgetState {
  const shares: number[] = [];
  if (b.globalDailyMb > 0) shares.push(1 - bytes / (b.globalDailyMb * MIB));
  if (b.globalDailyActiveHours > 0) shares.push(1 - activeMinutes / (b.globalDailyActiveHours * 60));
  if (shares.length === 0) return "ok";
  const remaining = Math.min(...shares);
  if (remaining <= 0) return "closed";
  if (remaining < b.mediaReserve) return "media_paused";
  return "ok";
}

export interface GlobalStats {
  day: string;
  budget: BudgetState;
  usedMb: number;
  activeHours: number;
  limits: { globalDailyMb: number; globalDailyActiveHours: number; mediaReserve: number };
  registrationsToday: number;
}

export class GlobalBudget {
  private ready = false;
  private cache?: { day: string; bytes: number; minutes: number };

  constructor(
    private sql: Sql,
    private budget: Policy["budget"],
  ) {}

  private ensure(): void {
    if (this.ready) return;
    this.sql.run("CREATE TABLE IF NOT EXISTS global_day (day TEXT PRIMARY KEY, bytes INTEGER NOT NULL, minutes INTEGER NOT NULL)");
    this.ready = true;
  }

  private load(now: number): { day: string; bytes: number; minutes: number } {
    const day = utcDay(now);
    if (this.cache?.day === day) return this.cache;
    this.ensure();
    const row = this.sql.all<{ bytes: number; minutes: number }>("SELECT bytes, minutes FROM global_day WHERE day = ?", day)[0];
    this.cache = { day, bytes: row?.bytes ?? 0, minutes: row?.minutes ?? 0 };
    return this.cache;
  }

  state(now: number): BudgetState {
    const t = this.load(now);
    return budgetState(this.budget, t.bytes, t.minutes);
  }

  /** Add one host's increment and return the resulting state. */
  report(now: number, bytes: number, minutes: number): BudgetState {
    const t = this.load(now);
    if (bytes > 0 || minutes > 0) {
      this.sql.run(
        `INSERT INTO global_day (day, bytes, minutes) VALUES (?, ?, ?)
         ON CONFLICT (day) DO UPDATE SET bytes = bytes + excluded.bytes, minutes = minutes + excluded.minutes`,
        t.day,
        Math.round(bytes),
        Math.round(minutes),
      );
      t.bytes += Math.round(bytes);
      t.minutes += Math.round(minutes);
      this.sql.run("DELETE FROM global_day WHERE day < ?", t.day);
    }
    return budgetState(this.budget, t.bytes, t.minutes);
  }

  stats(now: number, registrationsToday: number): GlobalStats {
    const t = this.load(now);
    return {
      day: t.day,
      budget: budgetState(this.budget, t.bytes, t.minutes),
      usedMb: Math.round((t.bytes / MIB) * 1000) / 1000,
      activeHours: Math.round((t.minutes / 60) * 100) / 100,
      limits: { ...this.budget },
      registrationsToday,
    };
  }
}

/**
 * The unit the registration limit counts: an IPv4 address, or the /64 prefix of an IPv6 address (one
 * subscriber usually holds a whole /64, so counting single IPv6 addresses would cost nothing to evade).
 * IPv4-mapped IPv6 addresses count as their IPv4 address. Anything unparsable is counted as given.
 */
export function ipBucket(ip: string): string {
  const s = ip.trim().toLowerCase().replace(/^\[|\]$/g, "").replace(/%.*$/, "");
  if (!s.includes(":")) return s;
  const mapped = /^(?:::|(?:0{1,4}:){5})ffff:(\d{1,3}(?:\.\d{1,3}){3})$/.exec(s);
  if (mapped) return mapped[1]!;
  let head = s;
  let tail = "";
  const dbl = s.indexOf("::");
  if (dbl >= 0) {
    head = s.slice(0, dbl);
    tail = s.slice(dbl + 2);
  }
  const h = head ? head.split(":") : [];
  const t = tail ? tail.split(":") : [];
  if (t.length && t[t.length - 1]!.includes(".")) return s; // embedded IPv4 in a non-mapped address: rare, count as given
  const missing = 8 - h.length - t.length;
  if (missing < 0 || (dbl < 0 && missing !== 0)) return s;
  const groups = [...h, ...Array<string>(missing).fill("0"), ...t];
  if (groups.some((g) => !/^[0-9a-f]{1,4}$/.test(g))) return s;
  return groups.slice(0, 4).map((g) => g.replace(/^0+(?=.)/, "")).join(":") + "::/64";
}

/**
 * Per-source-IP counter of newly registered hosts. Only a salted hash of the address is stored, the
 * salt is replaced every UTC day, and every earlier day is deleted, so nothing identifying survives
 * the day (PROTOCOL §6.3).
 */
export class IpRegistrations {
  private ready = false;
  private salt?: { day: string; value: Uint8Array };

  constructor(private sql: Sql) {}

  private ensure(): void {
    if (this.ready) return;
    this.sql.run("CREATE TABLE IF NOT EXISTS reg_salt (day TEXT PRIMARY KEY, salt TEXT NOT NULL)");
    this.sql.run("CREATE TABLE IF NOT EXISTS reg_ip (day TEXT NOT NULL, ip TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (day, ip))");
    this.ready = true;
  }

  private daySalt(day: string): Uint8Array {
    if (this.salt?.day === day) return this.salt.value;
    this.ensure();
    this.sql.run("DELETE FROM reg_salt WHERE day < ?", day);
    this.sql.run("DELETE FROM reg_ip WHERE day < ?", day);
    const row = this.sql.all<{ salt: string }>("SELECT salt FROM reg_salt WHERE day = ?", day)[0];
    let value: Uint8Array;
    if (row) {
      value = Uint8Array.from(atob(row.salt), (c) => c.charCodeAt(0));
    } else {
      value = randomBytes(16);
      this.sql.run("INSERT INTO reg_salt (day, salt) VALUES (?, ?)", day, btoa(String.fromCharCode(...value)));
    }
    this.salt = { day, value };
    return value;
  }

  private async keyOf(day: string, ip: string): Promise<string> {
    const salt = this.daySalt(day);
    return b64uEncode((await sha256(new Uint8Array([...salt, ...new TextEncoder().encode(ipBucket(ip))]))).slice(0, 12));
  }

  private count(day: string, key: string): number {
    return this.sql.all<{ n: number }>("SELECT n FROM reg_ip WHERE day = ? AND ip = ?", day, key)[0]?.n ?? 0;
  }

  /** Would a registration from `ip` be refused? Reads only; nothing is counted. */
  async check(now: number, ip: string, limit: number): Promise<{ ok: true } | { ok: false; retryAfter: number }> {
    if (limit <= 0) return { ok: true };
    const day = utcDay(now);
    if (this.count(day, await this.keyOf(day, ip)) >= limit) return { ok: false, retryAfter: secondsUntil(nextUtcMidnight(now), now) };
    return { ok: true };
  }

  /** Count one registration from `ip`. `limit` 0 means unlimited. */
  async register(now: number, ip: string, limit: number): Promise<{ ok: true } | { ok: false; retryAfter: number }> {
    if (limit <= 0) return { ok: true };
    const day = utcDay(now);
    const key = await this.keyOf(day, ip);
    if (this.count(day, key) >= limit) return { ok: false, retryAfter: secondsUntil(nextUtcMidnight(now), now) };
    this.sql.run(
      "INSERT INTO reg_ip (day, ip, n) VALUES (?, ?, 1) ON CONFLICT (day, ip) DO UPDATE SET n = n + 1",
      day,
      key,
    );
    return { ok: true };
  }

  /** Delete every earlier day's counters and salt (called from a midnight alarm, and on every use). */
  purge(now: number): void {
    this.ensure();
    const day = utcDay(now);
    this.sql.run("DELETE FROM reg_salt WHERE day < ?", day);
    this.sql.run("DELETE FROM reg_ip WHERE day < ?", day);
  }

  total(now: number): number {
    this.ensure();
    return this.sql.all<{ t: number | null }>("SELECT SUM(n) AS t FROM reg_ip WHERE day = ?", utcDay(now))[0]?.t ?? 0;
  }
}

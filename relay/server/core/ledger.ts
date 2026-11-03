// Per-host metering (PROTOCOL §6.3): bytes per UTC day and channel, active minutes per UTC day,
// and the usage object derived from them.

import type { BudgetState } from "./budget.ts";
import type { Channel } from "./codes.ts";
import type { Policy } from "./config.ts";
import type { Sql } from "./sql.ts";
import { MIB, isoNoMillis, nextUtcMidnight, nextUtcMonth, utcDay, utcMonth } from "./time.ts";

export interface Totals {
  day: string;
  month: string;
  dayControl: number;
  dayMedia: number;
  monthControl: number;
  monthMedia: number;
  dayActiveMin: number;
  /** Messages the host's sessions received this UTC day (PROTOCOL §6.3). */
  dayMsgs: number;
}

/** Counts not yet written to storage. */
export interface Pending {
  control: number;
  media: number;
  minutes: number;
  /** Messages received on the host's sessions (other than `ping`): the daily message quota, and the global budget. */
  msgs: number;
  /** Other billed requests (accepted WebSocket upgrades); only reported to the global budget. */
  reqs: number;
}

export interface Usage {
  day: string;
  month: string;
  dayMb: number;
  dayActiveMin: number;
  monthControlMb: number;
  monthMediaMb: number;
  /** Messages the host's sessions sent today (PROTOCOL §6.3); counted against quotas.hostDailyMessages. */
  dayMessages: number;
  budget: BudgetState;
  resetsAt: string;
}

export const EMPTY_PENDING: Readonly<Pending> = Object.freeze({ control: 0, media: 0, minutes: 0, msgs: 0, reqs: 0 });

export function pendingIsEmpty(p: Pending): boolean {
  return p.control === 0 && p.media === 0 && p.minutes === 0 && p.msgs === 0 && p.reqs === 0;
}

/**
 * Durable Object requests a count stands for: the platform bills 20 received WebSocket messages as
 * one request, and every other request (an upgrade, an alarm, an RPC) as one.
 */
export function requestUnits(msgs: number, reqs: number): number {
  return msgs / 20 + reqs;
}

const round3 = (n: number) => Math.round(n * 1000) / 1000;

/** The numbers quota checks compare against, in bytes and minutes. */
export interface Counted {
  dayBytes: number;
  dayActiveMin: number;
  monthControlBytes: number;
  monthMediaBytes: number;
  dayMsgs: number;
}

export function counted(t: Totals, p: Pending): Counted {
  return {
    dayBytes: t.dayControl + t.dayMedia + p.control + p.media,
    dayActiveMin: t.dayActiveMin + p.minutes,
    monthControlBytes: t.monthControl + p.control,
    monthMediaBytes: t.monthMedia + p.media,
    dayMsgs: t.dayMsgs + p.msgs,
  };
}

/** Has the host used up its daily message quota? Unlike the other quotas it also ends running sessions. */
export function messagesExhausted(policy: Policy, c: Counted): boolean {
  const limit = policy.quotas.hostDailyMessages;
  return limit > 0 && c.dayMsgs >= limit;
}

/**
 * Whether the host's quotas block a session on `ch` (or, with `null`, any quota at all), and when
 * everything that blocks it has reset. Daily quotas cover both channels; each monthly quota covers
 * only its own channel.
 */
export function quotaStatus(
  policy: Policy,
  c: Counted,
  ch: Channel | null,
  now: number,
): { exhausted: boolean; resetAt: number; fraction: number } {
  const q = policy.quotas;
  const midnight = nextUtcMidnight(now);
  const month = nextUtcMonth(now);
  const checks: { used: number; limit: number; reset: number; applies: boolean }[] = [
    { used: c.dayBytes, limit: q.hostDailyMb * MIB, reset: midnight, applies: true },
    { used: c.dayActiveMin, limit: q.hostDailyActiveMin, reset: midnight, applies: true },
    { used: c.dayMsgs, limit: q.hostDailyMessages, reset: midnight, applies: true },
    { used: c.monthControlBytes, limit: q.hostMonthlyControlMb * MIB, reset: month, applies: ch === null || ch === "control" },
    { used: c.monthMediaBytes, limit: q.hostMonthlyMediaMb * MIB, reset: month, applies: ch === null || ch === "media" },
  ];
  let resetAt = midnight;
  let exhausted = false;
  let fraction = 0;
  for (const k of checks) {
    if (!k.applies || k.limit <= 0) continue;
    fraction = Math.max(fraction, k.used / k.limit);
    if (k.used >= k.limit) {
      exhausted = true;
      resetAt = Math.max(resetAt, k.reset);
    }
  }
  return { exhausted, resetAt, fraction };
}

export function buildUsage(now: number, t: Totals, p: Pending, budget: BudgetState, policy: Policy): Usage {
  const c = counted(t, p);
  const q = quotaStatus(policy, c, null, now);
  return {
    day: t.day,
    month: t.month,
    dayMb: round3(c.dayBytes / MIB),
    dayActiveMin: c.dayActiveMin,
    monthControlMb: round3(c.monthControlBytes / MIB),
    monthMediaMb: round3(c.monthMediaBytes / MIB),
    dayMessages: c.dayMsgs,
    budget,
    resetsAt: isoNoMillis(q.exhausted ? q.resetAt : nextUtcMidnight(now)),
  };
}

export interface HostMeta {
  lastMinute: number;
  lastFlush: number;
}

export class HostLedger {
  private exists?: boolean;
  /** Whether msgs_day exists: added after the other tables, so hosts registered earlier lack it. */
  private msgsTable?: boolean;
  private totalsCache?: Totals;
  private metaCache?: HostMeta;

  constructor(
    private sql: Sql,
    private host: string,
  ) {}

  private tableExists(): boolean {
    if (this.exists !== undefined) return this.exists;
    this.exists =
      this.sql.all("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'host_meta'").length > 0;
    return this.exists;
  }

  /** Create the tables. Done lazily so that hosts which never register leave no storage behind. */
  ensure(): void {
    if (this.exists === true) return;
    this.sql.run(`CREATE TABLE IF NOT EXISTS host_meta (
      host TEXT PRIMARY KEY, registered INTEGER NOT NULL DEFAULT 0,
      last_minute INTEGER NOT NULL DEFAULT 0, last_flush INTEGER NOT NULL DEFAULT 0)`);
    this.sql.run(`CREATE TABLE IF NOT EXISTS usage_day (
      host TEXT NOT NULL, day TEXT NOT NULL, ch TEXT NOT NULL, bytes INTEGER NOT NULL,
      PRIMARY KEY (host, day, ch))`);
    this.sql.run(`CREATE TABLE IF NOT EXISTS active_day (
      host TEXT NOT NULL, day TEXT NOT NULL, minutes INTEGER NOT NULL, PRIMARY KEY (host, day))`);
    this.exists = true;
  }

  registered(): boolean {
    if (!this.tableExists()) return false;
    return this.sql.all("SELECT 1 FROM host_meta WHERE host = ? AND registered = 1", this.host).length > 0;
  }

  markRegistered(): void {
    this.ensure();
    this.sql.run(
      "INSERT INTO host_meta (host, registered) VALUES (?, 1) ON CONFLICT (host) DO UPDATE SET registered = 1",
      this.host,
    );
  }

  meta(): HostMeta {
    if (this.metaCache) return this.metaCache;
    let m: HostMeta = { lastMinute: 0, lastFlush: 0 };
    if (this.tableExists()) {
      const row = this.sql.all<{ last_minute: number; last_flush: number }>(
        "SELECT last_minute, last_flush FROM host_meta WHERE host = ?",
        this.host,
      )[0];
      if (row) m = { lastMinute: row.last_minute, lastFlush: row.last_flush };
    }
    this.metaCache = m;
    return m;
  }

  totals(now: number): Totals {
    const day = utcDay(now);
    if (this.totalsCache?.day === day) return this.totalsCache;
    const month = utcMonth(now);
    const t: Totals = { day, month, dayControl: 0, dayMedia: 0, monthControl: 0, monthMedia: 0, dayActiveMin: 0, dayMsgs: 0 };
    if (this.tableExists()) {
      for (const r of this.sql.all<{ ch: string; bytes: number }>(
        "SELECT ch, bytes FROM usage_day WHERE host = ? AND day = ?",
        this.host,
        day,
      )) {
        if (r.ch === "control") t.dayControl = r.bytes;
        else if (r.ch === "media") t.dayMedia = r.bytes;
      }
      for (const r of this.sql.all<{ ch: string; bytes: number }>(
        "SELECT ch, SUM(bytes) AS bytes FROM usage_day WHERE host = ? AND day >= ? AND day < ? GROUP BY ch",
        this.host,
        month + "-01",
        utcMonth(nextUtcMonth(now)) + "-01",
      )) {
        if (r.ch === "control") t.monthControl = r.bytes;
        else if (r.ch === "media") t.monthMedia = r.bytes;
      }
      t.dayActiveMin =
        this.sql.all<{ minutes: number }>("SELECT minutes FROM active_day WHERE host = ? AND day = ?", this.host, day)[0]
          ?.minutes ?? 0;
      if (this.hasMsgsTable()) {
        t.dayMsgs = this.sql.all<{ n: number }>("SELECT n FROM msgs_day WHERE host = ? AND day = ?", this.host, day)[0]?.n ?? 0;
      }
    }
    this.totalsCache = t;
    return t;
  }

  private hasMsgsTable(): boolean {
    this.msgsTable ??= this.sql.all("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'msgs_day'").length > 0;
    return this.msgsTable;
  }

  /** Write one flush. `day` is the UTC day the pending counts belong to. */
  commit(now: number, day: string, delta: Pending, lastMinute: number): void {
    this.ensure();
    if (delta.control > 0) this.addBytes(day, "control", delta.control);
    if (delta.media > 0) this.addBytes(day, "media", delta.media);
    if (delta.msgs > 0) {
      if (!this.hasMsgsTable()) {
        this.sql.run("CREATE TABLE IF NOT EXISTS msgs_day (host TEXT NOT NULL, day TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (host, day))");
        this.msgsTable = true;
      }
      this.sql.run(
        `INSERT INTO msgs_day (host, day, n) VALUES (?, ?, ?) ON CONFLICT (host, day) DO UPDATE SET n = n + excluded.n`,
        this.host,
        day,
        delta.msgs,
      );
    }
    if (delta.minutes > 0) {
      this.sql.run(
        `INSERT INTO active_day (host, day, minutes) VALUES (?, ?, ?)
         ON CONFLICT (host, day) DO UPDATE SET minutes = minutes + excluded.minutes`,
        this.host,
        day,
        delta.minutes,
      );
    }
    this.sql.run(
      `INSERT INTO host_meta (host, last_minute, last_flush) VALUES (?, ?, ?)
       ON CONFLICT (host) DO UPDATE SET last_minute = excluded.last_minute, last_flush = excluded.last_flush`,
      this.host,
      lastMinute,
      now,
    );
    // Only the current month is ever read, so earlier rows are dropped.
    const monthStart = utcMonth(now) + "-01";
    this.sql.run("DELETE FROM usage_day WHERE host = ? AND day < ?", this.host, monthStart);
    this.sql.run("DELETE FROM active_day WHERE host = ? AND day < ?", this.host, monthStart);
    if (this.msgsTable) this.sql.run("DELETE FROM msgs_day WHERE host = ? AND day < ?", this.host, monthStart);
    this.metaCache = { lastMinute, lastFlush: now };
    this.totalsCache = undefined;
  }

  private addBytes(day: string, ch: Channel, bytes: number): void {
    this.sql.run(
      `INSERT INTO usage_day (host, day, ch, bytes) VALUES (?, ?, ?, ?)
       ON CONFLICT (host, day, ch) DO UPDATE SET bytes = bytes + excluded.bytes`,
      this.host,
      day,
      ch,
      Math.round(bytes),
    );
  }
}

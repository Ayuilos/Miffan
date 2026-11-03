// A fake Engine platform with a controllable clock, for deterministic tests of timing-dependent rules.

import { Database } from "bun:sqlite";
import type { BudgetState } from "../../core/budget.ts";
import type { Att, BudgetPort, Frame, Platform, RequestCtx, Sock } from "../../core/engine.ts";
import { sqlite } from "../../bun/relay.ts";

export class FakeSock implements Sock {
  sent: Frame[] = [];
  closedWith?: [number, string];
  /** Time of the last ping the platform answered by itself (the Worker's auto-response), if any. */
  autoPing: number | null = null;
  constructor(
    readonly tags: string[],
    public a: Att,
  ) {}
  att(): Att {
    return structuredClone(this.a);
  }
  setAtt(a: Att): void {
    this.a = structuredClone(a);
  }
  send(d: Frame): void {
    if (this.closedWith) throw new Error("send on closed socket");
    this.sent.push(d);
  }
  close(code: number, reason: string): void {
    if (this.closedWith) throw new Error("already closed");
    this.closedWith = [code, reason];
  }
  lastAutoPing(): number | null {
    return this.autoPing;
  }
  json(i: number): any {
    return JSON.parse(this.sent[i] as string);
  }
  get texts(): any[] {
    return this.sent.filter((s) => typeof s === "string").map((s) => JSON.parse(s as string));
  }
}

export class World {
  t = Date.UTC(2026, 10, 1, 12, 0, 0);
  socks: FakeSock[] = [];
  alarm: number | null = null;
  pending: Promise<unknown>[] = [];
  db = new Database(":memory:");
  budgetState: BudgetState = "ok";
  reports: { bytes: number; minutes: number; requests: number }[] = [];
  stateCalls = 0;
  registrations = 0;
  ipChecks = 0;
  registrationLimit = Infinity;
  logs: Record<string, unknown>[] = [];

  budget: BudgetPort = {
    state: async () => {
      this.stateCalls++;
      return this.budgetState;
    },
    report: async (bytes, minutes, requests) => {
      this.reports.push({ bytes, minutes, requests });
      return this.budgetState;
    },
    checkIp: async () => {
      this.ipChecks++;
      return this.registrations >= this.registrationLimit ? { ok: false, retryAfter: 60 } : { ok: true };
    },
    registerIp: async () => {
      if (this.registrations >= this.registrationLimit) return { ok: false, retryAfter: 60 };
      this.registrations++;
      return { ok: true };
    },
  };

  platform(): Platform {
    return {
      now: () => this.t,
      sockets: (tag) => this.live().filter((s) => s.tags.includes(tag)),
      allSockets: () => this.live(),
      setAlarm: (at) => (this.alarm = at),
      waitUntil: (p) => void this.pending.push(p),
      sql: sqlite(this.db),
      budget: this.budget,
      log: (l) => this.logs.push(l),
    };
  }

  private live(): FakeSock[] {
    return this.socks.filter((s) => !s.closedWith);
  }

  requestCtx(host = "relay.test", ip = "203.0.113.9"): RequestCtx {
    return {
      host,
      ip,
      accept: (tags, att, after) => {
        const s = new FakeSock(tags, att);
        this.socks.push(s);
        after(s);
        return new Response(null);
      },
    };
  }

  /** Let queued waitUntil work finish. */
  async settle(): Promise<void> {
    while (this.pending.length) await this.pending.shift();
  }

  /** Advance the clock; if the alarm is due, run it. */
  async advance(ms: number, run: (alarm: () => Promise<void>) => Promise<void>): Promise<void> {
    this.t += ms;
    if (this.alarm !== null && this.alarm <= this.t) {
      this.alarm = null;
      await run(async () => {});
    }
  }
}

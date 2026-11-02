// Global singleton Durable Object: the relay-wide daily budget and the per-IP registration counter.
// HostRelay objects call it over RPC at most when they flush metering (every 30 s of activity) and when
// a new session starts with a stale cached state - never per message.

import { DurableObject } from "cloudflare:workers";
import { GlobalBudget, IpRegistrations, type BudgetState } from "../core/budget.ts";
import { parseConfig, type RelayConfig } from "../core/config.ts";
import { nextUtcMidnight } from "../core/time.ts";
import { doSql, type Env } from "./env.ts";

export class Budget extends DurableObject<Env> {
  private cfg: RelayConfig;
  private global: GlobalBudget;
  private ips: IpRegistrations;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    this.cfg = parseConfig(env.RELAY_CONFIG);
    const sql = doSql(ctx.storage.sql);
    this.global = new GlobalBudget(sql, this.cfg.policy.budget);
    this.ips = new IpRegistrations(sql);
  }

  state(): BudgetState {
    return this.global.state(Date.now());
  }

  report(bytes: number, minutes: number): BudgetState {
    return this.global.report(Date.now(), bytes, minutes);
  }

  async checkIp(ip: string): Promise<{ ok: true } | { ok: false; retryAfter: number }> {
    const now = Date.now();
    const r = await this.ips.check(now, ip, this.cfg.registrationsPerIpPerDay);
    if ((await this.ctx.storage.getAlarm()) === null) await this.ctx.storage.setAlarm(nextUtcMidnight(now) + 1000);
    return r;
  }

  async registerIp(ip: string): Promise<{ ok: true } | { ok: false; retryAfter: number }> {
    const now = Date.now();
    const r = await this.ips.register(now, ip, this.cfg.registrationsPerIpPerDay);
    // The counters must not outlive their day even if nobody registers tomorrow: purge at UTC midnight.
    if ((await this.ctx.storage.getAlarm()) === null) await this.ctx.storage.setAlarm(nextUtcMidnight(now) + 1000);
    return r;
  }

  async alarm(): Promise<void> {
    this.ips.purge(Date.now());
  }

  stats() {
    const now = Date.now();
    return { impl: "worker", ...this.global.stats(now, this.ips.total(now)) };
  }
}

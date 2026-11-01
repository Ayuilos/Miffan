import type { Budget } from "./budget.ts";
import type { HostRelay } from "./host-relay.ts";

export interface Env {
  /** One Durable Object per hostId. */
  RELAY: DurableObjectNamespace<HostRelay>;
  /** Singleton: global budget and per-IP registration counts. */
  BUDGET: DurableObjectNamespace<Budget>;
  /** JSON, see core/config.ts. Missing or empty means all defaults. */
  RELAY_CONFIG?: string;
  /** Secrets (wrangler secret put): access code (accessMode "token") and admin token. */
  ACCESS_TOKEN?: string;
  ADMIN_TOKEN?: string;
}

export function doSql(sql: SqlStorage) {
  return {
    run(q: string, ...p: (string | number | null)[]): void {
      sql.exec(q, ...p).toArray();
    },
    all<T>(q: string, ...p: (string | number | null)[]): T[] {
      return sql.exec(q, ...p).toArray() as T[];
    },
  };
}

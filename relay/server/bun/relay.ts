// Bun adapter: Bun.serve WebSocket handlers around one Engine per active host, state in bun:sqlite.

import { Database } from "bun:sqlite";
import { GlobalBudget, IpRegistrations } from "../core/budget.ts";
import type { RelayConfig } from "../core/config.ts";
import { infoDocument } from "../core/config.ts";
import { Engine, type Att, type BudgetPort, type Frame, type Platform, type RequestCtx, type Sock } from "../core/engine.ts";
import { jsonResponse, routeRequest, type Secrets } from "../core/http.ts";
import type { Sql, SqlValue } from "../core/sql.ts";

export function sqlite(db: Database): Sql {
  return {
    run: (sql, ...p: SqlValue[]) => void db.query(sql).run(...p),
    all: <T>(sql: string, ...p: SqlValue[]) => db.query(sql).all(...p) as T[],
  };
}

class BunSock implements Sock {
  ws?: Bun.ServerWebSocket<SockData>;
  dead = false;
  private outbox: Frame[] = [];
  private closeReq?: [number, string];

  constructor(
    readonly tags: string[],
    private a: Att,
    readonly after: (s: Sock) => void,
  ) {}

  att(): Att {
    return this.a;
  }
  setAtt(a: Att): void {
    this.a = a;
  }
  send(data: Frame): void {
    if (this.dead) return;
    if (this.ws) this.sendNow(this.ws, data);
    else this.outbox.push(data);
  }
  /** A frame the socket cannot take (0 = dropped under backpressure) would silently corrupt the stream, so end it. */
  private sendNow(ws: Bun.ServerWebSocket<SockData>, data: Frame): void {
    if (ws.send(data as string | Uint8Array) === 0) ws.close(1013, "backpressure");
  }

  close(code: number, reason: string): void {
    if (this.ws) this.ws.close(code, reason);
    else this.closeReq = [code, reason];
  }
  bind(ws: Bun.ServerWebSocket<SockData>): void {
    this.ws = ws;
    this.after(this);
    for (const f of this.outbox) this.sendNow(ws, f);
    this.outbox = [];
    if (this.closeReq) ws.close(...this.closeReq);
  }
}

interface SockData {
  slot: Slot;
  sock: BunSock;
}

class Slot {
  readonly socks = new Set<BunSock>();
  readonly engine: Engine;
  private timer?: ReturnType<typeof setTimeout>;

  constructor(
    readonly hostId: string,
    cfg: RelayConfig,
    sql: Sql,
    budget: BudgetPort,
    private onIdle: (s: Slot) => void,
  ) {
    const platform: Platform = {
      now: () => Date.now(),
      sockets: (tag) => [...this.socks].filter((s) => !s.dead && s.tags.includes(tag)),
      allSockets: () => [...this.socks].filter((s) => !s.dead),
      setAlarm: (at) => {
        clearTimeout(this.timer);
        this.timer = at === null ? undefined : setTimeout(() => void this.alarm(), Math.max(0, at - Date.now()));
      },
      waitUntil: (p) => void p.catch((e) => console.error(JSON.stringify({ ev: "error", msg: String(e) }))),
      sql,
      budget,
      log: (line) => console.log(JSON.stringify(line)),
    };
    this.engine = new Engine(platform, cfg);
  }

  private async alarm(): Promise<void> {
    try {
      await this.engine.onAlarm();
    } catch (e) {
      console.error(JSON.stringify({ ev: "error", msg: String(e) }));
    }
    this.checkIdle();
  }

  checkIdle(): void {
    if (this.engine.isIdle()) {
      clearTimeout(this.timer);
      this.onIdle(this);
    }
  }
}

export interface BunRelayOptions {
  config: RelayConfig;
  secrets: Secrets;
  db: Database;
  /** Header to read the client IP from when behind a reverse proxy (e.g. "x-forwarded-for"). The last value wins. */
  trustedIpHeader?: string;
}

export function createBunRelay(opts: BunRelayOptions) {
  const { config: cfg, secrets } = opts;
  const sql = sqlite(opts.db);
  const global = new GlobalBudget(sql, cfg.policy.budget);
  // Source addresses only ever live in memory (PROTOCOL §6.3).
  const ips = new IpRegistrations(sqlite(new Database(":memory:")));
  const budget: BudgetPort = {
    state: async () => global.state(Date.now()),
    report: async (bytes, minutes) => global.report(Date.now(), bytes, minutes),
    registerIp: (ip) => ips.register(Date.now(), ip, cfg.registrationsPerIpPerDay),
  };
  const slots = new Map<string, Slot>();
  const UPGRADED = new Response(null);

  function slotFor(hostId: string): Slot {
    let s = slots.get(hostId);
    if (!s) {
      s = new Slot(hostId, cfg, sql, budget, (done) => slots.delete(done.hostId));
      slots.set(hostId, s);
    }
    return s;
  }

  function clientIp(req: Request, server: Bun.Server<SockData>): string {
    if (opts.trustedIpHeader) {
      const v = req.headers.get(opts.trustedIpHeader);
      const last = v?.split(",").pop()?.trim();
      if (last) return last;
    }
    return server.requestIP(req)?.address ?? "unknown";
  }

  async function fetch(req: Request, server: Bun.Server<SockData>): Promise<Response | undefined> {
    const route = await routeRequest(req, cfg, secrets);
    if (route instanceof Response) return route;
    if (route.kind === "info") return jsonResponse(200, infoDocument(cfg, "bun"));
    if (route.kind === "admin") {
      const online = [...slots.values()].filter((s) => [...s.socks].some((k) => k.tags.includes("host"))).length;
      return jsonResponse(200, { impl: "bun", ...global.stats(Date.now(), ips.total(Date.now())), hostsOnline: online });
    }
    const slot = slotFor(route.hostId);
    const rc: RequestCtx = {
      host: (req.headers.get("host") ?? new URL(req.url).host).toLowerCase(),
      ip: clientIp(req, server),
      accept(tags, att, after) {
        const sock = new BunSock(tags, att, after);
        slot.socks.add(sock);
        if (!server.upgrade(req, { data: { slot, sock } })) {
          slot.socks.delete(sock);
          return jsonResponse(400, { reason: "bad_request" });
        }
        return UPGRADED;
      },
    };
    let res: Response;
    try {
      res = await slot.engine.start(route, rc);
    } finally {
      slot.checkIdle();
    }
    return res === UPGRADED ? undefined : res;
  }

  const websocket: Bun.WebSocketHandler<SockData> = {
    // Larger than any legal frame, so oversized frames reach the engine and are closed with 4413.
    maxPayloadLength: 1 << 20,
    idleTimeout: 120,
    open(ws) {
      ws.data.sock.bind(ws);
    },
    async message(ws, msg) {
      const { slot, sock } = ws.data;
      try {
        await slot.engine.onMessage(sock, typeof msg === "string" ? msg : new Uint8Array(msg.buffer, msg.byteOffset, msg.byteLength));
      } catch (e) {
        console.error(JSON.stringify({ ev: "error", msg: String(e) }));
      }
    },
    close(ws) {
      const { slot, sock } = ws.data;
      sock.dead = true;
      slot.socks.delete(sock);
      slot.engine.onClose(sock);
      slot.checkIdle();
    },
  };

  /** Write pending metering for every host (used on shutdown). */
  async function flushAll(): Promise<void> {
    await Promise.all([...slots.values()].map((s) => s.engine.flush()));
  }

  return { fetch, websocket, flushAll, slots };
}

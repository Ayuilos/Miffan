// Bun adapter: Bun.serve WebSocket handlers around one Engine per active host, state in bun:sqlite.

import { Database } from "bun:sqlite";
import { GlobalBudget, IpRegistrations } from "../core/budget.ts";
import { CLOSE_CODE } from "../core/codes.ts";
import type { RelayConfig } from "../core/config.ts";
import { infoDocument } from "../core/config.ts";
import { Engine, type Att, type BudgetPort, type Frame, type Platform, type RequestCtx, type Sock } from "../core/engine.ts";
import { jsonResponse, routeRequest, STREAM_CONTENT_TYPE, type Secrets } from "../core/http.ts";
import type { Sql, SqlValue } from "../core/sql.ts";
import { encodeStreamClose, encodeStreamFrame, STREAM_PRELUDE, StreamDecoder } from "../core/stream.ts";

const STREAM_BACKPRESSURE_LIMIT = 4 * 1024 * 1024;
const STREAM_RESPONSE_HEADERS = {
  "content-type": STREAM_CONTENT_TYPE,
  "cache-control": "no-store, no-transform",
  "x-accel-buffering": "no",
} as const;

export function sqlite(db: Database): Sql {
  return {
    run: (sql, ...p: SqlValue[]) => void db.query(sql).run(...p),
    all: <T>(sql: string, ...p: SqlValue[]) => db.query(sql).all(...p) as T[],
  };
}

interface SlotSock extends Sock {
  dead: boolean;
  readonly tags: string[];
}

class BunSock implements SlotSock {
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

class BunStreamSock implements SlotSock {
  dead = false;
  private a: Att;
  private controller?: ReadableStreamDefaultController<Uint8Array>;
  private outbox: Uint8Array[] = [STREAM_PRELUDE];
  private head = 0;
  private queuedBytes = STREAM_PRELUDE.byteLength;
  private closing = false;
  private closeAfterDrain = false;
  private reader?: ReadableStreamDefaultReader<Uint8Array>;
  private cleaned = false;

  constructor(
    readonly tags: string[],
    att: Att,
    private readonly slot: Slot,
    private readonly req: Request,
    private readonly after: (s: Sock) => void,
  ) {
    this.a = att;
  }

  att(): Att {
    return this.a;
  }

  setAtt(a: Att): void {
    this.a = a;
  }

  createResponse(): Response {
    const readable = new ReadableStream<Uint8Array>({
      start: (controller) => {
        this.controller = controller;
        this.after(this);
        this.drain();
      },
      pull: () => {
        this.drain();
      },
      cancel: () => {
        this.closing = true;
        this.closeController();
        this.cancelInput();
        this.cleanup();
      },
    });
    void this.pumpInput();
    return new Response(readable, { status: 200, headers: STREAM_RESPONSE_HEADERS });
  }

  send(data: Frame): void {
    if (this.dead || this.closing) return;
    const encoded = encodeStreamFrame(data);
    if (this.queuedBytes + encoded.byteLength > STREAM_BACKPRESSURE_LIMIT) {
      this.close(1013, "backpressure");
      return;
    }
    this.outbox.push(encoded);
    this.queuedBytes += encoded.byteLength;
    this.drain();
  }

  close(code: number, reason: string): void {
    if (this.closing) return;
    this.closing = true;
    const frame = encodeStreamClose(code, reason);
    if (code === 1013) {
      // Backpressure exceeded: bypass desiredSize so a stalled reader still sees EOF immediately.
      this.outbox.length = 0;
      this.head = 0;
      this.queuedBytes = 0;
      if (this.controller) {
        try {
          this.controller.enqueue(frame);
        } catch {
          // ignore
        }
        this.closeController();
      }
    } else {
      this.outbox.push(frame);
      this.queuedBytes += frame.byteLength;
      this.closeAfterDrain = true;
      this.drain();
    }
    this.cancelInput();
    this.cleanup();
  }

  private drain(): void {
    const c = this.controller;
    if (!c) return;
    while (this.head < this.outbox.length && (c.desiredSize ?? 0) > 0) {
      let chunk: Uint8Array;
      const pending = this.outbox.length - this.head;
      if (pending === 1) {
        chunk = this.outbox[this.head++]!;
      } else {
        let total = 0;
        for (let i = this.head; i < this.outbox.length; i++) total += this.outbox[i]!.byteLength;
        chunk = new Uint8Array(total);
        let pos = 0;
        for (let i = this.head; i < this.outbox.length; i++) {
          const part = this.outbox[i]!;
          chunk.set(part, pos);
          pos += part.byteLength;
        }
        this.head = this.outbox.length;
      }
      this.queuedBytes -= chunk.byteLength;
      if (this.head === this.outbox.length) {
        this.outbox.length = 0;
        this.head = 0;
        this.queuedBytes = 0;
      }
      try {
        c.enqueue(chunk);
      } catch {
        this.closeController();
        return;
      }
    }
    if (this.closeAfterDrain && this.head === this.outbox.length) {
      this.closeController();
    }
  }

  private closeController(): void {
    if (!this.controller) return;
    try {
      this.controller.close();
    } catch {
      // stream already closed or errored
    }
    this.controller = undefined;
  }

  private cancelInput(): void {
    const r = this.reader;
    if (r) void r.cancel().catch(() => {});
  }

  private cleanup(): void {
    if (this.cleaned) return;
    this.cleaned = true;
    this.dead = true;
    this.slot.socks.delete(this);
    this.slot.engine.onClose(this);
    this.slot.checkIdle();
  }

  private async pumpInput(): Promise<void> {
    const body = this.req.body;
    if (!body) {
      this.cleanup();
      return;
    }
    const reader = body.getReader();
    this.reader = reader;
    const decoder = new StreamDecoder();
    try {
      while (!this.closing && !this.dead) {
        const { value, done } = await reader.read();
        if (done) break;
        if (!value || value.byteLength === 0) continue;
        for (const ev of decoder.push(value)) {
          if (this.closing || this.dead) break;
          if (ev.kind === "prelude") continue;
          if (ev.kind === "oversize") {
            this.close(CLOSE_CODE.frame_too_large, "frame_too_large");
            return;
          }
          if (ev.kind === "invalid") {
            this.close(CLOSE_CODE.early_frame, "bad_request");
            return;
          }
          if (ev.kind === "close") {
            this.close(ev.code, ev.reason);
            return;
          }
          await this.slot.engine.onMessage(this, ev.data);
        }
      }
    } catch (e) {
      if (!this.closing && !this.dead) {
        console.error(JSON.stringify({ ev: "stream_read_error", msg: String(e) }));
      }
    } finally {
      this.cleanup();
    }
  }
}

interface SockData {
  slot: Slot;
  sock: BunSock;
}

class Slot {
  readonly socks = new Set<SlotSock>();
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
      stream: true,
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
    report: async (bytes, minutes, requests) => global.report(Date.now(), bytes, minutes, requests),
    checkIp: (ip) => ips.check(Date.now(), ip, cfg.registrationsPerIpPerDay),
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
    if (route.kind === "info") return jsonResponse(200, infoDocument(cfg, "bun", true));
    if (route.kind === "admin") {
      const online = [...slots.values()].filter((s) => [...s.socks].some((k) => k.tags.includes("host"))).length;
      return jsonResponse(200, { impl: "bun", ...global.stats(Date.now(), ips.total(Date.now())), hostsOnline: online });
    }
    const slot = slotFor(route.hostId);
    const rc: RequestCtx = {
      host: (req.headers.get("host") ?? new URL(req.url).host).toLowerCase(),
      ip: clientIp(req, server),
      accept(tags, att, after) {
        if (route.transport === "stream") {
          server.timeout(req, 0);
          const sock = new BunStreamSock(tags, att, slot, req, after);
          slot.socks.add(sock);
          return sock.createResponse();
        }
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
    // Bytes buffered for a reader that is slower than its sender. Beyond this send() drops the frame and
    // BunSock closes the connection with 1013 (PROTOCOL §9). SSH flow control keeps an ordinary session
    // far below it; the default (16 MiB per socket) let a few stalled phones take much of a small VPS.
    backpressureLimit: 4 * 1024 * 1024,
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

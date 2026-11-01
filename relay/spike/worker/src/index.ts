// R0 spike relay (protocol v0). Not the R1 design: one shared dev token, no device keys, no policy.
//
//   connector  ── WS /v0/host/<hostId>            control socket, receives {"t":"open","sid","ch"}
//   connector  ── WS /v0/host/<hostId>/s/<sid>    one data socket per session
//   client     ── WS /v0/connect/<hostId>?ch=…    waits for {"t":"ready"}, then binary frames
//
// Binary frames are opaque bytes and are forwarded unchanged. Text frames are control messages.
// Text "ping" is answered with "pong" by the runtime without waking the object.
import { DurableObject } from "cloudflare:workers";

export interface Env {
  RELAY: DurableObjectNamespace<HostRelay>;
  DEV_TOKEN: string;
}

const HOST_ID = /^[a-z2-7]{16,64}$/;
const SID = /^[a-f0-9]{32}$/;
const CHANNELS = new Set(["control", "media"]);
const PAIR_TIMEOUT_MS = 10_000;
const FLUSH_MS = 5_000;

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

async function tokenMatches(given: string | null, expected: string): Promise<boolean> {
  if (!given || !expected) return false;
  const enc = new TextEncoder();
  const [a, b] = await Promise.all([
    crypto.subtle.digest("SHA-256", enc.encode(given)),
    crypto.subtle.digest("SHA-256", enc.encode(expected)),
  ]);
  return crypto.subtle.timingSafeEqual(a, b);
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    const url = new URL(req.url);
    const colo = (req as { cf?: { colo?: string } }).cf?.colo ?? "?";
    if (url.pathname === "/v0/ping") return json(200, { ok: true, colo });
    const parts = url.pathname.split("/").filter(Boolean); // v0, kind, hostId, [s, sid]
    if (parts[0] !== "v0" || !parts[2] || !HOST_ID.test(parts[2])) return json(404, { reason: "not_found" });
    const token = req.headers.get("authorization")?.replace(/^Bearer /, "") ?? null;
    if (!(await tokenMatches(token, env.DEV_TOKEN))) return json(401, { reason: "unauthorized" });
    if (parts[1] === "echo") {
      // R0: WebSocket echo inside the Worker, no Durable Object, to split edge latency from object latency.
      const pair = new WebSocketPair();
      pair[1].accept();
      pair[1].addEventListener("message", (e) => pair[1].send(e.data));
      return new Response(null, { status: 101, webSocket: pair[0] });
    }
    const headers = new Headers(req.headers);
    headers.set("x-edge-colo", colo);
    // locationHint only matters when the object is first created; R0 compares placements with it.
    const hint = url.searchParams.get("hint") as DurableObjectLocationHint | null;
    const stub = env.RELAY.get(env.RELAY.idFromName(parts[2]), hint ? { locationHint: hint } : undefined);
    return stub.fetch(new Request(req, { headers }));
  },
};

type Attachment =
  | { role: "host"; colo: string }
  | { role: "c"; sid: string; ch: string; at: number; colo: string }
  | { role: "h"; sid: string; ch: string };

type Counter = { bytes: number; msgs: number };

export class HostRelay extends DurableObject<Env> {
  private pending = new Map<string, Counter>(); // "ch|dir" -> not yet flushed
  private placement?: Promise<string>;

  /** The colo this object runs in, from Cloudflare's trace endpoint (R0 measurement only). */
  private where(): Promise<string> {
    this.placement ??= fetch("https://www.cloudflare.com/cdn-cgi/trace")
      .then((r) => r.text())
      .then((t) => /^colo=(.+)$/m.exec(t)?.[1] ?? "?")
      .catch(() => "?");
    return this.placement;
  }

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    ctx.storage.sql.exec(`CREATE TABLE IF NOT EXISTS usage (
      day TEXT NOT NULL, ch TEXT NOT NULL, dir TEXT NOT NULL,
      bytes INTEGER NOT NULL, msgs INTEGER NOT NULL, PRIMARY KEY (day, ch, dir))`);
    // R0: count constructions; a hibernated object is constructed again on its next event.
    ctx.storage.sql.exec("CREATE TABLE IF NOT EXISTS wakes (n INTEGER NOT NULL, last INTEGER NOT NULL)");
    if (!ctx.storage.sql.exec("SELECT 1 FROM wakes").toArray().length) ctx.storage.sql.exec("INSERT INTO wakes VALUES (0, 0)");
    ctx.storage.sql.exec("UPDATE wakes SET n = n + 1, last = ?", Date.now());
  }

  async fetch(req: Request): Promise<Response> {
    const url = new URL(req.url);
    const parts = url.pathname.split("/").filter(Boolean);
    const kind = parts[1];
    const colo = req.headers.get("x-edge-colo") ?? "?";
    if (kind === "stats") return json(200, { ...this.stats(), objectColo: await this.where() });
    if (req.headers.get("upgrade")?.toLowerCase() !== "websocket") return json(426, { reason: "websocket_required" });

    if (kind === "host" && parts.length === 3) {
      for (const old of this.ctx.getWebSockets("host")) old.close(4000, "replaced");
      return this.accept(["host"], { role: "host", colo });
    }
    if (kind === "host" && parts[3] === "s" && SID.test(parts[4] ?? "")) {
      const sid = parts[4];
      const client = this.ctx.getWebSockets(`c:${sid}`)[0];
      if (!client || this.ctx.getWebSockets(`h:${sid}`).length) return json(404, { reason: "no_session" });
      const ch = (client.deserializeAttachment() as Attachment & { role: "c" }).ch;
      const res = this.accept([`h:${sid}`], { role: "h", sid, ch });
      client.send(JSON.stringify({ t: "ready", sid, clientColo: (client.deserializeAttachment() as { colo: string }).colo, hostColo: colo, objectColo: await this.where() }));
      return res;
    }
    if (kind === "connect") {
      const ch = url.searchParams.get("ch") ?? "control";
      if (!CHANNELS.has(ch)) return json(400, { reason: "bad_channel" });
      const host = this.ctx.getWebSockets("host")[0];
      if (!host) return json(404, { reason: "host_offline" });
      const sid = [...crypto.getRandomValues(new Uint8Array(16))].map((b) => b.toString(16).padStart(2, "0")).join("");
      const res = this.accept(["c", `c:${sid}`], { role: "c", sid, ch, at: Date.now(), colo });
      host.send(JSON.stringify({ t: "open", sid, ch }));
      await this.ctx.storage.setAlarm(Date.now() + PAIR_TIMEOUT_MS);
      return res;
    }
    return json(404, { reason: "not_found" });
  }

  private accept(tags: string[], attachment: Attachment): Response {
    const pair = new WebSocketPair();
    this.ctx.acceptWebSocket(pair[1], tags);
    pair[1].serializeAttachment(attachment);
    return new Response(null, { status: 101, webSocket: pair[0] });
  }

  async webSocketMessage(ws: WebSocket, msg: string | ArrayBuffer): Promise<void> {
    const a = ws.deserializeAttachment() as Attachment;
    if (a.role === "host" || typeof msg === "string") return;
    const peer = this.ctx.getWebSockets(a.role === "c" ? `h:${a.sid}` : `c:${a.sid}`)[0];
    if (!peer) {
      ws.close(4409, "not_ready");
      return;
    }
    peer.send(msg);
    this.count(a.ch, a.role === "c" ? "up" : "down", msg.byteLength);
  }

  async webSocketClose(ws: WebSocket, code: number): Promise<void> {
    this.closePeer(ws, code);
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.closePeer(ws, 1011);
  }

  private closePeer(ws: WebSocket, code: number): void {
    const a = ws.deserializeAttachment() as Attachment;
    if (a.role === "host") return; // sessions keep their own sockets
    const peer = this.ctx.getWebSockets(a.role === "c" ? `h:${a.sid}` : `c:${a.sid}`)[0];
    try {
      peer?.close(code >= 4000 || code === 1000 ? code : 1000, "peer_closed");
    } catch {
      // already closing
    }
  }

  async alarm(): Promise<void> {
    // One alarm serves both pairing timeouts and usage flushes, so re-arm while either is outstanding.
    const now = Date.now();
    let waiting = false;
    for (const ws of this.ctx.getWebSockets("c")) {
      const a = ws.deserializeAttachment() as Attachment & { role: "c" };
      if (this.ctx.getWebSockets(`h:${a.sid}`).length) continue;
      if (now - a.at >= PAIR_TIMEOUT_MS) ws.close(4408, "host_timeout");
      else waiting = true;
    }
    this.flush();
    if (waiting) await this.ctx.storage.setAlarm(now + 1_000);
  }

  private count(ch: string, dir: string, bytes: number): void {
    const key = `${ch}|${dir}`;
    const c = this.pending.get(key) ?? { bytes: 0, msgs: 0 };
    c.bytes += bytes;
    c.msgs += 1;
    if (this.pending.size === 0) void this.ctx.storage.setAlarm(Date.now() + FLUSH_MS);
    this.pending.set(key, c);
  }

  private flush(): void {
    const day = new Date().toISOString().slice(0, 10);
    for (const [key, c] of this.pending) {
      const [ch, dir] = key.split("|");
      this.ctx.storage.sql.exec(
        `INSERT INTO usage (day, ch, dir, bytes, msgs) VALUES (?, ?, ?, ?, ?)
         ON CONFLICT (day, ch, dir) DO UPDATE SET bytes = bytes + excluded.bytes, msgs = msgs + excluded.msgs`,
        day, ch, dir, c.bytes, c.msgs);
    }
    this.pending.clear();
  }

  private stats() {
    this.flush();
    return {
      hostOnline: this.ctx.getWebSockets("host").length > 0,
      sessions: this.ctx.getWebSockets("c").length,
      wakes: this.ctx.storage.sql.exec("SELECT n, last FROM wakes").one(),
      usage: this.ctx.storage.sql.exec("SELECT * FROM usage ORDER BY day DESC, ch, dir").toArray(),
    };
  }
}

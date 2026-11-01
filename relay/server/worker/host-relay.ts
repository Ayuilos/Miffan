// One Durable Object per hostId. A thin shell: hibernatable WebSockets, tags and attachments are
// exposed to the shared Engine, which holds all of the protocol logic.

import { DurableObject } from "cloudflare:workers";
import { parseConfig } from "../core/config.ts";
import { Engine, type Att, type Frame, type Platform, type RequestCtx, type Sock } from "../core/engine.ts";
import { routeRequest } from "../core/http.ts";
import { doSql, type Env } from "./env.ts";

function wrap(ws: WebSocket): Sock {
  return {
    att: () => ws.deserializeAttachment() as Att,
    setAtt: (a) => ws.serializeAttachment(a),
    send: (d: Frame) => ws.send(d as string | ArrayBuffer),
    close: (code, reason) => ws.close(code, reason),
  };
}

export class HostRelay extends DurableObject<Env> {
  private engine: Engine;

  constructor(ctx: DurableObjectState, env: Env) {
    super(ctx, env);
    // Answered by the runtime without waking the object (PROTOCOL §5.4).
    ctx.setWebSocketAutoResponse(new WebSocketRequestResponsePair("ping", "pong"));
    const cfg = parseConfig(env.RELAY_CONFIG);
    const budget = env.BUDGET.get(env.BUDGET.idFromName("global"));
    const platform: Platform = {
      now: () => Date.now(),
      sockets: (tag) => ctx.getWebSockets(tag).map(wrap),
      allSockets: () => ctx.getWebSockets().map(wrap),
      setAlarm: (at) => {
        const op = at === null ? ctx.storage.deleteAlarm() : ctx.storage.setAlarm(at);
        op.catch((e) => console.error(JSON.stringify({ ev: "alarm_error", msg: String(e) })));
      },
      waitUntil: (p) => ctx.waitUntil(p.catch((e) => console.error(JSON.stringify({ ev: "error", msg: String(e) })))),
      sql: doSql(ctx.storage.sql),
      budget: {
        state: () => budget.state(),
        report: (bytes, minutes) => budget.report(bytes, minutes),
        registerIp: (ip) => budget.registerIp(ip),
      },
      log: (line) => console.log(JSON.stringify(line)),
    };
    this.engine = new Engine(platform, cfg);
  }

  async fetch(req: Request): Promise<Response> {
    // The Worker already checked access; here only the shape is needed again.
    const route = await routeRequest(req, parseConfig("{}"), {});
    if (route instanceof Response || route.kind === "info" || route.kind === "admin") {
      return route instanceof Response ? route : new Response(null, { status: 404 });
    }
    const pair = () => new WebSocketPair();
    const rc: RequestCtx = {
      host: req.headers.get("x-relay-host") ?? "",
      ip: req.headers.get("x-relay-ip") ?? "unknown",
      accept: (tags, att, after) => {
        const [client, server] = Object.values(pair());
        this.ctx.acceptWebSocket(server!, tags);
        server!.serializeAttachment(att);
        after(wrap(server!));
        return new Response(null, { status: 101, webSocket: client! });
      },
    };
    return this.engine.start(route, rc);
  }

  async webSocketMessage(ws: WebSocket, msg: string | ArrayBuffer): Promise<void> {
    try {
      await this.engine.onMessage(wrap(ws), msg);
    } catch (e) {
      console.error(JSON.stringify({ ev: "error", msg: String(e) }));
    }
  }

  async webSocketClose(ws: WebSocket, code: number, reason: string): Promise<void> {
    let initiatedByUs = false;
    try {
      initiatedByUs = Boolean((ws.deserializeAttachment() as { x?: number } | null)?.x);
    } catch {
      // no attachment
    }
    if (!initiatedByUs) {
      // The remote closed first: complete the handshake. 1005/1006 and friends are not legal to send back.
      try {
        ws.close(code >= 1000 && code <= 4999 && ![1005, 1006, 1015].includes(code) ? code : 1000, reason);
      } catch {
        // already closing
      }
    }
    try {
      this.engine.onClose(wrap(ws));
    } catch (e) {
      console.error(JSON.stringify({ ev: "error", msg: String(e) }));
    }
  }

  async webSocketError(ws: WebSocket): Promise<void> {
    this.engine.onClose(wrap(ws));
  }

  async alarm(): Promise<void> {
    try {
      await this.engine.onAlarm();
    } catch (e) {
      console.error(JSON.stringify({ ev: "error", msg: String(e) }));
    }
  }
}

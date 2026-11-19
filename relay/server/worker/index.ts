// Cloudflare Worker entry: routing and access checks, then hand-over to the per-host Durable Object.

import { CLOSE_CODE, LIMITS } from "../core/codes.ts";
import { ConfigError, infoDocument, parseConfig, type RelayConfig } from "../core/config.ts";
import { jsonResponse, routeRequest, STREAM_CONTENT_TYPE } from "../core/http.ts";
import { encodeStreamClose, encodeStreamFrame, STREAM_PRELUDE, StreamDecoder } from "../core/stream.ts";
import type { Env } from "./env.ts";

export { HostRelay } from "./host-relay.ts";
export { Budget } from "./budget.ts";

async function bridgeStream(req: Request, stub: DurableObjectStub, headers: Headers): Promise<Response> {
  headers.delete("content-type");
  headers.set("upgrade", "websocket");
  headers.set("connection", "Upgrade");
  const wsRes = await stub.fetch(new Request(req.url, { method: "GET", headers }));
  const ws = wsRes.webSocket;
  if (!ws) return wsRes;
  ws.accept();

  const decoder = new StreamDecoder();
  let closing = false;
  let controller!: ReadableStreamDefaultController<Uint8Array>;

  const shutdown = (sendClose?: [number, string]) => {
    if (closing) return;
    closing = true;
    if (sendClose) {
      try {
        controller.enqueue(encodeStreamClose(sendClose[0], sendClose[1]));
      } catch {
        // stream closed
      }
    }
    try {
      controller.close();
    } catch {
      // already closed
    }
    try {
      ws.close(sendClose?.[0] ?? 1000, sendClose?.[1] ?? "");
    } catch {
      // already closed
    }
  };

  const readable = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
      c.enqueue(STREAM_PRELUDE);
      ws.addEventListener("message", (ev) => {
        if (closing) return;
        const data = ev.data;
        const frame = typeof data === "string" ? data : new Uint8Array(data as ArrayBuffer);
        try {
          c.enqueue(encodeStreamFrame(frame));
        } catch {
          shutdown();
        }
      });
      ws.addEventListener("close", (ev) => {
        shutdown([ev.code || 1000, ev.reason ?? ""]);
      });
      ws.addEventListener("error", () => {
        shutdown();
      });
    },
    cancel() {
      shutdown();
    },
  });

  if (req.body) {
    void (async () => {
      const reader = req.body!.getReader();
      try {
        while (!closing) {
          const { value, done } = await reader.read();
          if (done) break;
          if (!value || value.byteLength === 0) continue;
          for (const ev of decoder.push(value)) {
            if (closing) break;
            if (ev.kind === "prelude") continue;
            if (ev.kind === "oversize") {
              try {
                ws.send(new Uint8Array(LIMITS.maxBinaryFrame + 1));
              } catch {
                // ignore
              }
              return;
            }
            if (ev.kind === "invalid") {
              shutdown([CLOSE_CODE.early_frame, "bad_request"]);
              return;
            }
            if (ev.kind === "close") {
              try {
                ws.close(
                  ev.code >= 1000 && ev.code <= 4999 && ![1005, 1006, 1015].includes(ev.code) ? ev.code : 1000,
                  ev.reason,
                );
              } catch {
                // ignore
              }
              return;
            }
            ws.send(ev.data);
          }
        }
      } catch {
        // body aborted
      } finally {
        try {
          ws.close(1000, "");
        } catch {
          // ignore
        }
      }
    })();
  } else {
    shutdown();
  }

  return new Response(readable, {
    status: 200,
    headers: {
      "content-type": STREAM_CONTENT_TYPE,
      "cache-control": "no-store, no-transform",
    },
  });
}

export default {
  async fetch(req: Request, env: Env): Promise<Response> {
    let cfg: RelayConfig;
    try {
      cfg = parseConfig(env.RELAY_CONFIG);
    } catch (e) {
      return jsonResponse(500, { reason: "misconfigured", detail: e instanceof ConfigError ? e.message : "bad config" });
    }
    const route = await routeRequest(req, cfg, { accessToken: env.ACCESS_TOKEN, adminToken: env.ADMIN_TOKEN });
    if (route instanceof Response) return route;
    if (route.kind === "info") return jsonResponse(200, infoDocument(cfg, "worker", false));
    if (route.kind === "admin") return jsonResponse(200, await env.BUDGET.get(env.BUDGET.idFromName("global")).stats());

    const headers = new Headers(req.headers);
    for (const k of [...headers.keys()]) if (k.startsWith("x-relay-")) headers.delete(k);
    headers.set("x-relay-ip", req.headers.get("cf-connecting-ip") ?? "unknown");
    headers.set("x-relay-host", (req.headers.get("host") ?? new URL(req.url).host).toLowerCase());
    // locationHint only matters when the object is first created (PROTOCOL §4, `hint`).
    const hint = route.kind === "host" ? route.hint : undefined;
    const stub = env.RELAY.get(env.RELAY.idFromName(route.hostId), hint ? { locationHint: hint as DurableObjectLocationHint } : undefined);
    if (route.transport === "stream") return bridgeStream(req, stub, headers);
    return stub.fetch(new Request(req, { headers }));
  },
};

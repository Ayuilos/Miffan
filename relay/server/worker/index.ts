// Cloudflare Worker entry: routing and access checks, then hand-over to the per-host Durable Object.

import { ConfigError, infoDocument, parseConfig, type RelayConfig } from "../core/config.ts";
import { jsonResponse, routeRequest } from "../core/http.ts";
import type { Env } from "./env.ts";

export { HostRelay } from "./host-relay.ts";
export { Budget } from "./budget.ts";

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
    if (route.kind === "info") return jsonResponse(200, infoDocument(cfg, "worker"));
    if (route.kind === "admin") return jsonResponse(200, await env.BUDGET.get(env.BUDGET.idFromName("global")).stats());

    const headers = new Headers(req.headers);
    for (const k of [...headers.keys()]) if (k.startsWith("x-relay-")) headers.delete(k);
    headers.set("x-relay-ip", req.headers.get("cf-connecting-ip") ?? "unknown");
    headers.set("x-relay-host", (req.headers.get("host") ?? new URL(req.url).host).toLowerCase());
    // locationHint only matters when the object is first created (PROTOCOL §4, `hint`).
    const hint = route.kind === "host" ? route.hint : undefined;
    const stub = env.RELAY.get(env.RELAY.idFromName(route.hostId), hint ? { locationHint: hint as DurableObjectLocationHint } : undefined);
    return stub.fetch(new Request(req, { headers }));
  },
};

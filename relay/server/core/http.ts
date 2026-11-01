// HTTP routing shared by both adapters: path parsing, access-code check, error bodies (PROTOCOL §3).

import { HTTP_STATUS, isChannel, type Channel, type Reason } from "./codes.ts";
import type { RelayConfig } from "./config.ts";
import { HOST_ID_RE, SID_RE, b64uDecode, bearerToken, hostIdFromPub, secretEquals } from "./crypto.ts";
import type { Rejection } from "./policy.ts";

export type Route =
  | { kind: "info" }
  | { kind: "admin" }
  | { kind: "host"; hostId: string; pub: Uint8Array; hint?: string }
  | { kind: "data"; hostId: string; sid: string }
  | { kind: "connect"; hostId: string; ch: Channel };

export interface Secrets {
  accessToken?: string;
  adminToken?: string;
}

const LOCATION_HINTS = new Set(["wnam", "enam", "sam", "weur", "eeur", "apac", "oc", "afr", "me"]);

export function errorBody(reason: Reason, retryAfter?: number): string {
  return JSON.stringify(retryAfter === undefined ? { reason } : { reason, retryAfter });
}

export function errorResponse(rej: Rejection): Response {
  const headers: Record<string, string> = { "content-type": "application/json" };
  if (rej.retryAfter !== undefined) headers["retry-after"] = String(rej.retryAfter);
  return new Response(errorBody(rej.reason, rej.retryAfter), { status: rej.status, headers });
}

export function reject(reason: Reason, retryAfter?: number, status?: number): Response {
  return errorResponse({ status: status ?? HTTP_STATUS[reason] ?? 400, reason, retryAfter });
}

export function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

/** Does the request carry the deploy-time access code (when the relay requires one)? */
export async function accessAllowed(cfg: RelayConfig, secrets: Secrets, headers: Headers): Promise<boolean> {
  if (cfg.accessMode !== "token") return true;
  if (!secrets.accessToken) return false; // misconfigured: refuse everything rather than run open
  return secretEquals(bearerToken(headers.get("authorization")), secrets.accessToken);
}

function isUpgrade(req: Request): boolean {
  return req.headers.get("upgrade")?.toLowerCase() === "websocket";
}

/**
 * Parse and pre-check a request. Returns the Route, or the Response to send back.
 * Checks run in the order of PROTOCOL §6.4 as far as they are request-only: access code, then request shape.
 */
export async function routeRequest(req: Request, cfg: RelayConfig, secrets: Secrets): Promise<Route | Response> {
  const url = new URL(req.url);
  const parts = url.pathname.split("/").filter(Boolean);
  if (parts[0] !== "v1") return reject("bad_request", undefined, 404);
  if (req.method !== "GET") return reject("bad_request", undefined, 405);

  if (parts.length === 2 && parts[1] === "info") return { kind: "info" };

  if (parts.length === 3 && parts[1] === "admin" && parts[2] === "stats") {
    const ok = await secretEquals(bearerToken(req.headers.get("authorization")), secrets.adminToken);
    return ok ? { kind: "admin" } : reject("unauthorized");
  }

  if (parts[1] === "host" && parts.length === 5 && parts[3] === "session") {
    // The sid is the credential here, so there is no access-code check (PROTOCOL §3).
    if (!isUpgrade(req)) return reject("bad_request");
    const [hostId, sid] = [parts[2]!, parts[4]!];
    if (!HOST_ID_RE.test(hostId) || !SID_RE.test(sid)) return reject("bad_request");
    return { kind: "data", hostId, sid };
  }

  if (parts[1] === "host" && parts.length === 2) {
    if (!(await accessAllowed(cfg, secrets, req.headers))) return reject("unauthorized");
    if (!isUpgrade(req)) return reject("bad_request");
    const pub = b64uDecode(url.searchParams.get("pub") ?? "");
    if (!pub || pub.length !== 32) return reject("bad_request");
    const hint = url.searchParams.get("hint") ?? undefined;
    return {
      kind: "host",
      hostId: await hostIdFromPub(pub),
      pub,
      // An unknown hint is ignored rather than rejected so new regions do not break old relays.
      hint: hint && LOCATION_HINTS.has(hint) ? hint : undefined,
    };
  }

  if (parts[1] === "connect" && parts.length === 3) {
    if (!(await accessAllowed(cfg, secrets, req.headers))) return reject("unauthorized");
    if (!isUpgrade(req)) return reject("bad_request");
    const ch = url.searchParams.get("ch");
    if (!HOST_ID_RE.test(parts[2]!) || !isChannel(ch)) return reject("bad_request");
    return { kind: "connect", hostId: parts[2]!, ch };
  }

  return reject("bad_request", undefined, 404);
}

// Reason codes and close codes (PROTOCOL §9). One place so both adapters agree.

export type Reason =
  | "bad_request"
  | "unauthorized"
  | "media_disabled"
  | "denied"
  | "host_offline"
  | "host_timeout"
  | "frame_too_large"
  | "quota_exhausted"
  | "too_many_sessions"
  | "too_many_registrations"
  | "rate_limited"
  | "relay_busy"
  | "replaced"
  | "peer_closed"
  | "idle";

/** HTTP status for rejections before the WebSocket upgrade. */
export const HTTP_STATUS: Partial<Record<Reason, number>> = {
  bad_request: 400,
  unauthorized: 401,
  media_disabled: 403,
  host_offline: 404,
  quota_exhausted: 429,
  too_many_sessions: 429,
  too_many_registrations: 429,
  relay_busy: 503,
};

/** WebSocket close code for rejections after the upgrade. */
export const CLOSE_CODE = {
  unauthorized: 4401,
  denied: 4403,
  host_offline: 4404,
  host_timeout: 4408,
  frame_too_large: 4413,
  quota_exhausted: 4429,
  rate_limited: 4429,
  relay_busy: 4503,
  replaced: 4000,
  peer_closed: 1000,
  idle: 1000,
  /** Standard "policy violation": frames sent before `ready`. The protocol defines no code for this. */
  early_frame: 1008,
} as const;

export type Channel = "control" | "media";

export const CHANNELS: readonly Channel[] = ["control", "media"];

export function isChannel(v: unknown): v is Channel {
  return v === "control" || v === "media";
}

/** Protocol constants (PROTOCOL §4, §5.3). */
export const LIMITS = {
  maxBinaryFrame: 65_536,
  maxTextFrame: 4_096,
  /** Challenge answer window and "connector must open the data connection" window. */
  challengeMs: 10_000,
  pairMs: 10_000,
  /** Metering write-back interval (spec R1b §3). */
  flushMs: 30_000,
  /** How long a cached global-budget state may be used when a new session starts. */
  budgetCacheMs: 10_000,
  rateWindowS: 10,
  rateSlack: 1.5,
} as const;

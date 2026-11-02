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
  /** Standard "policy violation" (PROTOCOL §9): a frame sent before `ready`. */
  early_frame: 1008,
  /** Standard "policy violation" (PROTOCOL §9): text beyond the handshake, or any message but `ping` on an authenticated control connection. */
  unexpected_text: 1008,
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
  /**
   * A socket not heard from (no `ping`, no binary frame) for this long is treated as gone (PROTOCOL
   * §5.4). Released apps and connectors ping every 30 s on every connection. The computer's side (its
   * control connection and its end of each session) gets 75 s: one lost ping and slack. The app's end
   * of a session gets 150 s, because a phone whose screen is locked stops pinging while it sleeps, and
   * its sessions should still be there when it is unlocked a couple of minutes later.
   */
  livenessHostMs: 75_000,
  livenessAppMs: 150_000,
  rateWindowS: 10,
  rateSlack: 1.5,
  /**
   * Frames, text or binary, one direction of a session may send per rate window. The busiest real
   * senders: a connector relaying output that trickles in measures 80-90 frames/s (its 10 ms
   * coalescing), and the app, which does not coalesce, sends one frame per SSH packet - a touch drag
   * on the remote screen is one or two packets per touch event, up to about 240/s on a 120 Hz phone.
   * 6000 per 10 s (600/s sustained over the window) leaves 2.5x headroom over that.
   */
  maxFramesPerWindow: 6_000,
  /**
   * Text frames other than `ping` one side of a session may send: the end-to-end handshake needs one
   * from the app (auth) and two from the connector (challenge, then ok or denied).
   */
  sessionTexts: 2,
} as const;

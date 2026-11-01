// Admission checks for new sessions, in the order of PROTOCOL §6.4 (the access-code and
// channel-validity checks happen earlier, while routing).

import type { BudgetState } from "./budget.ts";
import { HTTP_STATUS, type Channel, type Reason } from "./codes.ts";
import type { Policy } from "./config.ts";
import { quotaStatus, type Counted } from "./ledger.ts";
import { nextUtcMidnight, secondsUntil } from "./time.ts";

export interface Rejection {
  status: number;
  reason: Reason;
  retryAfter?: number;
}

export interface SessionRequest {
  ch: Channel;
  hostOnline: boolean;
  policy: Policy;
  budget: BudgetState;
  counted: Counted;
  /** Sessions currently open on the host, paired or not. */
  sessions: number;
  now: number;
}

function reject(reason: Reason, retryAfter?: number): Rejection {
  return { status: HTTP_STATUS[reason] ?? 400, reason, ...(retryAfter !== undefined ? { retryAfter } : {}) };
}

export function checkNewSession(r: SessionRequest): Rejection | null {
  if (!r.hostOnline) return reject("host_offline", 30);
  if (r.ch === "media" && !r.policy.media.allowed) return reject("media_disabled");
  if (r.budget === "closed") return reject("relay_busy", secondsUntil(nextUtcMidnight(r.now), r.now));
  if (r.ch === "media" && r.budget === "media_paused") return reject("relay_busy", secondsUntil(nextUtcMidnight(r.now), r.now));
  const q = quotaStatus(r.policy, r.counted, r.ch, r.now);
  if (q.exhausted) return reject("quota_exhausted", secondsUntil(q.resetAt, r.now));
  const max = r.policy.limits.sessionsPerHost;
  if (max > 0 && r.sessions >= max) return reject("too_many_sessions");
  return null;
}

/**
 * Why a running media session must be closed, if it must (PROTOCOL §6.4, last paragraph).
 * Control sessions are never cut short by quota or budget.
 */
export function mediaSessionVerdict(
  policy: Policy,
  budget: BudgetState,
  c: Counted,
  now: number,
): { code: "relay_busy" | "quota_exhausted" } | null {
  if (budget === "closed" || budget === "media_paused") return { code: "relay_busy" };
  if (quotaStatus(policy, c, "media", now).exhausted) return { code: "quota_exhausted" };
  return null;
}

// Relay-side supervision of the senders' own pacing (PROTOCOL §6.2): one direction of one session
// may not forward more than 1.5x its token-bucket allowance in any 10 second window.

import { LIMITS } from "./codes.ts";

export function allowanceBytes(pace: { kbps: number; burstKb: number }): number {
  return Math.round(LIMITS.rateSlack * ((pace.kbps * 10 / 8) * 1000 + pace.burstKb * 1024));
}

/** 10 one-second buckets. */
export class RateWindow {
  private secs = new Array<number>(LIMITS.rateWindowS).fill(-1);
  private bytes = new Array<number>(LIMITS.rateWindowS).fill(0);

  total(nowMs: number): number {
    const sec = Math.floor(nowMs / 1000);
    let sum = 0;
    for (let i = 0; i < this.secs.length; i++) {
      if (this.secs[i]! > sec - LIMITS.rateWindowS) sum += this.bytes[i]!;
    }
    return sum;
  }

  add(nowMs: number, n: number): void {
    const sec = Math.floor(nowMs / 1000);
    const i = sec % LIMITS.rateWindowS;
    if (this.secs[i] !== sec) {
      this.secs[i] = sec;
      this.bytes[i] = 0;
    }
    this.bytes[i]! += n;
  }

  /** Would forwarding `n` more bytes now exceed `limit` bytes in the window? A limit of 0 never trips. */
  wouldExceed(nowMs: number, n: number, limit: number): boolean {
    return limit > 0 && this.total(nowMs) + n > limit;
  }
}

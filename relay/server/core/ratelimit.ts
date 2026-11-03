// Relay-side supervision of the senders' own pacing (PROTOCOL §6.2): one direction of one session
// may not forward more than 1.5x its token-bucket allowance in any 10 second window.

import { LIMITS } from "./codes.ts";

export function allowanceBytes(pace: { kbps: number; burstKb: number }): number {
  return Math.round(LIMITS.rateSlack * ((pace.kbps * 10 / 8) * 1000 + pace.burstKb * 1024));
}

/** 10 one-second buckets of bytes and frames. */
export class RateWindow {
  private secs = new Array<number>(LIMITS.rateWindowS).fill(-1);
  private bytes = new Array<number>(LIMITS.rateWindowS).fill(0);
  private frames = new Array<number>(LIMITS.rateWindowS).fill(0);

  total(nowMs: number): number {
    return this.sum(nowMs, this.bytes);
  }

  frameCount(nowMs: number): number {
    return this.sum(nowMs, this.frames);
  }

  private sum(nowMs: number, of: number[]): number {
    const sec = Math.floor(nowMs / 1000);
    let sum = 0;
    for (let i = 0; i < this.secs.length; i++) {
      if (this.secs[i]! > sec - LIMITS.rateWindowS) sum += of[i]!;
    }
    return sum;
  }

  /** Record one frame of `n` bytes. */
  add(nowMs: number, n: number): void {
    const sec = Math.floor(nowMs / 1000);
    const i = sec % LIMITS.rateWindowS;
    if (this.secs[i] !== sec) {
      this.secs[i] = sec;
      this.bytes[i] = 0;
      this.frames[i] = 0;
    }
    this.bytes[i]! += n;
    this.frames[i]! += 1;
  }

  /**
   * Would one more frame of `n` bytes now exceed `limit` bytes, or `maxFrames` frames, in the window?
   * A limit of 0 never trips.
   */
  wouldExceed(nowMs: number, n: number, limit: number, maxFrames = 0): boolean {
    if (limit > 0 && this.total(nowMs) + n > limit) return true;
    return maxFrames > 0 && this.frameCount(nowMs) + 1 > maxFrames;
  }
}

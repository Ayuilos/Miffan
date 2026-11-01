// UTC day/month helpers. The protocol meters by UTC day and month (PROTOCOL §6.3).

export const MIB = 1024 * 1024;

export function utcDay(ms: number): string {
  return new Date(ms).toISOString().slice(0, 10);
}

export function utcMonth(ms: number): string {
  return new Date(ms).toISOString().slice(0, 7);
}

export function nextUtcMidnight(ms: number): number {
  const d = new Date(ms);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate() + 1);
}

export function nextUtcMonth(ms: number): number {
  const d = new Date(ms);
  return Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 1);
}

export function isoNoMillis(ms: number): string {
  return new Date(ms).toISOString().replace(/\.\d{3}Z$/, "Z");
}

/** Whole seconds until `at`, at least 1. */
export function secondsUntil(at: number, now: number): number {
  return Math.max(1, Math.ceil((at - now) / 1000));
}

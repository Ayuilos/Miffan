// Test configurations. Each is run as its own server per implementation, with small quotas, small
// rate limits and a short idle timeout so every rule can be reached in seconds.

export const ACCESS_TOKEN = "test-access-token";
export const ADMIN_TOKEN = "test-admin-token";

export const profiles = {
  // Open relay, media allowed: sessions, forwarding, metering, quotas, rate limiting, idle, registrations.
  A: {
    accessMode: "open",
    registrationsPerIpPerDay: 60,
    policy: {
      control: { kbps: 100, burstKb: 32 },
      media: { allowed: true, kbps: 200, burstKb: 64 },
      limits: { sessionsPerHost: 2, idleTimeoutS: 3 },
      quotas: { hostDailyMb: 0.25, hostDailyActiveMin: 0, hostMonthlyControlMb: 0, hostMonthlyMediaMb: 0 },
    },
    edges: ["104.17.5.5", "104.19.8.8"],
  },
  // Access code required, media switched off.
  B: {
    accessMode: "token",
    policy: {
      media: { allowed: false },
      limits: { sessionsPerHost: 4, idleTimeoutS: 60 },
    },
  },
  // Global budget: 0.5 MiB per day, media pauses below 50% remaining.
  C: {
    accessMode: "open",
    registrationsPerIpPerDay: 60,
    policy: {
      control: { kbps: 10000, burstKb: 8192 },
      media: { allowed: true, kbps: 10000, burstKb: 8192 },
      limits: { sessionsPerHost: 4, idleTimeoutS: 60 },
      quotas: { hostDailyMb: 0, hostDailyActiveMin: 0, hostMonthlyControlMb: 0, hostMonthlyMediaMb: 0 },
      budget: { globalDailyMb: 0.5, globalDailyActiveHours: 0, mediaReserve: 0.5 },
    },
  },
  // Long idle timeout and no budgets: only liveness (PROTOCOL §5.4) can end a session here.
  D: {
    accessMode: "open",
    registrationsPerIpPerDay: 60,
    policy: {
      limits: { sessionsPerHost: 4, idleTimeoutS: 600 },
      quotas: { hostDailyMb: 0, hostDailyActiveMin: 0, hostMonthlyControlMb: 0, hostMonthlyMediaMb: 0 },
    },
  },
} as const;

export type ProfileName = keyof typeof profiles;

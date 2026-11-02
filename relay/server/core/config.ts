// Relay configuration: one JSON shape for both adapters (R1b spec §2). Defaults are the
// example values of RELAY_PLAN §5.1; the media burst is the PROTOCOL §6.1 example.

export interface Policy {
  control: { kbps: number; burstKb: number };
  media: { allowed: boolean; kbps: number; burstKb: number };
  limits: { sessionsPerHost: number; idleTimeoutS: number };
  quotas: {
    hostDailyMb: number;
    hostDailyActiveMin: number;
    hostMonthlyControlMb: number;
    hostMonthlyMediaMb: number;
  };
  budget: { globalDailyMb: number; globalDailyActiveHours: number; globalDailyRequests: number; mediaReserve: number };
}

export type AccessMode = "open" | "token";

export interface RelayConfig {
  accessMode: AccessMode;
  registrationsPerIpPerDay: number;
  policy: Policy;
  edges: string[];
}

/** The policy object as served in /v1/info, hello and ready (PROTOCOL §6.1). */
export interface PublicPolicy extends Policy {
  access: { mode: AccessMode; registrationsPerIpPerDay: number };
}

export const DEFAULT_CONFIG: RelayConfig = {
  accessMode: "open",
  registrationsPerIpPerDay: 5,
  policy: {
    control: { kbps: 1000, burstKb: 2048 },
    media: { allowed: true, kbps: 4000, burstKb: 4096 },
    limits: { sessionsPerHost: 4, idleTimeoutS: 300 },
    quotas: {
      hostDailyMb: 500,
      hostDailyActiveMin: 0,
      hostMonthlyControlMb: 2048,
      hostMonthlyMediaMb: 10240,
    },
    budget: { globalDailyMb: 0, globalDailyActiveHours: 0, globalDailyRequests: 0, mediaReserve: 0.3 },
  },
  edges: [],
};

export class ConfigError extends Error {}

function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

function num(errors: string[], path: string, v: unknown, fallback: number, max = Infinity): number {
  if (v === undefined) return fallback;
  if (typeof v !== "number" || !Number.isFinite(v) || v < 0 || v > max) {
    errors.push(`${path} must be a number between 0 and ${max === Infinity ? "infinity" : max}`);
    return fallback;
  }
  return v;
}

function bool(errors: string[], path: string, v: unknown, fallback: boolean): boolean {
  if (v === undefined) return fallback;
  if (typeof v !== "boolean") {
    errors.push(`${path} must be true or false`);
    return fallback;
  }
  return v;
}

function section(errors: string[], path: string, v: unknown): Record<string, unknown> {
  if (v === undefined) return {};
  if (!isObject(v)) {
    errors.push(`${path} must be an object`);
    return {};
  }
  return v;
}

/** Parse a JSON string or object; unspecified fields take their defaults. Throws ConfigError listing every problem. */
export function parseConfig(raw: unknown): RelayConfig {
  let input: unknown = raw;
  if (raw === undefined || raw === null || raw === "") input = {};
  if (typeof raw === "string" && raw !== "") {
    try {
      input = JSON.parse(raw);
    } catch (e) {
      throw new ConfigError(`config is not valid JSON: ${(e as Error).message}`);
    }
  }
  if (!isObject(input)) throw new ConfigError("config must be a JSON object");
  const d = DEFAULT_CONFIG;
  const errors: string[] = [];

  let accessMode = d.accessMode;
  if (input.accessMode !== undefined) {
    if (input.accessMode === "open" || input.accessMode === "token") accessMode = input.accessMode;
    else errors.push('accessMode must be "open" or "token"');
  }

  const p = section(errors, "policy", input.policy);
  const control = section(errors, "policy.control", p.control);
  const media = section(errors, "policy.media", p.media);
  const limits = section(errors, "policy.limits", p.limits);
  const quotas = section(errors, "policy.quotas", p.quotas);
  const budget = section(errors, "policy.budget", p.budget);

  const edges: string[] = [];
  if (input.edges !== undefined) {
    if (!Array.isArray(input.edges) || input.edges.some((e) => typeof e !== "string")) {
      errors.push("edges must be an array of IP strings");
    } else {
      edges.push(...(input.edges as string[]));
    }
  }

  const cfg: RelayConfig = {
    accessMode,
    registrationsPerIpPerDay: num(errors, "registrationsPerIpPerDay", input.registrationsPerIpPerDay, d.registrationsPerIpPerDay),
    policy: {
      control: {
        kbps: num(errors, "policy.control.kbps", control.kbps, d.policy.control.kbps),
        burstKb: num(errors, "policy.control.burstKb", control.burstKb, d.policy.control.burstKb),
      },
      media: {
        allowed: bool(errors, "policy.media.allowed", media.allowed, d.policy.media.allowed),
        kbps: num(errors, "policy.media.kbps", media.kbps, d.policy.media.kbps),
        burstKb: num(errors, "policy.media.burstKb", media.burstKb, d.policy.media.burstKb),
      },
      limits: {
        sessionsPerHost: num(errors, "policy.limits.sessionsPerHost", limits.sessionsPerHost, d.policy.limits.sessionsPerHost),
        idleTimeoutS: num(errors, "policy.limits.idleTimeoutS", limits.idleTimeoutS, d.policy.limits.idleTimeoutS),
      },
      quotas: {
        hostDailyMb: num(errors, "policy.quotas.hostDailyMb", quotas.hostDailyMb, d.policy.quotas.hostDailyMb),
        hostDailyActiveMin: num(errors, "policy.quotas.hostDailyActiveMin", quotas.hostDailyActiveMin, d.policy.quotas.hostDailyActiveMin),
        hostMonthlyControlMb: num(errors, "policy.quotas.hostMonthlyControlMb", quotas.hostMonthlyControlMb, d.policy.quotas.hostMonthlyControlMb),
        hostMonthlyMediaMb: num(errors, "policy.quotas.hostMonthlyMediaMb", quotas.hostMonthlyMediaMb, d.policy.quotas.hostMonthlyMediaMb),
      },
      budget: {
        globalDailyMb: num(errors, "policy.budget.globalDailyMb", budget.globalDailyMb, d.policy.budget.globalDailyMb),
        globalDailyActiveHours: num(errors, "policy.budget.globalDailyActiveHours", budget.globalDailyActiveHours, d.policy.budget.globalDailyActiveHours),
        globalDailyRequests: num(errors, "policy.budget.globalDailyRequests", budget.globalDailyRequests, d.policy.budget.globalDailyRequests),
        mediaReserve: num(errors, "policy.budget.mediaReserve", budget.mediaReserve, d.policy.budget.mediaReserve, 1),
      },
    },
    edges,
  };
  if (errors.length) throw new ConfigError("invalid config: " + errors.join("; "));
  return cfg;
}

export function publicPolicy(cfg: RelayConfig): PublicPolicy {
  return {
    access: { mode: cfg.accessMode, registrationsPerIpPerDay: cfg.registrationsPerIpPerDay },
    control: cfg.policy.control,
    media: cfg.policy.media,
    limits: cfg.policy.limits,
    quotas: cfg.policy.quotas,
    budget: cfg.policy.budget,
  };
}

export const RELAY_VERSION = "1.0.0";

export type Impl = "worker" | "bun";

export function infoDocument(cfg: RelayConfig, impl: Impl) {
  return {
    v: 1,
    impl,
    version: RELAY_VERSION,
    access: cfg.accessMode,
    capabilities: { dgram: "none" as const },
    policy: publicPolicy(cfg),
    edges: cfg.edges,
  };
}

export function paceFor(cfg: RelayConfig, ch: "control" | "media"): { kbps: number; burstKb: number } {
  const p = ch === "media" ? cfg.policy.media : cfg.policy.control;
  return { kbps: p.kbps, burstKb: p.burstKb };
}

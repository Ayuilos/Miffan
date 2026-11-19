// Entry point for the VPS build: bun run bun/server.ts, or the single file from `bun build --compile`.
//
//   RELAY_CONFIG_FILE  path of the JSON config (default ./relay.config.json; missing default file = defaults)
//   RELAY_DB           SQLite file for metering (default ./relay.sqlite; ":memory:" for tests)
//   ACCESS_TOKEN       access code, required when accessMode is "token"
//   ADMIN_TOKEN        enables GET /v1/admin/stats
//   PORT / HOST        listen port (default 8787) and address (default 127.0.0.1; the Docker image sets 0.0.0.0)
//   TRUSTED_IP_HEADER  header carrying the client IP behind a reverse proxy, e.g. x-forwarded-for
//   UDP_PORT           optional UDP port for direct `media-dgram` forwarding (0 = disabled, e.g. 8444)
//   UDP_HOST           optional UDP socket bind address (defaults to dual-stack "::" / "0.0.0.0")
//   UDP_PUBLIC_HOST    optional public hostname advertised in capabilities.udpHost
//   UDP_PUBLIC_PORT    optional public UDP port advertised in capabilities.udpPort (defaults to UDP_PORT)

import { Database } from "bun:sqlite";
import { existsSync, readFileSync } from "node:fs";
import { ConfigError, parseConfig } from "../core/config.ts";
import { createBunRelay } from "./relay.ts";
import { BunUdpRelay } from "./udp.ts";

function fail(msg: string): never {
  console.error(JSON.stringify({ ev: "fatal", msg }));
  process.exit(1);
}

const env = process.env;
const explicit = env.RELAY_CONFIG_FILE;
const file = explicit ?? "./relay.config.json";
let raw = "";
if (existsSync(file)) raw = readFileSync(file, "utf8");
else if (explicit) fail(`RELAY_CONFIG_FILE ${explicit} does not exist`);

let config;
try {
  config = parseConfig(raw);
} catch (e) {
  fail(e instanceof ConfigError ? e.message : String(e));
}
if (config.accessMode === "token" && !env.ACCESS_TOKEN) fail('accessMode is "token" but ACCESS_TOKEN is not set');

const udpPort = env.UDP_PORT ? Number(env.UDP_PORT) : 0;
if (!Number.isInteger(udpPort) || udpPort < 0 || udpPort > 65535) {
  fail(`invalid UDP_PORT: ${env.UDP_PORT}`);
}
const udpPublicPort = env.UDP_PUBLIC_PORT ? Number(env.UDP_PUBLIC_PORT) : undefined;
if (udpPublicPort !== undefined && (!Number.isInteger(udpPublicPort) || udpPublicPort < 1 || udpPublicPort > 65535)) {
  fail(`invalid UDP_PUBLIC_PORT: ${env.UDP_PUBLIC_PORT}`);
}

const udp =
  udpPort > 0
    ? await BunUdpRelay.create({
        port: udpPort,
        hostname: env.UDP_HOST || undefined,
        publicHost: env.UDP_PUBLIC_HOST || undefined,
        publicPort: udpPublicPort,
      })
    : undefined;

const db = new Database(env.RELAY_DB ?? "./relay.sqlite", { create: true });
db.exec("PRAGMA journal_mode = WAL; PRAGMA synchronous = NORMAL;");

const relay = createBunRelay({
  config,
  secrets: { accessToken: env.ACCESS_TOKEN, adminToken: env.ADMIN_TOKEN },
  db,
  trustedIpHeader: env.TRUSTED_IP_HEADER?.toLowerCase() || undefined,
  udp,
});

const server = Bun.serve({
  port: Number(env.PORT ?? 8787),
  hostname: env.HOST ?? "127.0.0.1",
  fetch: relay.fetch,
  websocket: relay.websocket,
});

console.log(
  JSON.stringify({
    ev: "listening",
    url: String(server.url),
    access: config.accessMode,
    ...(udp ? { udpPort: udp.port, udpPublicPort: udp.publicPort, ...(udp.publicHost ? { udpHost: udp.publicHost } : {}) } : {}),
  }),
);

for (const sig of ["SIGINT", "SIGTERM"] as const) {
  process.on(sig, async () => {
    udp?.close();
    await relay.flushAll();
    server.stop(true);
    db.close();
    process.exit(0);
  });
}

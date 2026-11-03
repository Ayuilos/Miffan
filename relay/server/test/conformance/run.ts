// Conformance runner: starts `wrangler dev --local` and the Bun server for each test profile, runs the
// same black-box suite against each implementation, and shuts everything down.
//
//   bun run conformance                 both implementations
//   bun run conformance -- --impl bun   only one (bun | worker)
//   bun run conformance -- --keep-logs  print the server log directory even on success

import { spawn, type Subprocess } from "bun";
import { mkdirSync, mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, resolve } from "node:path";
import { ACCESS_TOKEN, ADMIN_TOKEN, profiles, type ProfileName } from "./profiles.ts";

const root = resolve(import.meta.dir, "../..");
const args = process.argv.slice(2);
const implArg = args.includes("--impl") ? args[args.indexOf("--impl") + 1] : "both";
const impls = implArg === "both" ? (["bun", "worker"] as const) : ([implArg] as ("bun" | "worker")[]);
const logRoot = mkdtempSync(join(tmpdir(), "relay-conformance-"));

async function freePort(): Promise<number> {
  const s = Bun.serve({ port: 0, fetch: () => new Response("") });
  const port = s.port!;
  await s.stop(true);
  return port;
}

interface Running {
  name: ProfileName;
  url: string;
  proc: Subprocess;
}

async function waitReady(url: string, proc: Subprocess, what: string): Promise<void> {
  const deadline = Date.now() + 90_000;
  while (Date.now() < deadline) {
    if (proc.exitCode !== null) throw new Error(`${what} exited early (code ${proc.exitCode}); see ${logRoot}`);
    try {
      const r = await fetch(url + "/v1/info");
      if (r.ok) return;
    } catch {
      // not up yet
    }
    await Bun.sleep(250);
  }
  throw new Error(`${what} did not become ready; see ${logRoot}`);
}

async function startBun(name: ProfileName): Promise<Running> {
  const dir = join(logRoot, `bun-${name}`);
  mkdirSync(dir);
  const cfgFile = join(dir, "relay.config.json");
  writeFileSync(cfgFile, JSON.stringify(profiles[name]));
  const port = await freePort();
  const log = Bun.file(join(dir, "server.log"));
  const proc = spawn(["bun", "run", join(root, "bun/server.ts")], {
    cwd: dir,
    env: {
      ...process.env,
      PORT: String(port),
      HOST: "127.0.0.1",
      RELAY_CONFIG_FILE: cfgFile,
      RELAY_DB: ":memory:",
      ACCESS_TOKEN,
      ADMIN_TOKEN,
    },
    stdout: log,
    stderr: log,
  });
  const url = `http://127.0.0.1:${port}`;
  await waitReady(url, proc, `bun/${name}`);
  return { name, url, proc };
}

async function startWorker(name: ProfileName): Promise<Running> {
  const dir = join(logRoot, `worker-${name}`);
  mkdirSync(dir);
  // The same bindings and migrations as worker/wrangler.toml, with the profile's config as the variable.
  writeFileSync(
    join(dir, "wrangler.toml"),
    `name = "relay-test-${name.toLowerCase()}"
main = "${join(root, "worker/index.ts")}"
compatibility_date = "2026-05-01"

[vars]
RELAY_CONFIG = '''${JSON.stringify(profiles[name])}'''
ACCESS_TOKEN = "${ACCESS_TOKEN}"
ADMIN_TOKEN = "${ADMIN_TOKEN}"

[[durable_objects.bindings]]
name = "RELAY"
class_name = "HostRelay"

[[durable_objects.bindings]]
name = "BUDGET"
class_name = "Budget"

[[migrations]]
tag = "v1"
new_sqlite_classes = ["HostRelay"]

[[migrations]]
tag = "v2"
new_sqlite_classes = ["Budget"]
`,
  );
  const [port, inspector] = [await freePort(), await freePort()];
  const log = Bun.file(join(dir, "server.log"));
  const proc = spawn(
    [
      join(root, "node_modules/.bin/wrangler"),
      "dev",
      "--local",
      "--ip",
      "127.0.0.1",
      "--port",
      String(port),
      "--inspector-port",
      String(inspector),
      "--persist-to",
      join(dir, "state"),
      "-c",
      join(dir, "wrangler.toml"),
    ],
    {
      cwd: dir,
      env: { ...process.env, WRANGLER_SEND_METRICS: "false", CI: "1", NO_COLOR: "1", WRANGLER_LOG_PATH: join(dir, "wrangler-logs") },
      stdout: log,
      stderr: log,
    },
  );
  const url = `http://127.0.0.1:${port}`;
  await waitReady(url, proc, `worker/${name}`);
  return { name, url, proc };
}

async function runImpl(impl: "bun" | "worker"): Promise<number> {
  console.log(`\n=== ${impl}: starting servers (logs in ${logRoot}) ===`);
  const start = impl === "bun" ? startBun : startWorker;
  const names: ProfileName[] = ["A", "B", "C", "D"];
  const servers = await Promise.all(names.map(start));
  try {
    const env: Record<string, string> = { ...process.env, RELAY_IMPL: impl } as Record<string, string>;
    for (const s of servers) env[`RELAY_${s.name}`] = s.url;
    console.log(`=== ${impl}: running suite ===`);
    // The suite is split in two files so the slow cases overlap with the rest.
    const runs = ["relay.test.ts", "slow.test.ts"].map((f) =>
      spawn(["bun", "test", join(root, "test/conformance", f)], { cwd: root, env, stdout: "inherit", stderr: "inherit" }),
    );
    const codes = await Promise.all(runs.map((r) => r.exited));
    // Phase 2: the registration test exhausts the per-IP allowance, so it must not overlap the others.
    codes.push(await spawn(["bun", "test", join(root, "test/conformance/registrations.test.ts")], { cwd: root, env, stdout: "inherit", stderr: "inherit" }).exited);
    return codes.some((c) => c !== 0) ? 1 : 0;
  } finally {
    for (const s of servers) s.proc.kill();
    await Promise.all(servers.map((s) => s.proc.exited));
  }
}

let failed = 0;
for (const impl of impls) {
  try {
    const code = await runImpl(impl);
    console.log(`=== ${impl}: ${code === 0 ? "PASS" : "FAIL"} ===`);
    failed += code;
  } catch (e) {
    console.error(`=== ${impl}: ERROR ${(e as Error).message} ===`);
    failed += 1;
  }
}
if (failed) console.error(`logs: ${logRoot}`);
else if (args.includes("--keep-logs")) console.log(`logs: ${logRoot}`);
process.exit(failed ? 1 : 0);

// Black-box conformance tests: the same cases run against `wrangler dev --local` and the Bun server.
// Servers are started by run.ts, which passes their URLs in the environment.

import { describe, expect, test } from "bun:test";
import { b64uDecode } from "../../core/crypto.ts";
import {
  Conn,
  HostControl,
  bytes,
  connectApp,
  getJson,
  openSession,
  sleep,
  upgradeAttempt,
  wsUrl,
  type ReadyMsg,
} from "../client/client.ts";
import { newDeviceKey, newHostKey, verifySession } from "../client/keys.ts";
import { ADMIN_TOKEN, ACCESS_TOKEN } from "./profiles.ts";

const A = process.env.RELAY_A!;

// Runs after the other suites: it uses up this server's per-IP registration allowance.
// ---- registrations (last: it uses up the per-IP allowance of this server) ------------------

describe("registrations", () => {
  test("too_many_registrations once the source IP has registered too many new hosts", async () => {
    const known = await HostControl.connect(A);
    known.close();
    let refused: { status: number; json: any } | undefined;
    for (let i = 0; i < 80 && !refused; i++) {
      const key = await newHostKey();
      const r = await upgradeAttempt(wsUrl(A, `/v1/host?pub=${key.pubB64u}`));
      if (r.status === 429) refused = r as never;
      else expect(r.status).toBe(101);
    }
    expect(refused).toBeDefined();
    expect(refused!.json.reason).toBe("too_many_registrations");
    expect(refused!.json.retryAfter).toBeGreaterThan(0);
    // A host that is already registered is not affected.
    const back = await HostControl.connect(A, known.key);
    expect(back.hello.hostId).toBe(known.hostId);
    back.close();
  }, 30_000);
});

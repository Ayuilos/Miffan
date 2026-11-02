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
const today = () => new Date().toISOString().slice(0, 10);
const closeCodes = (c: { code: number; reason: string }) => `${c.code} ${c.reason}`;

// Cases that need real waiting (protocol timeouts of 10 s, the 30 s metering write-back).

describe("host authentication (slow)", () => {
  test("no answer within 10 s closes with 4401", async () => {
    const key = await newHostKey();
    const { conn } = await HostControl.connectRaw(A, key);
    const t0 = Date.now();
    const c = await conn.waitClosed(20_000);
    expect(c.code).toBe(4401);
    expect(Date.now() - t0).toBeGreaterThan(9000);
  }, 25_000);

});

describe("sessions (slow)", () => {
  test("host_timeout when the connector never opens the data connection", async () => {
    const host = await HostControl.connect(A);
    const { conn } = await connectApp(A, host.hostId, "control");
    const open = await host.nextOpen();
    expect(open.sid).toMatch(/^[a-f0-9]{32}$/);
    const t0 = Date.now();
    const c = await conn.waitClosed(20_000);
    expect(closeCodes(c)).toBe("4408 host_timeout");
    expect(Date.now() - t0).toBeGreaterThan(9000);
    expect(Date.now() - t0).toBeLessThan(15_000);
    // The late data connection finds nothing to attach to.
    const late = await upgradeAttempt(wsUrl(A, `/v1/host/${host.hostId}/session/${open.sid}`));
    expect(late.status).toBe(404);
    host.close();
  }, 25_000);

});

describe("liveness (slow)", () => {
  test("silent connections are treated as gone after 75 s; pinging ones are not", async () => {
    const D = process.env.RELAY_D!; // idle timeout 600 s, so only liveness can end the session below
    const silent = await HostControl.connect(A);
    const pinging = await HostControl.connect(A);
    const host = await HostControl.connect(D);
    const s = await openSession(host);
    const t0 = Date.now();
    // The app pings and keeps sending; the connector's end of the session has vanished without
    // closing (the computer's side gets 75 s, the app's side 150 s - the latter is covered by the unit tests).
    const timer = setInterval(() => {
      for (const c of [pinging.conn, host.conn, s.app]) {
        try {
          c.send("ping");
        } catch {
          // closed
        }
      }
      try {
        s.app.send(bytes(10));
      } catch {
        // closed
      }
    }, 15_000);
    try {
      const closed = await s.app.waitClosed(110_000);
      expect(closeCodes(closed)).toBe("1000 peer_closed");
      await sleep(Math.max(0, 80_000 - (Date.now() - t0)));
      const gone = await upgradeAttempt(wsUrl(A, `/v1/connect/${silent.hostId}?ch=control`));
      expect(gone.status).toBe(404);
      expect(gone.json?.reason).toBe("host_offline");
      const { conn } = await connectApp(A, pinging.hostId, "control");
      const open = await pinging.nextOpen();
      expect(open.sid).toMatch(/^[a-f0-9]{32}$/);
      conn.close();
    } finally {
      clearInterval(timer);
    }
    silent.close();
    pinging.close();
    host.close();
  }, 130_000);
});

// ---- long-running behaviour ----------------------------------------------------------------

describe("running sessions", () => {
  test("a media session is cut with 4429 when the quota runs out, and the connector is told", async () => {
    const host = await HostControl.connect(A); // hostDailyMb = 0.25 MiB
    const s = await openSession(host, "media");
    // The first frame after a quiet spell is written back at once; wait for that, then the rest has to
    // wait for the next write-back, 30 s later.
    s.app.send(bytes(100));
    await s.data.nextBinary();
    await sleep(600);
    const t0 = Date.now();
    for (let i = 0; i < 5; i++) s.app.send(bytes(65_536)); // 327,680 > 262,144
    for (let i = 0; i < 5; i++) await s.data.nextBinary();
    // Quotas are enforced at metering write-back, at most every 30 s. Keep the session from idling out meanwhile.
    const keepalive = setInterval(() => {
      try {
        s.data.send(bytes(1));
      } catch {
        // closed
      }
    }, 1500);
    try {
      const c = await s.app.waitClosed(50_000);
      expect(closeCodes(c)).toBe("4429 quota_exhausted");
      expect(closeCodes(await s.data.waitClosed())).toBe("4429 quota_exhausted");
      expect(Date.now() - t0).toBeGreaterThan(25_000);
    } finally {
      clearInterval(keepalive);
    }
    await sleep(500);
    const pushes = host.conn.messages.filter((m) => typeof m === "string").map((m) => JSON.parse(m as string)).filter((m) => m.t === "usage");
    expect(pushes.length).toBeGreaterThan(0);
    expect(pushes.at(-1).usage.dayMb).toBeGreaterThanOrEqual(0.25);
    host.close();
  }, 70_000);
});


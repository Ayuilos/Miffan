import { Database } from "bun:sqlite";
import { describe, expect, test } from "bun:test";
import { parseConfig } from "../../core/config.ts";
import { b64uDecode } from "../../core/crypto.ts";
import { Engine } from "../../core/engine.ts";
import { HostLedger } from "../../core/ledger.ts";
import { createBunRelay, sqlite } from "../../bun/relay.ts";
import { BunUdpRelay } from "../../bun/udp.ts";
import { HostControl, openSession } from "../client/client.ts";
import { newHostKey, type HostKey } from "../client/keys.ts";
import { FakeSock, World } from "./fake.ts";

function withDgram(w: World, port = 8444) {
  const registered = new Map<
    string,
    { ct: Uint8Array; ht: Uint8Array; onForward: (role: "c" | "h", bytes: number) => boolean }
  >();
  const platform = {
    ...w.platform(),
    stream: true,
    dgram: {
      mode: "udp" as const,
      port,
      register(
        sid: string,
        clientToken: Uint8Array,
        hostToken: Uint8Array,
        onForward: (role: "c" | "h", bytes: number) => boolean,
      ) {
        registered.set(sid, { ct: clientToken, ht: hostToken, onForward });
      },
      unregister(sid: string) {
        registered.delete(sid);
      },
    },
  };
  return { platform, registered };
}

async function connectHost(
  w: World,
  engine: Engine,
  key: HostKey,
  dgram = true,
  host = "relay.test",
): Promise<FakeSock> {
  const res = await engine.start({ kind: "host", hostId: key.hostId, pub: key.pub }, w.requestCtx(host));
  expect(res.status).toBe(200);
  const s = w.socks.at(-1)!;
  const nonce = s.json(0).nonce;
  await engine.onMessage(s, JSON.stringify({ t: "auth", sig: await key.signChallenge(host, nonce), ...(dgram ? { dgram: true } : {}) }));
  return s;
}

describe("engine media-dgram", () => {
  test("checks dgram_disabled and dgram_unsupported before budget/quota", async () => {
    const key = await newHostKey();
    // 1. Relay has no dgram platform -> dgram_disabled even if connector sent dgram:true
    {
      const w = new World();
      const engine = new Engine(w.platform(), parseConfig({}));
      await connectHost(w, engine, key, true);
      const r = await engine.start({ kind: "connect", hostId: key.hostId, ch: "media-dgram" }, w.requestCtx());
      expect(r.status).toBe(403);
      expect((await r.json()).reason).toBe("dgram_disabled");
    }

    // 2. Relay has dgram platform, but connector didn't advertise dgram:true -> dgram_unsupported
    {
      const w = new World();
      const { platform } = withDgram(w);
      const engine = new Engine(platform, parseConfig({}));
      await connectHost(w, engine, key, false);
      const r = await engine.start({ kind: "connect", hostId: key.hostId, ch: "media-dgram" }, w.requestCtx());
      expect(r.status).toBe(403);
      expect((await r.json()).reason).toBe("dgram_unsupported");
    }

    // 3. media.allowed = false takes precedence -> media_disabled
    {
      const w = new World();
      const { platform } = withDgram(w);
      const engine = new Engine(platform, parseConfig({ policy: { media: { allowed: false } } }));
      await connectHost(w, engine, key, true);
      const r = await engine.start({ kind: "connect", hostId: key.hostId, ch: "media-dgram" }, w.requestCtx());
      expect(r.status).toBe(403);
      expect((await r.json()).reason).toBe("media_disabled");
    }
  });

  test("byte policing drops excess packets without closing, meters to media, extends idle, and unregisters on close", async () => {
    const w = new World();
    const { platform, registered } = withDgram(w, 8444);
    const cfg = parseConfig({
      policy: {
        media: { allowed: true, kbps: 80, burstKb: 1 }, // 1.5 * (100_000 + 1024) = 151_536 B / 10s
        limits: { sessionsPerHost: 4, idleTimeoutS: 5 },
        quotas: { hostDailyMb: 1, hostDailyMessages: 10 },
      },
    });
    const engine = new Engine(platform, cfg);
    const key = await newHostKey();
    const host = await connectHost(w, engine, key, true);

    const cres = await engine.start({ kind: "connect", hostId: key.hostId, ch: "media-dgram" }, w.requestCtx());
    expect(cres.status).toBe(200);
    const app = w.socks.at(-1)!;
    const open = host.texts.filter((t) => t.t === "open").at(-1);
    expect(open).toMatchObject({ ch: "media-dgram", udp: { port: 8444 } });
    expect(b64uDecode(open.udp.token)?.byteLength).toBe(16);

    const dres = await engine.start({ kind: "data", hostId: key.hostId, sid: open.sid }, w.requestCtx());
    expect(dres.status).toBe(200);
    const ready = app.texts.find((t) => t.t === "ready");
    expect(ready).toMatchObject({
      t: "ready",
      ch: "media-dgram",
      capabilities: { dgram: "udp", udpPort: 8444, stream: true },
      udp: { port: 8444 },
    });
    expect(b64uDecode(ready.udp.token)?.byteLength).toBe(16);
    expect(ready.udp.token).not.toBe(open.udp.token);

    const reg = registered.get(open.sid)!;
    expect(reg).toBeDefined();

    // Send 150,000 B within allowance -> all accepted, none count as messages
    for (let i = 0; i < 150; i++) {
      expect(reg.onForward("h", 1000)).toBe(true);
    }
    // Excess datagrams are dropped (false), NOT closing the control session
    expect(reg.onForward("h", 2000)).toBe(false);
    expect(app.closedWith).toBeUndefined();

    // Advance 3s, send 1 packet (resets idle deadline to t+8s) -> at old t+5s session stays open
    w.t += 3_000;
    host.autoPing = w.t;
    app.autoPing = w.t;
    expect(reg.onForward("c", 500)).toBe(true);
    w.t += 3_000; // t0 + 6_000
    await engine.onAlarm();
    expect(app.closedWith).toBeUndefined();

    // Advance past flush deadline to flush metering into SQLite
    w.t += 30_000;
    host.autoPing = w.t;
    app.autoPing = w.t;
    // Wait: app/host didn't exceed 150s/75s, but idleTimeoutS=5 expired since t0+3s! So alarm closes idle and flushes.
    await engine.onAlarm();
    await w.settle();
    expect(app.closedWith).toEqual([1000, "idle"]);
    expect(registered.has(open.sid)).toBe(false);
    const totals = new HostLedger(sqlite(w.db), key.hostId).totals(w.t);
    expect(totals.dayMedia).toBe(150_500);
    expect(totals.dayControl).toBe(0);
    expect(totals.dayMsgs).toBe(0);
  });

  test("media quota exhaustion closes active media-dgram session and unregisters UDP tokens", async () => {
    const w = new World();
    const { platform, registered } = withDgram(w, 8444);
    const cfg = parseConfig({
      policy: {
        media: { allowed: true, kbps: 100_000, burstKb: 10_000 },
        quotas: { hostDailyMb: 0, hostMonthlyControlMb: 0, hostMonthlyMediaMb: 0.1 }, // ~104,857 B
      },
    });
    const engine = new Engine(platform, cfg);
    const key = await newHostKey();
    const host = await connectHost(w, engine, key, true);

    await engine.start({ kind: "connect", hostId: key.hostId, ch: "media-dgram" }, w.requestCtx());
    const open = host.texts.filter((t) => t.t === "open").at(-1);
    const app = w.socks.at(-1)!;
    await engine.start({ kind: "data", hostId: key.hostId, sid: open.sid }, w.requestCtx());
    const data = w.socks.at(-1)!;
    const reg = registered.get(open.sid)!;

    expect(reg.onForward("h", 120_000)).toBe(true);
    w.t += 31_000;
    host.autoPing = w.t;
    app.autoPing = w.t;
    data.autoPing = w.t;
    await engine.onAlarm();
    await w.settle();
    expect(app.closedWith).toEqual([4429, "quota_exhausted"]);
    expect(data.closedWith).toEqual([4429, "quota_exhausted"]);
    expect(registered.has(open.sid)).toBe(false);
  });
});

async function udpPeer() {
  const inbox: { data: Buffer; port: number; address: string }[] = [];
  const waiters: Array<() => void> = [];
  const sock = await Bun.udpSocket({
    hostname: "127.0.0.1",
    port: 0,
    binaryType: "buffer",
    socket: {
      data(_sock, buf, port, address) {
        inbox.push({ data: Buffer.from(buf), port, address });
        waiters.shift()?.();
      },
    },
  });
  async function nextPacket(timeoutMs = 2000) {
    if (inbox.length > 0) return inbox.shift()!;
    await new Promise<void>((resolve, reject) => {
      const t = setTimeout(() => {
        const idx = waiters.indexOf(onReady);
        if (idx >= 0) waiters.splice(idx, 1);
        reject(new Error("timed out waiting for UDP packet"));
      }, timeoutMs);
      const onReady = () => {
        clearTimeout(t);
        resolve();
      };
      waiters.push(onReady);
    });
    return inbox.shift()!;
  }
  async function expectNothing(waitMs = 80) {
    await Bun.sleep(waitMs);
    expect(inbox.length).toBe(0);
  }
  return { sock, nextPacket, expectNothing };
}

describe("BunUdpRelay end-to-end", () => {
  test("probes, anti-amplification, bidirectional flows, NAT rebinding, and revocation on close", async () => {
    const udp = await BunUdpRelay.create({ port: 0, hostname: "127.0.0.1" });
    const relay = createBunRelay({
      config: parseConfig({
        policy: {
          media: { allowed: true, kbps: 10_000, burstKb: 1024 },
          limits: { sessionsPerHost: 4, idleTimeoutS: 60 },
        },
      }),
      secrets: {},
      db: new Database(":memory:"),
      udp,
    });
    const server = Bun.serve({ port: 0, hostname: "127.0.0.1", fetch: relay.fetch, websocket: relay.websocket });
    const client = await udpPeer();
    const client2 = await udpPeer();
    const hostUdp = await udpPeer();

    try {
      const base = `http://127.0.0.1:${server.port}`;
      const infoRes = await fetch(base + "/v1/info");
      expect(await infoRes.json()).toMatchObject({
        v: 1,
        impl: "bun",
        capabilities: { dgram: "udp", udpPort: udp.port, stream: true },
      });

      const host = await HostControl.connect(base, undefined, { dgram: true });
      expect(host.hello.capabilities).toEqual({ dgram: "udp", udpPort: udp.port, stream: true });

      const sess = await openSession(host, "media-dgram");
      expect(sess.open.udp?.port).toBe(udp.port);
      expect(sess.ready.udp?.port).toBe(udp.port);
      expect(sess.ready.capabilities).toEqual({ dgram: "udp", udpPort: udp.port, stream: true });

      const cTok = b64uDecode(sess.ready.udp!.token)!;
      const hTok = b64uDecode(sess.open.udp!.token)!;
      const relayPort = udp.port;

      // 1. Unknown token is silently dropped
      const badTok = new Uint8Array(17).fill(0x99);
      badTok[16] = 0xff;
      client.sock.send(badTok, relayPort, "127.0.0.1");
      await client.expectNothing();

      // 2. Oversized probe (>33 bytes total) is dropped; valid 0xFF returns 0xFE with same nonce
      const bigProbe = new Uint8Array(34);
      bigProbe.set(cTok, 0);
      bigProbe[16] = 0xff;
      client.sock.send(bigProbe, relayPort, "127.0.0.1");
      await client.expectNothing();

      const probe = new Uint8Array(25);
      probe.set(cTok, 0);
      probe[16] = 0xff;
      probe.set([1, 2, 3, 4, 5, 6, 7, 8], 17);
      client.sock.send(probe, relayPort, "127.0.0.1");
      const echo = await client.nextPacket();
      expect(echo.data.subarray(0, 16)).toEqual(Buffer.from(cTok));
      expect(echo.data[16]).toBe(0xfe);
      expect(echo.data.subarray(17)).toEqual(Buffer.from([1, 2, 3, 4, 5, 6, 7, 8]));

      // 3. Anti-amplification: client -> host before host binds is silently dropped
      const preBind = Buffer.concat([Buffer.from(cTok), Buffer.from([0x02, 0xaa, 0xbb])]);
      client.sock.send(preBind, relayPort, "127.0.0.1");
      await hostUdp.expectNothing();

      // 4. Host probes 0xFF -> binds hostUdp addr -> bidirectional flows 0x01, 0x02, 0x03 work
      const hProbe = Buffer.concat([Buffer.from(hTok), Buffer.from([0xff])]);
      hostUdp.sock.send(hProbe, relayPort, "127.0.0.1");
      const hEcho = await hostUdp.nextPacket();
      expect(hEcho.data[16]).toBe(0xfe);

      for (const flow of [0x01, 0x02, 0x03]) {
        const msg = Buffer.concat([Buffer.from(cTok), Buffer.from([flow]), Buffer.from(`c2h-${flow}`)]);
        client.sock.send(msg, relayPort, "127.0.0.1");
        const recvH = await hostUdp.nextPacket();
        expect(recvH.data.subarray(0, 16)).toEqual(Buffer.from(hTok));
        expect(recvH.data[16]).toBe(flow);
        expect(recvH.data.subarray(17).toString()).toBe(`c2h-${flow}`);

        const reply = Buffer.concat([Buffer.from(hTok), Buffer.from([flow]), Buffer.from(`h2c-${flow}`)]);
        hostUdp.sock.send(reply, relayPort, "127.0.0.1");
        const recvC = await client.nextPacket();
        expect(recvC.data.subarray(0, 16)).toEqual(Buffer.from(cTok));
        expect(recvC.data[16]).toBe(flow);
        expect(recvC.data.subarray(17).toString()).toBe(`h2c-${flow}`);
      }

      // 5. NAT rebinding: client2 sends with cTok -> host packets arrive at client2
      const probe2 = Buffer.concat([Buffer.from(cTok), Buffer.from([0xff])]);
      client2.sock.send(probe2, relayPort, "127.0.0.1");
      await client2.nextPacket();
      const fromHostAfterRebind = Buffer.concat([Buffer.from(hTok), Buffer.from([0x01]), Buffer.from("new-path")]);
      hostUdp.sock.send(fromHostAfterRebind, relayPort, "127.0.0.1");
      const onNewClient = await client2.nextPacket();
      expect(onNewClient.data.subarray(17).toString()).toBe("new-path");
      await client.expectNothing();

      // 6. Control WS closes -> tokens revoked immediately
      sess.app.close();
      await sess.data.waitClosed();
      const afterClose = Buffer.concat([Buffer.from(cTok), Buffer.from([0x01]), Buffer.from("dead")]);
      client2.sock.send(afterClose, relayPort, "127.0.0.1");
      await hostUdp.expectNothing();
      host.close();
    } finally {
      client.sock.close();
      client2.sock.close();
      hostUdp.sock.close();
      server.stop(true);
      udp.close();
    }
  });
});

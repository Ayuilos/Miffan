import { describe, expect, test } from "bun:test";
import { Database } from "bun:sqlite";
import { createBunRelay } from "../../bun/relay.ts";
import { LIMITS } from "../../core/codes.ts";
import { DEFAULT_CONFIG, parseConfig } from "../../core/config.ts";
import { routeRequest, STREAM_CONTENT_TYPE } from "../../core/http.ts";
import { encodeStreamClose, encodeStreamFrame, STREAM_PRELUDE, StreamDecoder } from "../../core/stream.ts";
import { Conn, StreamConn, streamAttempt } from "../client/conn.ts";
import { newDeviceKey, newHostKey } from "../client/keys.ts";

describe("StreamDecoder and frame encoding", () => {
  test("round-trips prelude, text, binary, coalesced and byte-by-byte chunks", () => {
    const bin = crypto.getRandomValues(new Uint8Array(1024));
    const wire = Buffer.concat([
      STREAM_PRELUDE,
      encodeStreamFrame("hello 世界"),
      encodeStreamFrame(bin),
      encodeStreamClose(4401, "unauthorized"),
    ]);

    const decWhole = new StreamDecoder();
    expect(decWhole.push(wire)).toEqual([
      { kind: "prelude" },
      { kind: "text", data: "hello 世界" },
      { kind: "binary", data: bin },
      { kind: "close", code: 4401, reason: "unauthorized" },
    ]);
    expect(decWhole.isHalted).toBe(true);
    expect(decWhole.hasPartial()).toBe(false);

    const decByte = new StreamDecoder();
    const events = [];
    for (let i = 0; i < wire.length; i++) {
      events.push(...decByte.push(wire.subarray(i, i + 1)));
    }
    expect(events).toEqual([
      { kind: "prelude" },
      { kind: "text", data: "hello 世界" },
      { kind: "binary", data: bin },
      { kind: "close", code: 4401, reason: "unauthorized" },
    ]);
  });

  test("handles zero-length frames and UTF-8 safe close truncation", () => {
    const dec = new StreamDecoder();
    const longReason = "啊".repeat(60); // 180 UTF-8 bytes
    const closeBytes = encodeStreamClose(1000, longReason);
    expect(closeBytes.length - 5).toBeLessThanOrEqual(125);

    expect(dec.push(Buffer.concat([encodeStreamFrame(""), encodeStreamFrame(new Uint8Array(0)), closeBytes]))).toEqual([
      { kind: "text", data: "" },
      { kind: "binary", data: new Uint8Array(0) },
      { kind: "close", code: 1000, reason: "啊".repeat(41) },
    ]);
  });

  test("rejects oversize headers immediately without waiting for payload", () => {
    const hdr = new Uint8Array(5);
    const view = new DataView(hdr.buffer);

    const decText = new StreamDecoder();
    hdr[0] = 0x01;
    view.setUint32(1, LIMITS.maxTextFrame + 1, false);
    expect(decText.push(hdr)).toEqual([{ kind: "oversize" }]);
    expect(decText.isHalted).toBe(true);

    const decBin = new StreamDecoder();
    hdr[0] = 0x02;
    view.setUint32(1, 0x7fffffff, false);
    expect(decBin.push(hdr)).toEqual([{ kind: "oversize" }]);
    expect(decBin.isHalted).toBe(true);
  });

  test("rejects bad prelude, invalid frame type, invalid close length, and invalid UTF-8", () => {
    const hdr = new Uint8Array(5);
    const view = new DataView(hdr.buffer);

    const badPrelude = new StreamDecoder();
    hdr[0] = 0x00;
    view.setUint32(1, 1, false);
    expect(badPrelude.push(hdr)).toEqual([{ kind: "invalid", reason: "bad_prelude" }]);

    const badType = new StreamDecoder();
    hdr[0] = 0x09;
    view.setUint32(1, 0, false);
    expect(badType.push(hdr)).toEqual([{ kind: "invalid", reason: "bad_type" }]);

    const badClose = new StreamDecoder();
    hdr[0] = 0x08;
    view.setUint32(1, 1, false);
    expect(badClose.push(hdr)).toEqual([{ kind: "invalid", reason: "bad_close" }]);

    const badUtf8 = new StreamDecoder();
    hdr[0] = 0x01;
    view.setUint32(1, 2, false);
    expect(badUtf8.push(Buffer.concat([hdr, new Uint8Array([0xff, 0xfe])]))).toEqual([
      { kind: "invalid", reason: "invalid_utf8" },
    ]);
  });
});

describe("routeRequest HTTP stream support", () => {
  test("accepts POST stream routes and preserves method/auth order", async () => {
    const cfg = parseConfig({ accessMode: "token" });
    const hostKey = await newHostKey();
    const secrets = { accessToken: "tok" };

    const okHost = await routeRequest(
      new Request(`https://relay.example/v1/host?pub=${hostKey.pubB64u}`, {
        method: "POST",
        headers: {
          authorization: "Bearer tok",
          "content-type": `${STREAM_CONTENT_TYPE}; charset=binary`,
        },
      }),
      cfg,
      secrets,
    );
    expect(okHost).toMatchObject({ kind: "host", transport: "stream", hostId: hostKey.hostId });

    // Token check happens before shape/content-type check per PROTOCOL §6.4.
    const unauth = await routeRequest(
      new Request(`https://relay.example/v1/host?pub=${hostKey.pubB64u}`, {
        method: "POST",
        headers: { "content-type": "text/plain" },
      }),
      cfg,
      secrets,
    );
    expect(unauth).toBeInstanceOf(Response);
    expect((unauth as Response).status).toBe(401);

    const postInfo = await routeRequest(new Request("https://relay.example/v1/info", { method: "POST" }), DEFAULT_CONFIG, {});
    expect((postInfo as Response).status).toBe(405);

    const putHost = await routeRequest(new Request("https://relay.example/v1/host", { method: "PUT" }), DEFAULT_CONFIG, {});
    expect((putHost as Response).status).toBe(405);
  });
});

describe("Bun relay HTTP stream e2e", () => {
  async function startTestServer() {
    const cfg = parseConfig({
      edges: ["127.0.0.1"],
      policy: { limits: { sessionsPerHost: 4, idleTimeoutS: 5 } },
    });
    const relay = createBunRelay({ config: cfg, secrets: {}, db: new Database(":memory:") });
    const server = Bun.serve({
      port: 0,
      hostname: "127.0.0.1",
      fetch: relay.fetch,
      websocket: relay.websocket,
    });
    return {
      base: `http://127.0.0.1:${server.port}`,
      stop: async () => {
        await relay.flushAll();
        await server.stop(true);
      },
    };
  }

  test("pairs StreamConn app with StreamConn host and cross-transports with WebSocket Conn", async () => {
    const srv = await startTestServer();
    try {
      const infoRes = await fetch(srv.base + "/v1/info");
      expect(infoRes.status).toBe(200);
      const info = (await infoRes.json()) as { capabilities: { dgram: string; stream?: boolean } };
      expect(info.capabilities).toEqual({ dgram: "none", stream: true });

      const hostKey = await newHostKey();
      const deviceKey = await newDeviceKey();

      // Host connects over StreamConn.
      const hostUrl = `${srv.base}/v1/host?pub=${hostKey.pubB64u}`;
      const host = await StreamConn.open(hostUrl);
      const ch = await host.nextJson<{ t: string; nonce: string }>();
      expect(ch.t).toBe("challenge");
      host.send(JSON.stringify({ t: "auth", sig: await hostKey.signChallenge(`127.0.0.1:${new URL(srv.base).port}`, ch.nonce) }));
      const hello = await host.nextJson<{ t: string; capabilities: { dgram: string; stream?: boolean } }>();
      expect(hello.t).toBe("hello");
      expect(hello.capabilities).toEqual({ dgram: "none", stream: true });

      host.send("ping");
      expect(await host.nextText()).toBe("pong");

      // Case 1: App uses WebSocket Conn, Host Data uses StreamConn.
      const appWs = await Conn.open(srv.base.replace(/^http/, "ws") + `/v1/connect/${hostKey.hostId}?ch=control`);
      const open1 = await host.nextJson<{ t: string; sid: string }>();
      expect(open1.t).toBe("open");
      const dataStream = await StreamConn.open(`${srv.base}/v1/host/${hostKey.hostId}/session/${open1.sid}`);
      const ready1 = await appWs.nextJson<{ t: string }>();
      expect(ready1.t).toBe("ready");

      const cNonce1 = "c1";
      appWs.send(
        JSON.stringify({
          t: "auth",
          device: deviceKey.pubB64u,
          sig: await deviceKey.signSession(hostKey.hostId, open1.sid, "control", cNonce1),
        }),
      );
      expect((await dataStream.nextJson<{ t: string }>()).t).toBe("auth");
      const bin1 = crypto.getRandomValues(new Uint8Array(4096));
      appWs.send(bin1);
      expect(await dataStream.nextBinary()).toEqual(bin1);
      dataStream.send(bin1.slice(0, 512));
      expect(await appWs.nextBinary()).toEqual(bin1.slice(0, 512));

      dataStream.close(1000, "done");
      expect(await appWs.waitClosed()).toEqual({ code: 1000, reason: "peer_closed" });

      // Case 2: App uses StreamConn, Host Data uses WebSocket Conn, and oversize frame triggers 4413 + 1000.
      const appStream = await StreamConn.open(`${srv.base}/v1/connect/${hostKey.hostId}?ch=media`);
      const open2 = await host.nextJson<{ t: string; sid: string }>();
      const dataWs = await Conn.open(srv.base.replace(/^http/, "ws") + `/v1/host/${hostKey.hostId}/session/${open2.sid}`);
      const ready2 = await appStream.nextJson<{ t: string }>();
      expect(ready2.t).toBe("ready");

      const payload2 = crypto.getRandomValues(new Uint8Array(1234));
      appStream.send(payload2);
      expect(await dataWs.nextBinary()).toEqual(payload2);

      // Oversize frame (> 65536) from appStream closes offender 4413 and peer 1000.
      appStream.send(new Uint8Array(LIMITS.maxBinaryFrame + 1));
      expect(await appStream.waitClosed()).toEqual({ code: 4413, reason: "frame_too_large" });
      expect(await dataWs.waitClosed()).toEqual({ code: 1000, reason: "peer_closed" });

      // Offline host rejection over stream attempt preserves JSON reason.
      host.close(1000, "bye");
      await host.waitClosed();
      const missingKey = await newHostKey();
      const offline = await streamAttempt(`${srv.base}/v1/connect/${missingKey.hostId}?ch=control`);
      expect(offline.status).toBe(404);
      expect(offline.json).toMatchObject({ reason: "host_offline" });
    } finally {
      await srv.stop();
    }
  });

  test("closes stalled BunStreamSock with 1013 backpressure and notifies peer", async () => {
    const net = await import("node:net");
    const relay = createBunRelay({
      config: parseConfig({
        policy: {
          media: { allowed: true, kbps: 500_000, burstKb: 20_000 },
          quotas: { hostDailyMb: 50, hostDailyMessages: 10_000 },
          limits: { sessionsPerHost: 4, idleTimeoutS: 10 },
        },
      }),
      secrets: {},
      db: new Database(":memory:"),
    });
    const server = Bun.serve({ port: 0, hostname: "127.0.0.1", fetch: relay.fetch, websocket: relay.websocket });
    try {
      const base = `http://127.0.0.1:${server.port}`;
      const hostKey = await newHostKey();
      const host = await StreamConn.open(`${base}/v1/host?pub=${hostKey.pubB64u}`);
      const ch = await host.nextJson<{ nonce: string }>();
      host.send(JSON.stringify({ t: "auth", sig: await hostKey.signChallenge(`127.0.0.1:${server.port}`, ch.nonce) }));
      await host.nextJson(); // hello

      // Raw HTTP/1.1 POST stream that stops reading immediately after headers arrive.
      const stalled = net.connect(server.port!, "127.0.0.1");
      await new Promise<void>((resolve) => stalled.once("connect", () => resolve()));
      stalled.write(
        `POST /v1/connect/${hostKey.hostId}?ch=media HTTP/1.1\r\n` +
          `Host: 127.0.0.1:${server.port}\r\n` +
          `Content-Type: ${STREAM_CONTENT_TYPE}\r\n` +
          `Transfer-Encoding: chunked\r\n\r\n`,
      );
      stalled.pause();

      const open = await host.nextJson<{ t: string; sid: string }>();
      const data = await StreamConn.open(`${base}/v1/host/${hostKey.hostId}/session/${open.sid}`);
      const chunk = crypto.getRandomValues(new Uint8Array(65_536));
      for (let i = 0; i < 250 && !data.closed; i++) {
        data.send(chunk);
        await Bun.sleep(1);
      }
      const closed = await data.waitClosed(10_000);
      expect(closed).toEqual({ code: 1000, reason: "peer_closed" });

      stalled.destroy();
      host.close(1000, "done");
      await host.waitClosed();
    } finally {
      await relay.flushAll();
      await server.stop(true);
    }
  }, 20_000);
});

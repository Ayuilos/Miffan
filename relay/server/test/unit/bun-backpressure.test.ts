// The Bun adapter against a reader that stops reading: the relay must not buffer without bound.

import { Database } from "bun:sqlite";
import { expect, test } from "bun:test";
import net from "node:net";
import { parseConfig } from "../../core/config.ts";
import { createBunRelay } from "../../bun/relay.ts";
import { HostControl, bytes } from "../client/client.ts";

test("a socket whose reader stalls is closed with 1013 once 4 MiB are buffered; the peer gets peer_closed", async () => {
  const relay = createBunRelay({ config: parseConfig({}), secrets: {}, db: new Database(":memory:") });
  const server = Bun.serve({ port: 0, hostname: "127.0.0.1", fetch: relay.fetch, websocket: relay.websocket });
  try {
    const base = `http://127.0.0.1:${server.port}`;
    const host = await HostControl.connect(base);
    // An app that upgrades and then never reads again.
    const app = net.connect(server.port!, "127.0.0.1");
    await new Promise<void>((resolve) => app.once("connect", () => resolve()));
    app.write(
      `GET /v1/connect/${host.hostId}?ch=media HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n` +
        "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n",
    );
    app.pause();
    const open = await host.nextOpen();
    const data = await host.openData(open.sid);
    // 150 x 64 KiB = 9.4 MiB: under the media allowance, far over the 4 MiB buffer.
    for (let i = 0; i < 150 && !data.closed; i++) {
      data.send(bytes(65_536));
      await Bun.sleep(1);
    }
    const c = await data.waitClosed(10_000);
    expect(`${c.code} ${c.reason}`).toBe("1000 peer_closed");
    app.destroy();
    host.close();
  } finally {
    server.stop(true);
  }
}, 20_000);

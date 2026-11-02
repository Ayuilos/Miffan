// Protocol reference clients for the connector side and the app side (PROTOCOL §4, §5).

import { Conn, getJson, upgradeAttempt, sleep, type HttpResult } from "./conn.ts";
import { newHostKey, type HostKey } from "./keys.ts";

export { Conn, getJson, upgradeAttempt, sleep };
export type { HttpResult };

export const wsUrl = (base: string, path: string) => base.replace(/^http/, "ws") + path;

export interface Opts {
  token?: string;
}

const auth = (o?: Opts): Record<string, string> => (o?.token ? { authorization: `Bearer ${o.token}` } : {});

export interface HelloMsg {
  t: "hello";
  hostId: string;
  policy: Record<string, any>;
  usage: Usage;
  capabilities: { dgram: string };
}

export interface Usage {
  day: string;
  month: string;
  dayMb: number;
  dayActiveMin: number;
  monthControlMb: number;
  monthMediaMb: number;
  budget: string;
  resetsAt: string;
}

export interface OpenMsg {
  t: "open";
  sid: string;
  ch: "control" | "media";
  pace: { kbps: number; burstKb: number };
}

export interface ReadyMsg {
  t: "ready";
  sid: string;
  ch: "control" | "media";
  pace: { kbps: number; burstKb: number };
  policy: Record<string, any>;
  usage: Usage;
}

/** The connector's control connection. */
export class HostControl {
  constructor(
    readonly base: string,
    readonly key: HostKey,
    readonly conn: Conn,
    readonly hello: HelloMsg,
  ) {}

  get hostId(): string {
    return this.key.hostId;
  }

  /** Connect, answer the challenge correctly and wait for `hello`. */
  static async connect(base: string, key?: HostKey, opts?: Opts): Promise<HostControl> {
    const k = key ?? (await newHostKey());
    const { conn, nonce, host } = await HostControl.connectRaw(base, k, opts);
    conn.send(JSON.stringify({ t: "auth", sig: await k.signChallenge(host, nonce) }));
    const hello = await conn.nextJson<HelloMsg>();
    if (hello.t !== "hello") throw new Error("expected hello, got " + JSON.stringify(hello));
    return new HostControl(base, k, conn, hello);
  }

  /** Connect and read the challenge, leaving authentication to the caller. */
  static async connectRaw(base: string, key: HostKey, opts?: Opts & { hint?: string }) {
    const hint = opts?.hint ? `&hint=${opts.hint}` : "";
    const conn = await Conn.open(wsUrl(base, `/v1/host?pub=${key.pubB64u}${hint}`), auth(opts));
    const ch = await conn.nextJson<{ t: string; nonce: string }>();
    if (ch.t !== "challenge") throw new Error("expected challenge, got " + JSON.stringify(ch));
    return { conn, nonce: ch.nonce, host: new URL(base).host.toLowerCase() };
  }

  async nextOpen(timeoutMs?: number): Promise<OpenMsg> {
    for (;;) {
      const text = await this.conn.nextText(timeoutMs);
      if (text === "pong") continue; // answers to our own keepalive pings
      const m = JSON.parse(text) as { t: string };
      if (m.t === "open") return m as unknown as OpenMsg;
      // `usage` pushes may interleave; ignore everything but `open`
    }
  }

  /** Open the data connection for a session the relay announced. */
  openData(sid: string): Promise<Conn> {
    return Conn.open(wsUrl(this.base, `/v1/host/${this.hostId}/session/${sid}`));
  }

  close(): void {
    this.conn.close();
  }
}

/** The app's side of a session: connect and wait for `ready`. */
export async function connectApp(
  base: string,
  hostId: string,
  ch: "control" | "media",
  opts?: Opts,
): Promise<{ conn: Conn; open?: undefined }> {
  const conn = await Conn.open(wsUrl(base, `/v1/connect/${hostId}?ch=${ch}`), auth(opts));
  return { conn };
}

export interface Session {
  app: Conn;
  data: Conn;
  sid: string;
  ready: ReadyMsg;
  open: OpenMsg;
}

/** Full session setup: app connects, connector gets `open` and attaches, app gets `ready`. */
export async function openSession(host: HostControl, ch: "control" | "media" = "control", opts?: Opts): Promise<Session> {
  const { conn: app } = await connectApp(host.base, host.hostId, ch, opts);
  const open = await host.nextOpen();
  const data = await host.openData(open.sid);
  const ready = await app.nextJson<ReadyMsg>();
  if (ready.t !== "ready") throw new Error("expected ready, got " + JSON.stringify(ready));
  return { app, data, sid: open.sid, ready, open };
}

export const bytes = (n: number, fill = 0x5a) => new Uint8Array(n).fill(fill);

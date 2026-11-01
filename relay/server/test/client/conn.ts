// WebSocket client for tests: queued messages, awaitable close, exact Close frames.

import { randomBytes } from "node:crypto";
import request from "node:http";
import net from "node:net";

export type Msg = string | Uint8Array;

export interface CloseInfo {
  code: number;
  reason: string;
}

/**
 * A small RFC 6455 client over a raw TCP socket. Tests use it instead of a library WebSocket because
 * they must observe the exact Close frame (code and reason) the relay sends, and treat the frame itself
 * as the end of the connection: `wrangler dev --local` leaves the TCP connection of a socket that the
 * server closed on behalf of a peer open for about ten seconds after the close handshake.
 */
export class Conn {
  readonly messages: Msg[] = [];
  closed?: CloseInfo;
  readonly closePromise: Promise<CloseInfo>;
  private waiters: (() => void)[] = [];
  private buf = Buffer.alloc(0);
  private fragments: Buffer[] = [];
  private fragOpcode = 0;
  private sentClose = false;
  private resolveClose!: (c: CloseInfo) => void;

  private constructor(private sock: net.Socket) {
    this.closePromise = new Promise((resolve) => (this.resolveClose = resolve));
    sock.on("data", (d: Buffer) => this.onData(d));
    sock.on("close", () => this.finish({ code: 1006, reason: "" }));
    sock.on("error", () => this.finish({ code: 1006, reason: "" }));
  }

  private wake(): void {
    for (const w of this.waiters.splice(0)) w();
  }

  private finish(info: CloseInfo): void {
    if (this.closed) return;
    this.closed = info;
    this.resolveClose(info);
    this.wake();
    this.sock.end();
  }

  /** Open a WebSocket; rejects with the HTTP status if the upgrade is refused. */
  static open(url: string, headers: Record<string, string> = {}): Promise<Conn> {
    const u = new URL(url);
    return new Promise((resolve, reject) => {
      const sock = net.connect(Number(u.port || 80), u.hostname);
      let head = Buffer.alloc(0);
      let conn: Conn | undefined;
      const lines = [
        `GET ${u.pathname}${u.search} HTTP/1.1`,
        `Host: ${u.host}`,
        "Connection: Upgrade",
        "Upgrade: websocket",
        "Sec-WebSocket-Version: 13",
        `Sec-WebSocket-Key: ${randomBytes(16).toString("base64")}`,
        ...Object.entries(headers).map(([k, v]) => `${k}: ${v}`),
      ];
      sock.write(lines.join("\r\n") + "\r\n\r\n");
      const onData = (d: Buffer) => {
        head = Buffer.concat([head, d]);
        const end = head.indexOf("\r\n\r\n");
        if (end < 0) return;
        sock.off("data", onData);
        const status = Number(/^HTTP\/1\.1 (\d+)/.exec(head.toString("latin1", 0, end))?.[1]);
        if (status !== 101) {
          sock.destroy();
          reject(new Error(`upgrade refused (${status}): ${url}`));
          return;
        }
        conn = new Conn(sock);
        resolve(conn);
        const rest = head.subarray(end + 4);
        if (rest.length) conn.onData(rest);
      };
      sock.on("data", onData);
      sock.on("error", reject);
    });
  }

  private onData(d: Buffer): void {
    this.buf = Buffer.concat([this.buf, d]);
    for (;;) {
      if (this.buf.length < 2) return;
      const b0 = this.buf[0]!;
      const b1 = this.buf[1]!;
      let len = b1 & 0x7f;
      let off = 2;
      if (len === 126) {
        if (this.buf.length < 4) return;
        len = this.buf.readUInt16BE(2);
        off = 4;
      } else if (len === 127) {
        if (this.buf.length < 10) return;
        len = Number(this.buf.readBigUInt64BE(2));
        off = 10;
      }
      if (this.buf.length < off + len) return;
      const payload = Buffer.from(this.buf.subarray(off, off + len));
      this.buf = this.buf.subarray(off + len);
      this.onFrame(b0 & 0x80 ? 1 : 0, b0 & 0x0f, payload);
    }
  }

  private onFrame(fin: number, opcode: number, payload: Buffer): void {
    if (opcode === 0 || opcode === 1 || opcode === 2) {
      if (opcode !== 0) {
        this.fragOpcode = opcode;
        this.fragments = [];
      }
      this.fragments.push(payload);
      if (!fin) return;
      const whole = Buffer.concat(this.fragments);
      this.fragments = [];
      this.messages.push(this.fragOpcode === 1 ? whole.toString("utf8") : new Uint8Array(whole));
      this.wake();
    } else if (opcode === 8) {
      const code = payload.length >= 2 ? payload.readUInt16BE(0) : 1005;
      const reason = payload.length > 2 ? payload.toString("utf8", 2) : "";
      if (!this.sentClose) this.writeFrame(8, payload.subarray(0, 2)); // echo
      this.finish({ code, reason });
    } else if (opcode === 9) {
      this.writeFrame(10, payload);
    }
  }

  private writeFrame(opcode: number, payload: Uint8Array): void {
    if (this.closed || this.sock.destroyed) return;
    const mask = randomBytes(4);
    const n = payload.length;
    const head = n < 126 ? Buffer.from([0x80 | opcode, 0x80 | n]) : n < 65536 ? Buffer.from([0x80 | opcode, 0x80 | 126, n >> 8, n & 255]) : (() => {
      const h = Buffer.alloc(10);
      h[0] = 0x80 | opcode;
      h[1] = 0x80 | 127;
      h.writeBigUInt64BE(BigInt(n), 2);
      return h;
    })();
    const body = Buffer.alloc(n);
    for (let i = 0; i < n; i++) body[i] = payload[i]! ^ mask[i % 4]!;
    this.sock.write(Buffer.concat([head, mask, body]));
  }

  send(m: Msg): void {
    if (typeof m === "string") this.writeFrame(1, Buffer.from(m, "utf8"));
    else this.writeFrame(2, m);
  }

  close(code = 1000, reason = ""): void {
    const r = Buffer.from(reason, "utf8");
    const p = Buffer.alloc(2 + r.length);
    p.writeUInt16BE(code, 0);
    r.copy(p, 2);
    this.sentClose = true;
    this.writeFrame(8, p);
  }

  /** Next message, or throws on timeout / close. */
  async next(timeoutMs = 5000): Promise<Msg> {
    const deadline = Date.now() + timeoutMs;
    while (this.messages.length === 0) {
      if (this.closed) throw new Error(`closed (${this.closed.code} ${this.closed.reason}) while waiting for a message`);
      const left = deadline - Date.now();
      if (left <= 0) throw new Error("timed out waiting for a message");
      await new Promise<void>((resolve) => {
        const t = setTimeout(resolve, left);
        this.waiters.push(() => {
          clearTimeout(t);
          resolve();
        });
      });
    }
    return this.messages.shift()!;
  }

  async nextText(timeoutMs?: number): Promise<string> {
    const m = await this.next(timeoutMs);
    if (typeof m !== "string") throw new Error("expected a text frame, got binary");
    return m;
  }

  async nextJson<T = Record<string, unknown>>(timeoutMs?: number): Promise<T> {
    return JSON.parse(await this.nextText(timeoutMs)) as T;
  }

  async nextBinary(timeoutMs?: number): Promise<Uint8Array> {
    const m = await this.next(timeoutMs);
    if (typeof m === "string") throw new Error("expected a binary frame, got text: " + m);
    return m;
  }

  /** Wait for the close; throws on timeout. */
  async waitClosed(timeoutMs = 5000): Promise<CloseInfo> {
    return Promise.race([
      this.closePromise,
      new Promise<never>((_, rej) => setTimeout(() => rej(new Error("timed out waiting for close")), timeoutMs)),
    ]);
  }
}

export interface HttpResult {
  status: number;
  json: Record<string, unknown> | null;
  headers: Record<string, string | string[] | undefined>;
}

/**
 * Attempt a WebSocket upgrade with raw HTTP so the refusal (status and JSON body) can be inspected.
 * A successful 101 is reported as status 101; the socket is dropped.
 */
export function upgradeAttempt(url: string, headers: Record<string, string> = {}): Promise<HttpResult> {
  const u = new URL(url);
  return new Promise((resolve, reject) => {
    const req = request.request(
      {
        host: u.hostname,
        port: u.port,
        path: u.pathname + u.search,
        headers: {
          Connection: "Upgrade",
          Upgrade: "websocket",
          "Sec-WebSocket-Version": "13",
          "Sec-WebSocket-Key": btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(16)))),
          ...headers,
        },
      },
      (res) => {
        let body = "";
        res.on("data", (c) => (body += c));
        res.on("end", () => {
          let json: Record<string, unknown> | null = null;
          try {
            json = JSON.parse(body);
          } catch {
            // not JSON
          }
          resolve({ status: res.statusCode ?? 0, json, headers: res.headers });
        });
      },
    );
    req.on("upgrade", (res, socket) => {
      socket.destroy();
      resolve({ status: res.statusCode ?? 101, json: null, headers: res.headers });
    });
    req.on("error", reject);
    req.end();
  });
}

export async function getJson(url: string, headers: Record<string, string> = {}): Promise<HttpResult> {
  const r = await fetch(url, { headers });
  const text = await r.text();
  let json: Record<string, unknown> | null = null;
  try {
    json = JSON.parse(text);
  } catch {
    // not JSON
  }
  return { status: r.status, json, headers: Object.fromEntries([...(r.headers as unknown as { entries(): Iterable<[string, string]> }).entries()]) };
}

export const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms));

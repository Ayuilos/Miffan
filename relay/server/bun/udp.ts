// Native UDP datagram relay for Bun VPS deployments (PROTOCOL §5.5).
// Single UDP socket multiplexing all `media-dgram` sessions via 16-byte random tokens.

import type { DgramCapability } from "../core/config.ts";
import type { DgramPlatform } from "../core/engine.ts";

const DGRAM_HEADER_BYTES = 17;
const DGRAM_TOKEN_BYTES = 16;
const DGRAM_MAX_PACKET = 2048;
const DGRAM_MAX_PROBE_PACKET = 33; // 16B token + 1B flow + up to 16B probe nonce

const FLOW_VIDEO = 0x01;
const FLOW_CONTROL = 0x02;
const FLOW_AUDIO = 0x03;
const FLOW_ECHO = 0xfe;
const FLOW_BIND = 0xff;

export interface BunUdpRelayOptions {
  /** UDP port to bind (0 allocates an ephemeral port, useful for tests). */
  port: number;
  /** Optional bind hostname (defaults to dual-stack "::" with fallback to "0.0.0.0"). */
  hostname?: string;
  /** Optional public hostname advertised in capabilities.udpHost. */
  publicHost?: string;
  /** Optional public port advertised in capabilities.udpPort (defaults to bound socket port). */
  publicPort?: number;
}

interface PeerSlot {
  sid: string;
  role: "c" | "h";
  tokenBytes: Uint8Array;
  peer?: PeerSlot;
  addr?: { address: string; port: number };
  onForward: (role: "c" | "h", bytes: number) => boolean;
}

function tokenHex(bytes: Uint8Array): string {
  return Buffer.from(bytes.buffer, bytes.byteOffset, bytes.byteLength).toString("hex");
}

function isErrno(e: unknown, ...codes: string[]): boolean {
  const code = (e as { code?: string } | null)?.code;
  return typeof code === "string" && codes.includes(code);
}

export class BunUdpRelay {
  readonly port: number;
  readonly publicPort: number;
  readonly publicHost?: string;

  private readonly byToken = new Map<string, PeerSlot>();
  private readonly bySid = new Map<string, [string, string]>();

  private constructor(
    private readonly sock: Bun.udp.Socket<"buffer">,
    publicPort?: number,
    publicHost?: string,
  ) {
    this.port = sock.port;
    this.publicPort = publicPort && publicPort > 0 ? publicPort : sock.port;
    this.publicHost = publicHost || undefined;
  }

  static async create(opts: BunUdpRelayOptions): Promise<BunUdpRelay> {
    let instance!: BunUdpRelay;
    const handler: Bun.udp.SocketHandler<"buffer"> = {
      data(sock, buf, port, address) {
        instance.handlePacket(sock, buf, port, address);
      },
      error(_sock, err) {
        console.error(JSON.stringify({ ev: "udp_socket_error", msg: String(err) }));
      },
    };

    const bind = async (hostname: string) =>
      Bun.udpSocket({
        hostname,
        port: opts.port,
        binaryType: "buffer",
        socket: handler,
      });

    let sock: Bun.udp.Socket<"buffer">;
    if (opts.hostname) {
      sock = await bind(opts.hostname);
    } else {
      try {
        sock = await bind("::");
      } catch (e) {
        if (!isErrno(e, "EAFNOSUPPORT", "EADDRNOTAVAIL", "EINVAL")) throw e;
        sock = await bind("0.0.0.0");
      }
    }

    instance = new BunUdpRelay(sock, opts.publicPort, opts.publicHost);
    return instance;
  }

  capability(): DgramCapability {
    return {
      mode: "udp",
      port: this.publicPort,
      ...(this.publicHost ? { host: this.publicHost } : {}),
    };
  }

  platformPort(): DgramPlatform {
    return {
      ...this.capability(),
      register: (sid, clientToken, hostToken, onForward) =>
        this.register(sid, clientToken, hostToken, onForward),
      unregister: (sid) => this.unregister(sid),
    };
  }

  register(
    sid: string,
    clientToken: Uint8Array,
    hostToken: Uint8Array,
    onForward: (role: "c" | "h", bytes: number) => boolean,
  ): void {
    if (clientToken.byteLength !== DGRAM_TOKEN_BYTES || hostToken.byteLength !== DGRAM_TOKEN_BYTES) {
      throw new Error("UDP session tokens must be 16 bytes");
    }
    this.unregister(sid);
    const cKey = tokenHex(clientToken);
    const hKey = tokenHex(hostToken);
    const clientSlot: PeerSlot = {
      sid,
      role: "c",
      tokenBytes: new Uint8Array(clientToken),
      onForward,
    };
    const hostSlot: PeerSlot = {
      sid,
      role: "h",
      tokenBytes: new Uint8Array(hostToken),
      onForward,
    };
    clientSlot.peer = hostSlot;
    hostSlot.peer = clientSlot;
    this.byToken.set(cKey, clientSlot);
    this.byToken.set(hKey, hostSlot);
    this.bySid.set(sid, [cKey, hKey]);
  }

  unregister(sid: string): void {
    const keys = this.bySid.get(sid);
    if (!keys) return;
    this.bySid.delete(sid);
    this.byToken.delete(keys[0]);
    this.byToken.delete(keys[1]);
  }

  private handlePacket(sock: Bun.udp.Socket<"buffer">, buf: Buffer, port: number, address: string): void {
    const len = buf.byteLength;
    if (len < DGRAM_HEADER_BYTES || len > DGRAM_MAX_PACKET) return;

    const key = buf.toString("hex", 0, DGRAM_TOKEN_BYTES);
    const slot = this.byToken.get(key);
    if (!slot) return;

    if (!slot.addr || slot.addr.port !== port || slot.addr.address !== address) {
      slot.addr = { address, port };
    }

    const flow = buf[DGRAM_TOKEN_BYTES];
    if (flow === FLOW_BIND) {
      if (len > DGRAM_MAX_PROBE_PACKET) return;
      buf[DGRAM_TOKEN_BYTES] = FLOW_ECHO;
      try {
        sock.send(buf, port, address);
      } catch {
        // ignore transient send failures
      }
      return;
    }

    if (flow !== FLOW_VIDEO && flow !== FLOW_CONTROL && flow !== FLOW_AUDIO) return;

    const peer = slot.peer;
    const dst = peer?.addr;
    if (!dst) return;

    if (!slot.onForward(slot.role, len)) return;

    buf.set(peer.tokenBytes, 0);
    try {
      sock.send(buf, dst.port, dst.address);
    } catch {
      // ignore transient send failures
    }
  }

  close(): void {
    this.byToken.clear();
    this.bySid.clear();
    try {
      this.sock.close();
    } catch {
      // already closed
    }
  }
}

import { LIMITS } from "./codes.ts";
import type { Frame } from "./engine.ts";

export const STREAM_CONTENT_TYPE = "application/x-miffan-relay-v1";

export const STREAM_TYPE = {
  prelude: 0x00,
  text: 0x01,
  binary: 0x02,
  close: 0x08,
} as const;

/** 5-byte prelude written at the start of every stream so intermediaries flush headers immediately. */
export const STREAM_PRELUDE = new Uint8Array([STREAM_TYPE.prelude, 0, 0, 0, 0]);

const EMPTY = new Uint8Array(0);
const utf8Enc = new TextEncoder();
const utf8Dec = new TextDecoder("utf-8", { fatal: true, ignoreBOM: false });

export type StreamEvent =
  | { kind: "prelude" }
  | { kind: "text"; data: string }
  | { kind: "binary"; data: Uint8Array }
  | { kind: "close"; code: number; reason: string }
  | { kind: "oversize" }
  | { kind: "invalid"; reason: string };

export function encodeStreamFrame(frame: Frame): Uint8Array {
  if (typeof frame === "string") {
    const body = utf8Enc.encode(frame);
    return packFrame(STREAM_TYPE.text, body);
  }
  const body = frame instanceof Uint8Array ? frame : new Uint8Array(frame);
  return packFrame(STREAM_TYPE.binary, body);
}

export function encodeStreamClose(code: number, reason: string): Uint8Array {
  const raw = reason ? utf8Enc.encode(reason) : EMPTY;
  let end = raw.length;
  if (end > 123) {
    end = 123;
    while (end > 0 && (raw[end]! & 0xc0) === 0x80) end--;
  }
  const body = new Uint8Array(2 + end);
  body[0] = (code >>> 8) & 0xff;
  body[1] = code & 0xff;
  if (end > 0) body.set(raw.subarray(0, end), 2);
  return packFrame(STREAM_TYPE.close, body);
}

function packFrame(type: number, body: Uint8Array): Uint8Array {
  const out = new Uint8Array(5 + body.length);
  out[0] = type;
  const view = new DataView(out.buffer, out.byteOffset, 5);
  view.setUint32(1, body.length, false);
  if (body.length > 0) out.set(body, 5);
  return out;
}

export class StreamDecoder {
  private readonly hdr = new Uint8Array(5);
  private hdrLen = 0;
  private frameType = 0;
  private frameLen = 0;
  private payload = EMPTY;
  private payloadPos = 0;
  private halted = false;

  get isHalted(): boolean {
    return this.halted;
  }

  hasPartial(): boolean {
    return !this.halted && this.hdrLen > 0;
  }

  push(chunk: Uint8Array): StreamEvent[] {
    const out: StreamEvent[] = [];
    let offset = 0;

    while (offset < chunk.length && !this.halted) {
      if (this.hdrLen < 5) {
        const take = Math.min(5 - this.hdrLen, chunk.length - offset);
        this.hdr.set(chunk.subarray(offset, offset + take), this.hdrLen);
        this.hdrLen += take;
        offset += take;
        if (this.hdrLen < 5) break;

        this.frameType = this.hdr[0]!;
        this.frameLen = new DataView(this.hdr.buffer, this.hdr.byteOffset, 5).getUint32(1, false);

        if (this.frameType === STREAM_TYPE.prelude) {
          if (this.frameLen !== 0) {
            this.halted = true;
            out.push({ kind: "invalid", reason: "bad_prelude" });
            break;
          }
          this.hdrLen = 0;
          out.push({ kind: "prelude" });
          continue;
        }

        if (this.frameType === STREAM_TYPE.text) {
          if (this.frameLen > LIMITS.maxTextFrame) {
            this.halted = true;
            out.push({ kind: "oversize" });
            break;
          }
          if (this.frameLen === 0) {
            this.hdrLen = 0;
            out.push({ kind: "text", data: "" });
            continue;
          }
        } else if (this.frameType === STREAM_TYPE.binary) {
          if (this.frameLen > LIMITS.maxBinaryFrame) {
            this.halted = true;
            out.push({ kind: "oversize" });
            break;
          }
          if (this.frameLen === 0) {
            this.hdrLen = 0;
            out.push({ kind: "binary", data: EMPTY });
            continue;
          }
        } else if (this.frameType === STREAM_TYPE.close) {
          if (this.frameLen > 125 || this.frameLen === 1) {
            this.halted = true;
            out.push({ kind: "invalid", reason: "bad_close" });
            break;
          }
          if (this.frameLen === 0) {
            this.halted = true;
            this.hdrLen = 0;
            out.push({ kind: "close", code: 1000, reason: "" });
            break;
          }
        } else {
          this.halted = true;
          out.push({ kind: "invalid", reason: "bad_type" });
          break;
        }

        this.payload = new Uint8Array(this.frameLen);
        this.payloadPos = 0;
      }

      const take = Math.min(this.frameLen - this.payloadPos, chunk.length - offset);
      this.payload.set(chunk.subarray(offset, offset + take), this.payloadPos);
      this.payloadPos += take;
      offset += take;
      if (this.payloadPos < this.frameLen) break;

      const body = this.payload;
      const type = this.frameType;
      this.hdrLen = 0;
      this.frameLen = 0;
      this.payload = EMPTY;
      this.payloadPos = 0;

      if (type === STREAM_TYPE.binary) {
        out.push({ kind: "binary", data: body });
      } else if (type === STREAM_TYPE.text) {
        try {
          out.push({ kind: "text", data: utf8Dec.decode(body) });
        } catch {
          this.halted = true;
          out.push({ kind: "invalid", reason: "invalid_utf8" });
          break;
        }
      } else if (type === STREAM_TYPE.close) {
        const code = (body[0]! << 8) | body[1]!;
        let reason = "";
        try {
          reason = utf8Dec.decode(body.subarray(2));
        } catch {
          this.halted = true;
          out.push({ kind: "invalid", reason: "invalid_utf8" });
          break;
        }
        this.halted = true;
        out.push({ kind: "close", code, reason });
        break;
      }
    }

    return out;
  }
}

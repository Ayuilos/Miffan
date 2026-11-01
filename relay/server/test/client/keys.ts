// Reference implementations of the two key types in PROTOCOL §2, using WebCrypto only.

import { b64uEncode, base32Lower, sha256, hostSignedMessage, type Bytes } from "../../core/crypto.ts";

const ENC = new TextEncoder();

export interface HostKey {
  pub: Bytes;
  pubB64u: string;
  hostId: string;
  /** Sign the relay's challenge (PROTOCOL §4). */
  signChallenge(host: string, nonce: string): Promise<string>;
  /** Sign arbitrary bytes (for negative tests). */
  signRaw(data: Bytes): Promise<string>;
}

export async function newHostKey(): Promise<HostKey> {
  const pair = (await crypto.subtle.generateKey({ name: "Ed25519" }, true, ["sign", "verify"])) as CryptoKeyPair;
  const pub = new Uint8Array(await crypto.subtle.exportKey("raw", pair.publicKey)) as Bytes;
  const hostId = base32Lower(await sha256(pub)).slice(0, 26);
  const sign = async (data: Bytes) => b64uEncode(new Uint8Array(await crypto.subtle.sign({ name: "Ed25519" }, pair.privateKey, data)));
  return {
    pub,
    pubB64u: b64uEncode(pub),
    hostId,
    signChallenge: (host, nonce) => sign(hostSignedMessage(host, nonce)),
    signRaw: sign,
  };
}

// ---- ECDSA P-256 with DER signatures (PROTOCOL §5.2) ----

function derInt(raw: Uint8Array): Uint8Array {
  let i = 0;
  while (i < raw.length - 1 && raw[i] === 0) i++;
  let v = raw.slice(i);
  if (v[0]! & 0x80) v = new Uint8Array([0, ...v]);
  return new Uint8Array([0x02, v.length, ...v]);
}

/** WebCrypto returns r||s; the protocol wants a DER SEQUENCE of two INTEGERs. */
export function p1363ToDer(sig: Uint8Array): Uint8Array {
  const half = sig.length / 2;
  const body = new Uint8Array([...derInt(sig.slice(0, half)), ...derInt(sig.slice(half))]);
  return new Uint8Array([0x30, body.length, ...body]);
}

export function derToP1363(der: Uint8Array): Uint8Array {
  if (der[0] !== 0x30) throw new Error("not a DER sequence");
  let o = 2;
  const out = new Uint8Array(64);
  for (let part = 0; part < 2; part++) {
    if (der[o] !== 0x02) throw new Error("not a DER integer");
    let len = der[o + 1]!;
    let start = o + 2;
    while (len > 32 && der[start] === 0) {
      start++;
      len--;
    }
    out.set(der.slice(start, start + len), part * 32 + 32 - len);
    o = start + len;
  }
  return out;
}

export interface DeviceKey {
  pubSpki: Bytes;
  pubB64u: string;
  deviceId: string;
  /** The app's answer to the connector's challenge (PROTOCOL §5.2); DER signature, b64u. */
  signSession(hostId: string, sid: string, ch: string, nonce: string): Promise<string>;
}

export function sessionMessage(hostId: string, sid: string, ch: string, nonce: string): Bytes {
  return ENC.encode(`miffan-relay/v1 session\n${hostId}\n${sid}\n${ch}\n${nonce}`) as Bytes;
}

export async function newDeviceKey(): Promise<DeviceKey> {
  const pair = (await crypto.subtle.generateKey({ name: "ECDSA", namedCurve: "P-256" }, true, ["sign", "verify"])) as CryptoKeyPair;
  const pubSpki = new Uint8Array(await crypto.subtle.exportKey("spki", pair.publicKey)) as Bytes;
  return {
    pubSpki,
    pubB64u: b64uEncode(pubSpki),
    deviceId: base32Lower(await sha256(pubSpki)).slice(0, 26),
    async signSession(hostId, sid, ch, nonce) {
      const raw = new Uint8Array(
        await crypto.subtle.sign({ name: "ECDSA", hash: "SHA-256" }, pair.privateKey, sessionMessage(hostId, sid, ch, nonce)),
      );
      return b64uEncode(p1363ToDer(raw));
    },
  };
}

/** What the connector does: check the device's signature over the session message. */
export async function verifySession(
  devicePubSpki: Bytes,
  sigDer: Uint8Array,
  hostId: string,
  sid: string,
  ch: string,
  nonce: string,
): Promise<boolean> {
  const key = await crypto.subtle.importKey("spki", devicePubSpki, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
  return crypto.subtle.verify(
    { name: "ECDSA", hash: "SHA-256" },
    key,
    derToP1363(sigDer) as Bytes,
    sessionMessage(hostId, sid, ch, nonce),
  );
}

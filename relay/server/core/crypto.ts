// Encodings, hostId and host-signature verification. WebCrypto only, so it runs on Workers and Bun alike.

export type Bytes = Uint8Array<ArrayBuffer>;

const ENC = new TextEncoder();

export function b64uEncode(bytes: Uint8Array): string {
  let s = "";
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]!);
  return btoa(s).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Strict base64url without padding; returns null for anything else. */
export function b64uDecode(text: string): Bytes | null {
  if (!/^[A-Za-z0-9_-]*$/.test(text) || text.length % 4 === 1) return null;
  const pad = (4 - (text.length % 4)) % 4;
  try {
    const bin = atob(text.replace(/-/g, "+").replace(/_/g, "/") + "=".repeat(pad));
    const out = new Uint8Array(bin.length);
    for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
    return out;
  } catch {
    return null;
  }
}

const B32 = "abcdefghijklmnopqrstuvwxyz234567";

/** RFC 4648 base32, lowercase, no padding. */
export function base32Lower(bytes: Uint8Array): string {
  let bits = 0;
  let value = 0;
  let out = "";
  for (const b of bytes) {
    value = (value << 8) | b;
    bits += 8;
    while (bits >= 5) {
      out += B32[(value >>> (bits - 5)) & 31];
      bits -= 5;
    }
  }
  if (bits > 0) out += B32[(value << (5 - bits)) & 31];
  return out;
}

export async function sha256(data: Bytes): Promise<Bytes> {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", data));
}

export const HOST_ID_RE = /^[a-z2-7]{26}$/;
export const SID_RE = /^[a-f0-9]{32}$/;

/** hostId = base32lower(SHA-256(hostPub))[:26]  (PROTOCOL §2). */
export async function hostIdFromPub(hostPub: Bytes): Promise<string> {
  return base32Lower(await sha256(hostPub)).slice(0, 26);
}

export function randomBytes(n: number): Bytes {
  return crypto.getRandomValues(new Uint8Array(n)) as Bytes;
}

export function randomSid(): string {
  return [...randomBytes(16)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** The bytes the connector signs to prove it owns the host key (PROTOCOL §4). */
export function hostSignedMessage(host: string, nonceText: string): Bytes {
  return ENC.encode("miffan-relay/v1 host\n" + host.toLowerCase() + "\n" + nonceText) as Bytes;
}

/** Ed25519 verification of the connector's answer to the challenge. Never throws. */
export async function verifyHostSignature(
  hostPub: Bytes,
  host: string,
  nonceText: string,
  sig: Bytes,
): Promise<boolean> {
  try {
    if (hostPub.length !== 32 || sig.length !== 64) return false;
    const key = await crypto.subtle.importKey("raw", hostPub, { name: "Ed25519" }, false, ["verify"]);
    return await crypto.subtle.verify({ name: "Ed25519" }, key, sig, hostSignedMessage(host, nonceText));
  } catch {
    return false;
  }
}

/** Constant-time string comparison: hash both sides, then XOR the fixed-length digests. */
export async function secretEquals(given: string | null | undefined, expected: string | null | undefined): Promise<boolean> {
  if (!expected) return false;
  const [a, b] = await Promise.all([sha256(ENC.encode(given ?? "") as Bytes), sha256(ENC.encode(expected) as Bytes)]);
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a[i]! ^ b[i]!;
  return diff === 0 && given != null;
}

export function bearerToken(header: string | null | undefined): string | null {
  if (!header) return null;
  const m = /^Bearer (.+)$/i.exec(header.trim());
  return m ? m[1]! : null;
}

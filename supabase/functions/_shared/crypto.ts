// AES-256-GCM envelope encryption for provider tokens (WebCrypto: works in Deno/Edge Functions and Node >= 20).
// Format: v1.<keyId>.<iv>.<ciphertext+tag>, all base64url. The AAD binds a ciphertext to its owner/account row.
const enc = new TextEncoder();
const dec = new TextDecoder();

export interface RingKey { id: string; key: Uint8Array }
export interface KeyRing { current: RingKey; previous: RingKey[] }

export function b64uEncode(bytes: Uint8Array): string {
  let s = '';
  for (let i = 0; i < bytes.length; i++) s += String.fromCharCode(bytes[i]);
  return btoa(s).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
}
export function b64uDecode(s: string): Uint8Array {
  const pad = s.length % 4 === 0 ? '' : '='.repeat(4 - (s.length % 4));
  const bin = atob(s.replaceAll('-', '+').replaceAll('_', '/') + pad);
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out;
}

/** env: TOKEN_ENC_KEY="<id>:<base64 32 bytes>", optional TOKEN_ENC_KEY_PREVIOUS="<id>:<b64>,<id>:<b64>" (rotation). */
export function parseKeyRing(current: string | undefined, previous?: string): KeyRing {
  const one = (spec: string): RingKey => {
    const i = spec.indexOf(':');
    if (i < 1) throw new Error('key spec must be "<id>:<base64 key>"');
    const key = b64uDecode(spec.slice(i + 1).trim().replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, ''));
    if (key.length !== 32) throw new Error('token encryption key must be exactly 32 bytes');
    return { id: spec.slice(0, i).trim(), key };
  };
  if (!current) throw new Error('TOKEN_ENC_KEY is not configured');
  return { current: one(current), previous: (previous ?? '').split(',').map((s) => s.trim()).filter(Boolean).map(one) };
}

async function importKey(k: RingKey): Promise<CryptoKey> {
  return await crypto.subtle.importKey('raw', k.key as BufferSource, 'AES-GCM', false, ['encrypt', 'decrypt']);
}

export async function encryptToken(plain: string, ring: KeyRing, aad: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const ct = new Uint8Array(await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv: iv as BufferSource, additionalData: enc.encode(aad) as BufferSource },
    await importKey(ring.current), enc.encode(plain) as BufferSource));
  return `v1.${ring.current.id}.${b64uEncode(iv)}.${b64uEncode(ct)}`;
}

export async function decryptToken(blob: string, ring: KeyRing, aad: string): Promise<string> {
  const [v, id, ivS, ctS] = blob.split('.');
  if (v !== 'v1' || !id || !ivS || !ctS) throw new Error('unsupported token envelope');
  const key = [ring.current, ...ring.previous].find((k) => k.id === id);
  if (!key) throw new Error('unknown token encryption key id');
  const pt = await crypto.subtle.decrypt(
    { name: 'AES-GCM', iv: b64uDecode(ivS) as BufferSource, additionalData: enc.encode(aad) as BufferSource },
    await importKey(key), b64uDecode(ctS) as BufferSource);
  return dec.decode(pt);
}

/** True when the envelope was written with a key that is no longer the current one (re-encrypt on next write). */
export function needsReencrypt(blob: string, ring: KeyRing): boolean {
  return blob.split('.')[1] !== ring.current.id;
}

export function aadFor(owner: string, accountId: string, field: 'access' | 'refresh'): string {
  return `autoposter:v1:${owner}:${accountId}:${field}`;
}

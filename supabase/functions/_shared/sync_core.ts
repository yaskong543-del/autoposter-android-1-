// Deterministic two-way sync resolution (pure). Used by the `sync` Edge Function; the same rules are unit-tested.
//
// Model: every record has a server `rev` (integer, +1 per accepted write) and a tombstone (`deleted`).
// A client pushes {id, baseRev, fields, deleted, clientUpdatedAt, deviceId, opId}. The server compares baseRev to the stored rev:
//  - equal            -> fast-forward apply.
//  - different        -> three-way merge per field class below, using the *base* snapshot the client sends.
// Field classes:
//  - serverOwned: written only by the publishing engine (status, remote id, errors, progress, metrics). A client value is ignored.
//  - mergeable:   user-editable; if only one side changed a field since base, take that side; if both changed it to different
//                 values the later clientUpdatedAt wins, ties broken by larger deviceId, and the loser is recorded as a visible
//                 conflict only when the field is marked `protectedField` (text content the user could lose).
//  - delete vs edit: irreconcilable -> keep the record (resurrect), mark conflict 'delete_vs_edit' for the user to decide.
export type Json = string | number | boolean | null | Json[] | { [k: string]: Json };
export type Fields = Record<string, Json>;

export interface FieldPolicy { serverOwned: ReadonlySet<string>; mergeable: ReadonlySet<string>; protectedFields: ReadonlySet<string> }

export const POST_POLICY: FieldPolicy = {
  serverOwned: new Set(['status', 'progress', 'attempts', 'remote_id', 'error', 'finished_at', 'views', 'likes', 'comments', 'size_bytes']),
  mergeable: new Set(['name', 'text', 'scheduled_at', 'account_label', 'platform', 'campaign_id']),
  protectedFields: new Set(['text', 'scheduled_at']),
};
export const DOC_POLICY: FieldPolicy = {
  serverOwned: new Set(),
  mergeable: new Set(['*']),
  protectedFields: new Set(['*']),
};

export interface ServerRecord { id: string; rev: number; deleted: boolean; fields: Fields; updatedAtMs: number; deviceId?: string }
export interface ClientChange {
  id: string; baseRev: number; baseFields: Fields; fields: Fields; deleted: boolean; clientUpdatedAtMs: number; deviceId: string; opId: string;
}
export interface Conflict { id: string; kind: 'field' | 'delete_vs_edit'; field?: string; serverValue?: Json; clientValue?: Json; resolvedWith: 'server' | 'client' | 'kept_record' }

export type MergeResult =
  | { action: 'noop'; rev: number }                                    // nothing changed
  | { action: 'apply'; rev: number; fields: Fields; deleted: boolean; conflicts: Conflict[] }
  | { action: 'reject'; reason: 'stale_tombstone' };

const eq = (a: Json | undefined, b: Json | undefined): boolean => JSON.stringify(a ?? null) === JSON.stringify(b ?? null);
const isMergeable = (p: FieldPolicy, k: string): boolean => p.mergeable.has('*') ? !p.serverOwned.has(k) : p.mergeable.has(k);
const isProtected = (p: FieldPolicy, k: string): boolean => p.protectedFields.has('*') || p.protectedFields.has(k);

/** Later timestamp wins; equal timestamps -> lexicographically larger deviceId wins (deterministic on every replica). */
export function clientWins(c: { atMs: number; deviceId: string }, s: { atMs: number; deviceId?: string }): boolean {
  if (c.atMs !== s.atMs) return c.atMs > s.atMs;
  return c.deviceId > (s.deviceId ?? '');
}

export function mergeChange(server: ServerRecord | null, c: ClientChange, policy: FieldPolicy): MergeResult {
  // New record.
  if (!server) {
    if (c.deleted) return { action: 'reject', reason: 'stale_tombstone' };
    const fields: Fields = {};
    for (const [k, v] of Object.entries(c.fields)) if (isMergeable(policy, k)) fields[k] = v;
    return { action: 'apply', rev: 1, fields, deleted: false, conflicts: [] };
  }

  const conflicts: Conflict[] = [];
  const fastForward = c.baseRev === server.rev;

  // Deletion handling.
  if (c.deleted) {
    if (server.deleted) return { action: 'noop', rev: server.rev };
    if (!fastForward) {
      // Server changed the record after the client's base: do not silently drop someone's edit.
      const serverChanged = Object.keys(server.fields).some((k) => isMergeable(policy, k) && !eq(server.fields[k], c.baseFields[k]));
      if (serverChanged) {
        return { action: 'apply', rev: server.rev, fields: server.fields, deleted: false,
          conflicts: [{ id: c.id, kind: 'delete_vs_edit', resolvedWith: 'kept_record' }] };
      }
    }
    return { action: 'apply', rev: server.rev + 1, fields: server.fields, deleted: true, conflicts };
  }
  if (server.deleted) {
    // Client edited a record that was deleted elsewhere.
    if (fastForward) return { action: 'apply', rev: server.rev + 1, fields: { ...server.fields, ...pickMergeable(c.fields, policy) }, deleted: false, conflicts };
    return { action: 'apply', rev: server.rev + 1, fields: { ...server.fields, ...pickMergeable(c.fields, policy) }, deleted: false,
      conflicts: [{ id: c.id, kind: 'delete_vs_edit', resolvedWith: 'kept_record' }] };
  }

  const out: Fields = { ...server.fields };
  let changed = false;
  for (const [k, cv] of Object.entries(c.fields)) {
    if (!isMergeable(policy, k)) continue;                       // server-owned or unknown: ignore client value
    const sv = server.fields[k];
    if (eq(cv, sv)) continue;                                    // already equal
    if (fastForward) { out[k] = cv; changed = true; continue; }
    const bv = c.baseFields[k];
    const clientChanged = !eq(cv, bv), serverChanged = !eq(sv, bv);
    if (!clientChanged) continue;                                // only the server changed it: keep server
    if (!serverChanged) { out[k] = cv; changed = true; continue; } // only the client changed it
    const win = clientWins({ atMs: c.clientUpdatedAtMs, deviceId: c.deviceId }, { atMs: server.updatedAtMs, deviceId: server.deviceId });
    if (win) { out[k] = cv; changed = true; }
    if (isProtected(policy, k)) conflicts.push({ id: c.id, kind: 'field', field: k, serverValue: sv ?? null, clientValue: cv, resolvedWith: win ? 'client' : 'server' });
  }
  if (!changed && conflicts.length === 0) return { action: 'noop', rev: server.rev };
  return { action: 'apply', rev: changed ? server.rev + 1 : server.rev, fields: out, deleted: false, conflicts };
}

function pickMergeable(f: Fields, p: FieldPolicy): Fields {
  const o: Fields = {};
  for (const [k, v] of Object.entries(f)) if (isMergeable(p, k)) o[k] = v;
  return o;
}

/** Client outbox helper: collapse queued ops per record id so only the latest state is pushed (ops stay idempotent by opId). */
export interface OutboxOp { opId: string; id: string; seq: number; change: Omit<ClientChange, 'opId'> }
export function collapseOutbox(ops: OutboxOp[]): OutboxOp[] {
  const latest = new Map<string, OutboxOp>();
  for (const o of [...ops].sort((a, b) => a.seq - b.seq)) {
    const prev = latest.get(o.id);
    // Keep the earliest baseRev/baseFields so the three-way merge sees the true common ancestor.
    latest.set(o.id, prev ? { ...o, change: { ...o.change, baseRev: prev.change.baseRev, baseFields: prev.change.baseFields } } : o);
  }
  return [...latest.values()].sort((a, b) => a.seq - b.seq);
}

/** Tombstone retention: a tombstone may be purged only after every non-revoked device has synced past it. */
export function purgeableTombstones(tombstones: Array<{ id: string; serverSeq: number }>, deviceCursors: number[]): string[] {
  if (deviceCursors.length === 0) return [];
  const min = Math.min(...deviceCursors);
  return tombstones.filter((t) => t.serverSeq <= min).map((t) => t.id);
}

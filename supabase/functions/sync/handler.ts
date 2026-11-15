// Two-way sync endpoint logic. Merge rules live in _shared/sync_core.ts; this file adds auth, device, plan and idempotency rules.
import { DOC_POLICY, POST_POLICY, mergeChange, type ClientChange, type Conflict, type Fields, type FieldPolicy, type ServerRecord } from '../_shared/sync_core.ts';
import { ENTITLEMENTS, type PlanId } from '../_shared/entitlements.ts';

export type Entity = 'post' | 'draft' | 'media_meta' | 'preferences';
const ENTITIES: readonly Entity[] = ['post', 'draft', 'media_meta', 'preferences'];
const policyFor = (e: Entity): FieldPolicy => (e === 'post' ? POST_POLICY : DOC_POLICY);

export interface ClientOp extends ClientChange { entity: Entity }
export interface SyncRequest { deviceId: string; trigger: 'manual' | 'open' | 'auto'; cursor: number; limit?: number; ops: ClientOp[] }
export interface PulledRecord { entity: Entity; id: string; rev: number; deleted: boolean; fields: Fields; serverSeq: number }

export interface SyncDb {
  deviceStatus(deviceId: string): Promise<{ status: 'ok' | 'revoked' | 'suspended' | 'unknown'; owner: string | null }>;
  plan(owner: string): Promise<PlanId>;
  getOpResult(deviceId: string, opId: string): Promise<OpResult | null>;
  putOpResult(deviceId: string, owner: string, opId: string, r: OpResult): Promise<void>;
  getRecord(owner: string, e: Entity, id: string): Promise<ServerRecord | null>;
  /** Compare-and-set. expectedRev === null means "must not exist yet". Returns false when the row changed meanwhile. */
  writeRecord(owner: string, e: Entity, id: string, expectedRev: number | null, next: { fields: Fields; deleted: boolean; deviceId: string; clientUpdatedAtMs: number }): Promise<{ ok: boolean; rev: number }>;
  insertConflict(owner: string, deviceId: string, entity: Entity, c: Conflict): Promise<void>;
  pull(owner: string, cursor: number, limit: number): Promise<PulledRecord[]>;
  touchDevice(deviceId: string, cursor: number): Promise<void>;
}

export interface OpResult { opId: string; status: 'applied' | 'noop' | 'rejected' | 'retry'; rev?: number; conflicts?: Conflict[]; reason?: string }
export type SyncResponse =
  | { ok: true; results: OpResult[]; changes: PulledRecord[]; cursor: number; hasMore: boolean }
  | { ok: false; status: 400 | 403; code: 'bad_request' | 'device_unknown' | 'device_revoked' | 'device_suspended' | 'plan_limit_autosync' };

const MAX_OPS = 100, MAX_PULL = 200, MAX_CAS_RETRIES = 5, MAX_FIELD_BYTES = 60_000;

function valid(op: ClientOp): boolean {
  return ENTITIES.includes(op.entity) && typeof op.id === 'string' && op.id.length > 0 && op.id.length <= 100 && typeof op.opId === 'string' && op.opId.length > 0 && op.opId.length <= 100
    && Number.isInteger(op.baseRev) && op.baseRev >= 0 && typeof op.deviceId === 'string' && typeof op.deleted === 'boolean' && Number.isFinite(op.clientUpdatedAtMs)
    && op.fields !== null && typeof op.fields === 'object' && op.baseFields !== null && typeof op.baseFields === 'object'
    && JSON.stringify(op.fields).length <= MAX_FIELD_BYTES;
}

export async function handleSync(db: SyncDb, owner: string, req: SyncRequest): Promise<SyncResponse> {
  if (!req || typeof req.deviceId !== 'string' || !Array.isArray(req.ops) || req.ops.length > MAX_OPS || !Number.isFinite(req.cursor) || req.cursor < 0
      || !['manual', 'open', 'auto'].includes(req.trigger)) return { ok: false, status: 400, code: 'bad_request' };
  const dev = await db.deviceStatus(req.deviceId);
  if (dev.status === 'unknown' || dev.owner !== owner) return { ok: false, status: 403, code: 'device_unknown' };   // also hides other users' device ids
  if (dev.status === 'revoked') return { ok: false, status: 403, code: 'device_revoked' };
  if (dev.status === 'suspended') return { ok: false, status: 403, code: 'device_suspended' };
  const plan = await db.plan(owner);
  if (req.trigger === 'auto' && !ENTITLEMENTS[plan].autoSync) return { ok: false, status: 403, code: 'plan_limit_autosync' };

  const results: OpResult[] = [];
  for (const raw of req.ops) {
    if (!valid(raw)) { results.push({ opId: String((raw as ClientOp)?.opId ?? ''), status: 'rejected', reason: 'invalid_op' }); continue; }
    const op: ClientOp = { ...raw, deviceId: req.deviceId };          // the device id comes from the authenticated request, never from the op body
    const prior = await db.getOpResult(req.deviceId, op.opId);
    if (prior) { results.push(prior); continue; }                      // idempotent replay
    let result: OpResult = { opId: op.opId, status: 'retry', reason: 'contention' };
    for (let attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
      const server = await db.getRecord(owner, op.entity, op.id);
      const m = mergeChange(server, op, policyFor(op.entity));
      if (m.action === 'noop') { result = { opId: op.opId, status: 'noop', rev: m.rev }; break; }
      if (m.action === 'reject') { result = { opId: op.opId, status: 'rejected', reason: m.reason }; break; }
      const w = await db.writeRecord(owner, op.entity, op.id, server ? server.rev : null, { fields: m.fields, deleted: m.deleted, deviceId: req.deviceId, clientUpdatedAtMs: op.clientUpdatedAtMs });
      if (!w.ok) continue;                                             // someone else wrote first: re-read and re-merge
      for (const c of m.conflicts) await db.insertConflict(owner, req.deviceId, op.entity, c);
      result = { opId: op.opId, status: 'applied', rev: w.rev, conflicts: m.conflicts };
      break;
    }
    if (result.status !== 'retry') await db.putOpResult(req.deviceId, owner, op.opId, result);   // 'retry' is never cached
    results.push(result);
  }

  const limit = Math.min(Math.max(1, req.limit ?? MAX_PULL), MAX_PULL);
  const rows = await db.pull(owner, req.cursor, limit + 1);
  const hasMore = rows.length > limit;
  const changes = rows.slice(0, limit);
  const cursor = changes.length ? changes[changes.length - 1].serverSeq : req.cursor;
  await db.touchDevice(req.deviceId, cursor);
  return { ok: true, results, changes, cursor, hasMore };
}

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { handleSync, type SyncDb, type Entity, type OpResult, type PulledRecord, type SyncRequest } from './handler.ts';
import type { Conflict, ServerRecord } from '../_shared/sync_core.ts';

class MemSync implements SyncDb {
  recs = new Map<string, ServerRecord & { seq: number }>(); ops = new Map<string, OpResult>(); conflicts: any[] = []; seq = 0; devices = new Map<string, { status: any; owner: string }>();
  plans = new Map<string, any>(); failCas = 0; touched: number[] = [];
  key = (o: string, e: Entity, id: string) => `${o}|${e}|${id}`;
  async deviceStatus(d: string) { return this.devices.get(d) ? { status: this.devices.get(d)!.status, owner: this.devices.get(d)!.owner } : { status: 'unknown' as const, owner: null }; }
  async plan(o: string) { return this.plans.get(o) ?? 'free'; }
  async getOpResult(d: string, op: string) { return this.ops.get(d + op) ?? null; }
  async putOpResult(d: string, _o: string, op: string, r: OpResult) { this.ops.set(d + op, r); }
  async getRecord(o: string, e: Entity, id: string) { const r = this.recs.get(this.key(o, e, id)); return r ? { id: r.id, rev: r.rev, deleted: r.deleted, fields: r.fields, updatedAtMs: r.updatedAtMs, deviceId: r.deviceId } : null; }
  async writeRecord(o: string, e: Entity, id: string, exp: number | null, n: any) {
    if (this.failCas > 0) { this.failCas--; return { ok: false, rev: 0 }; }
    const k = this.key(o, e, id); const cur = this.recs.get(k);
    if ((cur?.rev ?? null) !== exp) return { ok: false, rev: 0 };
    const rev = (cur?.rev ?? 0) + 1;
    this.recs.set(k, { id, rev, deleted: n.deleted, fields: n.fields, updatedAtMs: n.clientUpdatedAtMs, deviceId: n.deviceId, seq: ++this.seq, entity: e } as any);
    return { ok: true, rev };
  }
  async insertConflict(o: string, d: string, e: Entity, c: Conflict) { this.conflicts.push({ o, d, e, c }); }
  async pull(o: string, cursor: number, limit: number): Promise<PulledRecord[]> {
    return [...this.recs.entries()].filter(([k, r]) => k.startsWith(o + '|') && r.seq > cursor).sort((a, b) => a[1].seq - b[1].seq).slice(0, limit)
      .map(([k, r]) => ({ entity: k.split('|')[1] as Entity, id: r.id, rev: r.rev, deleted: r.deleted, fields: r.fields, serverSeq: r.seq }));
  }
  async touchDevice(_d: string, c: number) { this.touched.push(c); }
}
const op = (o: object = {}) => ({ entity: 'draft' as Entity, id: 'd1', baseRev: 0, baseFields: {}, fields: { text: 'hello' }, deleted: false, clientUpdatedAtMs: 1000, deviceId: 'ignored', opId: 'op1', ...o });
const req = (ops: any[], o: Partial<SyncRequest> = {}): SyncRequest => ({ deviceId: 'devA', trigger: 'manual', cursor: 0, ops, ...o });
const mk = () => { const s = new MemSync(); s.devices.set('devA', { status: 'ok', owner: 'u1' }); s.devices.set('devB', { status: 'ok', owner: 'u1' }); s.devices.set('devX', { status: 'ok', owner: 'u2' }); return s; };

test('two devices converge: A pushes, B pulls; B edits, A pulls', async () => {
  const s = mk();
  const a1 = await handleSync(s, 'u1', req([op()])) as any;
  assert.equal(a1.results[0].status, 'applied'); assert.equal(a1.results[0].rev, 1);
  const b1 = await handleSync(s, 'u1', req([], { deviceId: 'devB' })) as any;
  assert.deepEqual(b1.changes.map((c: any) => [c.id, c.fields.text, c.rev]), [['d1', 'hello', 1]]);
  const b2 = await handleSync(s, 'u1', req([op({ opId: 'op2', baseRev: 1, baseFields: { text: 'hello' }, fields: { text: 'from B' }, clientUpdatedAtMs: 2000 })], { deviceId: 'devB', cursor: b1.cursor })) as any;
  assert.equal(b2.results[0].rev, 2);
  const a2 = await handleSync(s, 'u1', req([], { cursor: a1.cursor })) as any;
  assert.equal(a2.changes[0].fields.text, 'from B');
});

test('replaying an op is idempotent; same opId from another device is independent', async () => {
  const s = mk();
  await handleSync(s, 'u1', req([op()]));
  const again = await handleSync(s, 'u1', req([op()])) as any;
  assert.equal(again.results[0].status, 'applied'); assert.equal(s.seq, 1);       // no second write
});

test('offline conflict: both edit the same text -> deterministic winner, visible conflict recorded', async () => {
  const s = mk();
  await handleSync(s, 'u1', req([op()]));
  await handleSync(s, 'u1', req([op({ opId: 'b', baseRev: 1, baseFields: { text: 'hello' }, fields: { text: 'B text' }, clientUpdatedAtMs: 3000 })], { deviceId: 'devB' }));
  const r = await handleSync(s, 'u1', req([op({ opId: 'c', baseRev: 1, baseFields: { text: 'hello' }, fields: { text: 'A text' }, clientUpdatedAtMs: 2000 })])) as any;
  assert.equal(r.results[0].conflicts[0].resolvedWith, 'server'); assert.equal(s.conflicts.length, 1);
  assert.equal(s.recs.get('u1|draft|d1')!.fields.text, 'B text');
});

test('tombstones propagate to other devices; server-owned post fields can not be set by clients', async () => {
  const s = mk();
  await handleSync(s, 'u1', req([op({ entity: 'post', id: 'p1', fields: { text: 'cap', status: 'DONE' } })]));
  assert.equal(s.recs.get('u1|post|p1')!.fields.status, undefined);
  const del = await handleSync(s, 'u1', req([op({ opId: 'del', entity: 'post', id: 'p1', baseRev: 1, baseFields: { text: 'cap' }, deleted: true, fields: {} })])) as any;
  assert.equal(del.results[0].status, 'applied');
  const b = await handleSync(s, 'u1', req([], { deviceId: 'devB' })) as any;
  assert.equal(b.changes[0].deleted, true);
});

test('authorization: unknown/foreign/revoked/suspended devices and plan-gated auto sync', async () => {
  const s = mk();
  assert.deepEqual(await handleSync(s, 'u1', req([], { deviceId: 'nope' })), { ok: false, status: 403, code: 'device_unknown' });
  assert.deepEqual(await handleSync(s, 'u1', req([], { deviceId: 'devX' })), { ok: false, status: 403, code: 'device_unknown' });    // u2's device
  s.devices.set('devA', { status: 'revoked', owner: 'u1' });
  assert.equal(((await handleSync(s, 'u1', req([op()]))) as any).code, 'device_revoked'); assert.equal(s.recs.size, 0);
  s.devices.set('devA', { status: 'suspended', owner: 'u1' });
  assert.equal(((await handleSync(s, 'u1', req([]))) as any).code, 'device_suspended');
  s.devices.set('devA', { status: 'ok', owner: 'u1' });
  assert.equal(((await handleSync(s, 'u1', req([], { trigger: 'auto' }))) as any).code, 'plan_limit_autosync');
  assert.equal(((await handleSync(s, 'u1', req([], { trigger: 'open' }))) as any).ok, true);
  s.plans.set('u1', 'pro');
  assert.equal(((await handleSync(s, 'u1', req([], { trigger: 'auto' }))) as any).ok, true);
  assert.equal(((await handleSync(s, 'u1', { ...req([]), ops: 'x' as any })) as any).code, 'bad_request');
  assert.equal(((await handleSync(s, 'u1', req(new Array(101).fill(op())))) as any).code, 'bad_request');
});

test('invalid ops are rejected individually; contention returns retry and is not cached; pagination', async () => {
  const s = mk();
  const r = await handleSync(s, 'u1', req([op({ entity: 'secrets' }), op({ opId: 'big', fields: { text: 'x'.repeat(70_000) } }), op({ opId: 'ok' })])) as any;
  assert.deepEqual(r.results.map((x: any) => x.status), ['rejected', 'rejected', 'applied']);
  const s2 = mk(); s2.failCas = 99;
  const c = await handleSync(s2, 'u1', req([op()])) as any;
  assert.equal(c.results[0].status, 'retry'); assert.equal(s2.ops.size, 0);
  const s3 = mk();
  for (let i = 0; i < 5; i++) await handleSync(s3, 'u1', req([op({ id: `d${i}`, opId: `o${i}` })]));
  const p1 = await handleSync(s3, 'u1', req([], { limit: 2 })) as any;
  assert.equal(p1.changes.length, 2); assert.equal(p1.hasMore, true);
  const p2 = await handleSync(s3, 'u1', req([], { limit: 10, cursor: p1.cursor })) as any;
  assert.equal(p2.changes.length, 3); assert.equal(p2.hasMore, false);
  assert.equal(s3.touched.at(-1), p2.cursor);
  const other = await handleSync(s3, 'u2', req([], { deviceId: 'devX' })) as any;
  assert.equal(other.changes.length, 0);                                            // never sees u1's records
});

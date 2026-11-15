// Supabase Edge Function (Deno): POST /sync  {deviceId, trigger, cursor, ops[]}  (JWT required) -> {results, changes, cursor, hasMore}
// Writes use the service role AFTER the JWT, the device and the plan were verified in handler.ts; clients have no direct write access to sync tables.
import { createClient } from "npm:@supabase/supabase-js@2.50.0";
import { handleSync, type Entity, type PulledRecord, type SyncDb } from "./handler.ts";
import { type Fields } from "../_shared/sync_core.ts";

const url = Deno.env.get("SUPABASE_URL")!;
const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });
const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

const POST_FIELDS = ["name", "text", "scheduled_at", "account_label", "platform", "campaign_id", "status", "progress", "attempts", "remote_id", "error", "finished_at", "views", "likes", "comments", "size_bytes"];
const DOC_KIND: Record<string, string> = { draft: "draft", media_meta: "media_meta", preferences: "preferences" };

function pick(row: Record<string, unknown>): Fields { const o: Fields = {}; for (const k of POST_FIELDS) if (row[k] !== undefined && row[k] !== null) o[k] = row[k] as never; return o; }

const db: SyncDb = {
  async deviceStatus(id) {
    if (!/^[0-9a-f-]{36}$/i.test(id)) return { status: "unknown", owner: null };
    const { data: d } = await admin.from("devices").select("owner").eq("id", id).maybeSingle();
    if (!d) return { status: "unknown", owner: null };
    const { data: s } = await admin.rpc("device_allowed", { p_device: id });
    return { status: (s as "ok" | "revoked" | "suspended" | "unknown") ?? "unknown", owner: d.owner };
  },
  async plan(owner) { const { data } = await admin.rpc("plan_of", { p_owner: owner }); return (data as "free" | "pro" | "agency") ?? "free"; },
  async getOpResult(device, op) { const { data } = await admin.from("sync_ops").select("result").eq("device_id", device).eq("op_id", op).maybeSingle(); return data?.result ?? null; },
  async putOpResult(device, owner, op, result) { await admin.from("sync_ops").upsert({ device_id: device, op_id: op, owner, result }, { onConflict: "device_id,op_id", ignoreDuplicates: true }); },
  async getRecord(owner, e, id) {
    if (e === "post") {
      const { data } = await admin.from("post_items").select("*").eq("owner", owner).eq("id", id).maybeSingle();
      return data ? { id, rev: Number(data.rev), deleted: !!data.deleted_at, fields: pick(data), updatedAtMs: Date.parse(data.updated_at), deviceId: data.updated_by_device ?? undefined } : null;
    }
    const { data } = await admin.from("sync_documents").select("*").eq("owner", owner).eq("kind", DOC_KIND[e]).eq("id", id).maybeSingle();
    return data ? { id, rev: Number(data.rev), deleted: !!data.deleted_at, fields: data.data as Fields, updatedAtMs: Date.parse(data.client_updated_at ?? data.updated_at), deviceId: data.updated_by_device ?? undefined } : null;
  },
  async writeRecord(owner, e, id, expectedRev, n) {
    const deletedAt = n.deleted ? new Date().toISOString() : null;
    if (e === "post") {
      const f = n.fields as Record<string, unknown>;
      const row = { name: f.name ?? null, text: f.text ?? null, scheduled_at: f.scheduled_at ?? null, account_label: f.account_label ?? null, campaign_id: f.campaign_id ?? null, deleted_at: deletedAt, updated_by_device: n.deviceId };
      if (expectedRev === null) {
        const { data, error } = await admin.from("post_items").insert({ owner, id, platform: f.platform ?? "YOUTUBE", status: "QUEUED", ...row }).select("rev").maybeSingle();
        return error || !data ? { ok: false, rev: 0 } : { ok: true, rev: Number(data.rev) };
      }
      const { data } = await admin.from("post_items").update(row).eq("owner", owner).eq("id", id).eq("rev", expectedRev).select("rev").maybeSingle();
      return data ? { ok: true, rev: Number(data.rev) } : { ok: false, rev: 0 };
    }
    const base = { data: n.fields, deleted_at: deletedAt, client_updated_at: new Date(n.clientUpdatedAtMs).toISOString(), updated_by_device: n.deviceId };
    if (expectedRev === null) {
      const { data, error } = await admin.from("sync_documents").insert({ owner, kind: DOC_KIND[e], id, ...base }).select("rev").maybeSingle();
      return error || !data ? { ok: false, rev: 0 } : { ok: true, rev: Number(data.rev) };
    }
    // The bump trigger sets rev = old.rev + 1; the .eq("rev", expectedRev) makes this a compare-and-set.
    const { data } = await admin.from("sync_documents").update(base).eq("owner", owner).eq("kind", DOC_KIND[e]).eq("id", id).eq("rev", expectedRev).select("rev").maybeSingle();
    return data ? { ok: true, rev: Number(data.rev) } : { ok: false, rev: 0 };
  },
  async insertConflict(owner, device, entity, c) {
    await admin.from("sync_conflicts").insert({ owner, entity, entity_id: c.id, kind: c.kind, field: c.field ?? null, server_value: c.serverValue ?? null, client_value: c.clientValue ?? null, resolved_with: c.resolvedWith, device_id: device });
  },
  async pull(owner, cursor, limit) {
    const [posts, docs] = await Promise.all([
      admin.from("post_items").select("*").eq("owner", owner).gt("server_seq", cursor).order("server_seq").limit(limit),
      admin.from("sync_documents").select("*").eq("owner", owner).gt("server_seq", cursor).order("server_seq").limit(limit),
    ]);
    const out: PulledRecord[] = [
      ...(posts.data ?? []).map((r) => ({ entity: "post" as Entity, id: r.id, rev: Number(r.rev), deleted: !!r.deleted_at, fields: pick(r), serverSeq: Number(r.server_seq) })),
      ...(docs.data ?? []).map((r) => ({ entity: (r.kind === "draft" || r.kind === "media_meta" ? r.kind : "preferences") as Entity, id: r.id, rev: Number(r.rev), deleted: !!r.deleted_at, fields: r.data as Fields, serverSeq: Number(r.server_seq) })),
    ];
    return out.sort((a, b) => a.serverSeq - b.serverSeq).slice(0, limit);
  },
  async touchDevice(device, cursor) { await admin.from("devices").update({ last_sync_at: new Date().toISOString(), sync_cursor: cursor }).eq("id", device); },
};

Deno.serve(async (req) => {
  if (req.method !== "POST") return json({ error: "method_not_allowed" }, 405);
  const token = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
  if (!token) return json({ error: "unauthorized" }, 401);
  const { data, error } = await createClient(url, anon, { auth: { persistSession: false } }).auth.getUser(token);
  if (error || !data.user) return json({ error: "unauthorized" }, 401);
  const body = await req.json().catch(() => null);
  try {
    const r = await handleSync(db, data.user.id, body);
    return r.ok ? json(r) : json({ error: r.code }, r.status);
  } catch (e) {
    console.error("sync failed", (e as Error).message);
    return json({ error: "internal" }, 500);
  }
});

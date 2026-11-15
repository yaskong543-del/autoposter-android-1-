// Supabase Edge Function (Deno): the scheduler tick. Invoke every minute (pg_cron + pg_net, see supabase/README.md) with
//   Authorization: Bearer <WORKER_SECRET>
// Deploy: `supabase functions deploy publish-worker --no-verify-jwt`. Secrets: WORKER_SECRET, TOKEN_ENC_KEY(+_PREVIOUS), <PLATFORM>_CLIENT_ID/_SECRET, LINKEDIN_API_VERSION (optional).
// Realistic latency: a job starts at the first tick after run_at (typically within ~1-2 minutes) and may take several ticks to upload
// and for the platform to finish processing. Exact-minute delivery is NOT promised.
import { createClient } from "npm:@supabase/supabase-js@2.50.0";
import { runMaintenance, tick, type Rpc } from "./handler.ts";
import { parseKeyRing } from "../_shared/crypto.ts";
import { safeEqual } from "../_shared/oauth.ts";
import { type Platform } from "../_shared/types.ts";
import { type AccountRow, type OAuthDeps } from "../oauth/handler.ts";

const url = Deno.env.get("SUPABASE_URL")!;
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const secret = Deno.env.get("WORKER_SECRET") ?? "";
const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });
const rpc: Rpc = async (fn, args) => { const r = await admin.rpc(fn, args ?? {}); return { data: r.data, error: r.error ? { message: r.error.message, code: r.error.code } : null }; };

const COLS = "id, owner, platform, external_id, status, access_token_enc, refresh_token_enc, token_expires_at";
const oauth: OAuthDeps = {
  db: {
    insertState: async () => { throw new Error("n/a"); }, takeState: async () => null, findAccount: async () => null, upsertAccount: async () => { throw new Error("n/a"); },
    getAccount: async (owner, id) => (await admin.from("provider_accounts").select(COLS).eq("owner", owner).eq("id", id).maybeSingle()).data as AccountRow | null,
    saveTokens: async (id, t) => { await admin.from("provider_accounts").update({ ...t, last_refresh_at: new Date().toISOString() }).eq("id", id); },
    setStatus: async (id, status, code) => { await admin.from("provider_accounts").update({ status, last_error_code: code }).eq("id", id); },
    clearTokens: async () => {},
  },
  ring: parseKeyRing(Deno.env.get("TOKEN_ENC_KEY"), Deno.env.get("TOKEN_ENC_KEY_PREVIOUS")), fetch, now: () => Date.now(), uuid: () => crypto.randomUUID(),
  credentials: (p: Platform) => { const id = Deno.env.get(`${p}_CLIENT_ID`), s = Deno.env.get(`${p}_CLIENT_SECRET`); return id && s ? { clientId: id, clientSecret: s } : null; },
  redirectUri: "", resultUrl: () => "",
};

Deno.serve(async (req) => {
  const auth = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "") ?? "";
  if (!secret || !safeEqual(auth, secret)) return new Response("unauthorized", { status: 401 });
  const loadAccount = async (id: string) => (await admin.from("provider_accounts").select(COLS).eq("id", id).maybeSingle()).data as AccountRow | null;
  const signUrl = async (path: string, ttl: number) => {
    const { data, error } = await admin.storage.from("publish-media").createSignedUrl(path, ttl);
    if (error || !data) throw new Error("sign failed");
    return data.signedUrl;
  };
  const workerId = `w-${crypto.randomUUID().slice(0, 8)}`;
  try {
    const t = await tick({ rpc, oauth, fetch, loadAccount, signUrl, now: () => Date.now() }, { workerId, linkedinVersion: Deno.env.get("LINKEDIN_API_VERSION") ?? undefined });
    let maint: unknown = null;
    if (new URL(req.url).searchParams.get("maintenance") === "1") {
      maint = await runMaintenance({
        rpc, oauth, now: () => Date.now(),
        listAccountsForSweep: async (within) => ((await admin.from("provider_accounts").select(COLS).in("status", ["CONNECTED", "EXPIRING"]).not("token_expires_at", "is", null)
          .lt("token_expires_at", new Date(Date.now() + within).toISOString()).limit(200)).data ?? []) as AccountRow[],
        listUpcomingJobs: async (within) => ((await admin.from("publish_jobs").select("id, owner, platform, run_at").eq("status", "scheduled")
          .gt("run_at", new Date().toISOString()).lt("run_at", new Date(Date.now() + within).toISOString()).limit(200)).data ?? []),
        setAccountStatus: async (id, status) => { await admin.from("provider_accounts").update({ status }).eq("id", id); },
      });
    }
    return new Response(JSON.stringify({ worker: workerId, ...t, outcomes: t.outcomes.length, maintenance: maint }), { headers: { "content-type": "application/json" } });
  } catch (e) {
    console.error("tick failed", (e as Error).message);          // message only: no tokens, no payloads
    return new Response(JSON.stringify({ error: "tick_failed" }), { status: 500 });
  }
});

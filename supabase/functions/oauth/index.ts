// Supabase Edge Function (Deno): OAuth for the seven publishing platforms.
//   POST /oauth/start        (JWT)  {platform}            -> {url}   open in a browser / Custom Tab
//   GET  /oauth/callback     (public, provider redirect)  -> 302 to OAUTH_RESULT_URL
//   POST /oauth/disconnect   (JWT)  {accountId}           -> {ok}
// Deploy: `supabase functions deploy oauth --no-verify-jwt` (the callback has no JWT; start/disconnect verify the JWT themselves).
// Secrets (supabase secrets set ...): TOKEN_ENC_KEY="<id>:<base64 32 bytes>", [TOKEN_ENC_KEY_PREVIOUS], OAUTH_RESULT_URL,
//   and per provider: <PLATFORM>_CLIENT_ID / <PLATFORM>_CLIENT_SECRET (YOUTUBE, INSTAGRAM, THREADS, FACEBOOK, TIKTOK, X, LINKEDIN).
import { createClient } from "npm:@supabase/supabase-js@2.50.0";
import { disconnectAccount, finishOAuth, startOAuth, type AccountRow, type OAuthDb, type OAuthDeps } from "./handler.ts";
import { parseKeyRing } from "../_shared/crypto.ts";
import { type Platform } from "../_shared/types.ts";

const url = Deno.env.get("SUPABASE_URL")!;
const anon = Deno.env.get("SUPABASE_ANON_KEY")!;
const service = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const admin = createClient(url, service, { auth: { persistSession: false, autoRefreshToken: false } });
const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { "content-type": "application/json", "cache-control": "no-store" } });

const COLS = "id, owner, platform, external_id, status, access_token_enc, refresh_token_enc, token_expires_at";
const db: OAuthDb = {
  async insertState(r) { const { error } = await admin.from("oauth_states").insert(r); if (error) throw new Error(error.message); },
  async takeState(hash, nowIso) {
    const { data, error } = await admin.from("oauth_states").update({ used_at: nowIso }).eq("state_hash", hash).is("used_at", null).select().maybeSingle();
    if (error) throw new Error(error.message);
    return data;
  },
  async findAccount(owner, platform, ext) { const { data } = await admin.from("provider_accounts").select(COLS).eq("owner", owner).eq("platform", platform).eq("external_id", ext).maybeSingle(); return data as AccountRow | null; },
  async getAccount(owner, id) { const { data } = await admin.from("provider_accounts").select(COLS).eq("owner", owner).eq("id", id).maybeSingle(); return data as AccountRow | null; },
  async upsertAccount(a) {
    const { error } = await admin.from("provider_accounts").upsert({ ...a, status: "CONNECTED", last_error_code: null, blocked_until: null, last_refresh_at: new Date().toISOString() }, { onConflict: "owner,platform,external_id" });
    if (error) throw Object.assign(new Error(error.message), { code: error.code });
  },
  async saveTokens(id, t) { const { error } = await admin.from("provider_accounts").update({ ...t, last_refresh_at: new Date().toISOString() }).eq("id", id); if (error) throw new Error(error.message); },
  async setStatus(id, status, code) { await admin.from("provider_accounts").update({ status, last_error_code: code }).eq("id", id); },
  async clearTokens(id) { await admin.from("provider_accounts").update({ access_token_enc: null, refresh_token_enc: null, token_expires_at: null }).eq("id", id); },
};

function deps(req: Request): OAuthDeps {
  return {
    db, ring: parseKeyRing(Deno.env.get("TOKEN_ENC_KEY"), Deno.env.get("TOKEN_ENC_KEY_PREVIOUS")), fetch, now: () => Date.now(), uuid: () => crypto.randomUUID(),
    credentials: (p: Platform) => {
      const id = Deno.env.get(`${p}_CLIENT_ID`), secret = Deno.env.get(`${p}_CLIENT_SECRET`);
      return id && secret ? { clientId: id, clientSecret: secret } : null;
    },
    redirectUri: Deno.env.get("OAUTH_REDIRECT_URI") ?? `${url}/functions/v1/oauth/callback`,
    resultUrl: ({ platform, status, detail }) => {
      const base = Deno.env.get("OAUTH_RESULT_URL") ?? "autoposter://oauth";
      const u = new URL(base); u.searchParams.set("platform", platform); u.searchParams.set("status", status); if (detail) u.searchParams.set("detail", detail);
      return u.toString();
    },
  };
}

async function user(req: Request): Promise<string | null> {
  const token = req.headers.get("authorization")?.replace(/^Bearer\s+/i, "");
  if (!token) return null;
  const { data, error } = await createClient(url, anon, { auth: { persistSession: false } }).auth.getUser(token);
  return error || !data.user ? null : data.user.id;
}

Deno.serve(async (req) => {
  const path = new URL(req.url).pathname.replace(/\/+$/, "");
  try {
    if (req.method === "GET" && path.endsWith("/callback")) {
      const q = new URL(req.url).searchParams;
      // The platform comes from the stored state record, never from the query string.
      const state = q.get("state");
      const hash = state ? Array.from(new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(state))), (b) => b.toString(16).padStart(2, "0")).join("") : "";
      const { data: rec } = hash ? await admin.from("oauth_states").select("platform").eq("state_hash", hash).maybeSingle() : { data: null };
      const r = await finishOAuth(deps(req), { platform: rec?.platform ?? "", state, code: q.get("code"), error: q.get("error") });
      return new Response(null, { status: 302, headers: { location: r.redirect, "cache-control": "no-store" } });
    }
    if (req.method === "POST" && path.endsWith("/start")) {
      const owner = await user(req); if (!owner) return json({ error: "unauthorized" }, 401);
      const body = await req.json().catch(() => ({}));
      const r = await startOAuth(deps(req), { owner, platform: String(body.platform ?? "") });
      return r.ok ? json({ url: r.url }) : json({ error: r.code }, r.code === "not_configured" ? 501 : 400);
    }
    if (req.method === "POST" && path.endsWith("/disconnect")) {
      const owner = await user(req); if (!owner) return json({ error: "unauthorized" }, 401);
      const body = await req.json().catch(() => ({}));
      const r = await disconnectAccount(deps(req), { owner, accountId: String(body.accountId ?? "") });
      return r.ok ? json(r) : json({ error: "not_found" }, 404);
    }
    return json({ error: "not_found" }, 404);
  } catch {
    return json({ error: "internal" }, 500);      // never echo exception text: it can contain provider responses
  }
});

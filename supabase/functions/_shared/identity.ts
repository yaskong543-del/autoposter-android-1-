// Account identity + eligibility after OAuth. One function per provider, all taking an injected fetch.
import { type Platform } from './types.ts';

export interface Identity {
  externalId: string;
  displayName: string;
  accountType: string;
  eligible: boolean;
  ineligibleReason?: string;
  /** Per-account token when it differs from the user token (Facebook Page access tokens). */
  token?: string;
  meta: Record<string, unknown>;
}

async function getJson(f: typeof fetch, url: string, token: string): Promise<{ ok: boolean; status: number; json: any }> {
  const r = await f(url, { headers: { authorization: `Bearer ${token}` } });
  let json: any; try { json = await r.json(); } catch { json = undefined; }
  return { ok: r.ok, status: r.status, json };
}

export async function fetchIdentities(platform: Platform, token: string, f: typeof fetch, hint?: { providerUserId?: string }): Promise<Identity[]> {
  switch (platform) {
    case 'YOUTUBE': {
      const r = await getJson(f, 'https://www.googleapis.com/youtube/v3/channels?part=snippet&mine=true', token);
      if (!r.ok) throw new Error(`identity lookup failed (${r.status})`);
      const items: any[] = r.json?.items ?? [];
      if (!items.length) return [{ externalId: 'none', displayName: '', accountType: 'google_account', eligible: false, ineligibleReason: 'This Google account has no YouTube channel.', meta: {} }];
      return items.map((c) => ({ externalId: String(c.id), displayName: String(c.snippet?.title ?? c.id), accountType: 'channel', eligible: true, meta: {} }));
    }
    case 'INSTAGRAM': {
      const r = await getJson(f, 'https://graph.instagram.com/v26.0/me?fields=user_id,username,account_type', token);
      if (!r.ok) throw new Error(`identity lookup failed (${r.status})`);
      const type = String(r.json?.account_type ?? '');
      const eligible = type === 'BUSINESS' || type === 'MEDIA_CREATOR';
      return [{ externalId: String(r.json?.user_id ?? hint?.providerUserId ?? ''), displayName: String(r.json?.username ?? ''), accountType: type || 'unknown', eligible,
        ineligibleReason: eligible ? undefined : 'Instagram publishing needs a professional (Business or Creator) account.', meta: {} }];
    }
    case 'THREADS': {
      const r = await getJson(f, 'https://graph.threads.net/v1.0/me?fields=id,username', token);
      if (!r.ok) throw new Error(`identity lookup failed (${r.status})`);
      return [{ externalId: String(r.json?.id ?? hint?.providerUserId ?? ''), displayName: String(r.json?.username ?? ''), accountType: 'threads_profile', eligible: !!r.json?.id, meta: {} }];
    }
    case 'FACEBOOK': {
      const r = await getJson(f, 'https://graph.facebook.com/v26.0/me/accounts?fields=id,name,access_token,tasks&limit=100', token);
      if (!r.ok) throw new Error(`identity lookup failed (${r.status})`);
      const pages: any[] = r.json?.data ?? [];
      if (!pages.length) return [{ externalId: 'none', displayName: '', accountType: 'user', eligible: false, ineligibleReason: 'No Facebook Pages found. Reels can only be published to Pages you manage.', meta: {} }];
      return pages.map((p) => {
        const can = Array.isArray(p.tasks) ? p.tasks.includes('CREATE_CONTENT') : false;
        return { externalId: String(p.id), displayName: String(p.name ?? p.id), accountType: 'page', eligible: can && !!p.access_token, token: p.access_token as string | undefined,
          ineligibleReason: can ? undefined : 'You need the Create Content task on this Page.', meta: {} };
      });
    }
    case 'TIKTOK': {
      const r = await getJson(f, 'https://open.tiktokapis.com/v2/user/info/?fields=open_id,display_name', token);
      if (!r.ok) throw new Error(`identity lookup failed (${r.status})`);
      const u = r.json?.data?.user ?? {};
      return [{ externalId: String(u.open_id ?? hint?.providerUserId ?? ''), displayName: String(u.display_name ?? ''), accountType: 'tiktok_user', eligible: !!(u.open_id ?? hint?.providerUserId), meta: {} }];
    }
    case 'X': {
      const r = await getJson(f, 'https://api.x.com/2/users/me', token);
      if (!r.ok) throw new Error(`identity lookup failed (${r.status})`);
      return [{ externalId: String(r.json?.data?.id ?? ''), displayName: String(r.json?.data?.username ?? ''), accountType: 'x_user', eligible: !!r.json?.data?.id, meta: {} }];
    }
    case 'LINKEDIN': {
      const r = await getJson(f, 'https://api.linkedin.com/v2/userinfo', token);
      if (!r.ok) throw new Error(`identity lookup failed (${r.status})`);
      return [{ externalId: String(r.json?.sub ?? ''), displayName: String(r.json?.name ?? ''), accountType: 'member', eligible: !!r.json?.sub, meta: {} }];
    }
  }
}

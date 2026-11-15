// Adapter tests use a scripted fake `fetch`. They prove request shapes, state machines and error mapping only;
// they are NOT live provider tests (see docs/API_SUPPORT_MATRIX.md for live status).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ADAPTERS, type AdapterCtx, type MediaSource, type ProviderState } from './providers/index.ts';
import { chunkPlan } from './providers/tiktok.ts';
import { ProviderError } from './types.ts';

type Route = (url: string, init: RequestInit) => Response | Promise<Response> | undefined;
function fakeFetch(routes: Route[], log: Array<{ url: string; method: string; headers: Headers; body: unknown }>): typeof fetch {
  return (async (url: string, init: RequestInit = {}) => {
    log.push({ url: String(url), method: init.method ?? 'GET', headers: new Headers(init.headers), body: init.body });
    for (const r of routes) { const out = await r(String(url), init); if (out) return out; }
    return new Response(JSON.stringify({ error: { message: 'unrouted ' + url } }), { status: 599 });
  }) as typeof fetch;
}
const media = (size: number): MediaSource => ({
  size, contentType: 'video/mp4', name: 'clip.mp4',
  signedUrl: async () => 'https://storage.example/signed?token=abc',
  readRange: async (s, e) => new Uint8Array(Math.min(e, size) - s),
});
function ctx(platform: string, f: typeof fetch, m: MediaSource, over: Partial<AdapterCtx> = {}, saved: ProviderState[] = []): AdapterCtx {
  return { fetch: f, accessToken: 'TOKEN', accountExternalId: '12345', accountMeta: {}, payload: { caption: 'Hello world', hashtags: ['a', '#b'] },
    media: m, budgetBytes: 1_000_000, nowMs: Date.UTC(2026, 10, 12), checkpoint: async (s) => { saved.push(s); }, config: { linkedinVersion: '202605' }, ...over };
}
const json = (o: unknown, status = 200, h: Record<string, string> = {}) => new Response(JSON.stringify(o), { status, headers: { 'content-type': 'application/json', ...h } });
async function drive(platform: keyof typeof ADAPTERS, c: AdapterCtx, max = 30) {
  let state: ProviderState = {};
  for (let i = 0; i < max; i++) {
    const r = await ADAPTERS[platform].advance(c, state);
    state = r.state;
    if (r.kind !== 'progress') return { r, state, steps: i + 1 };
  }
  throw new Error('did not finish');
}

test('every provider is registered, video-only, honest about approvals', () => {
  assert.deepEqual(Object.keys(ADAPTERS).sort(), ['FACEBOOK', 'INSTAGRAM', 'LINKEDIN', 'THREADS', 'TIKTOK', 'X', 'YOUTUBE']);
  for (const a of Object.values(ADAPTERS)) { assert.equal(a.capabilities.video, true); assert.equal(a.capabilities.image, false); assert.ok(a.capabilities.approvalNote.length > 20); }
});

test('YouTube: resumable init, chunked upload with 256KiB granularity, resume from server offset, status gate', async () => {
  const log: any[] = [];
  const size = 600_000; let received = 0; let calls = 0;
  const f = fakeFetch([
    (u, i) => u.includes('/upload/youtube/v3/videos') ? new Response('', { status: 200, headers: { location: 'https://upload.example/session1' } }) : undefined,
    (u, i) => {
      if (u !== 'https://upload.example/session1') return;
      const cr = new Headers(i.headers).get('content-range')!;
      if (cr.startsWith('bytes */')) return received === 0 ? new Response('', { status: 308 }) : new Response('', { status: 308, headers: { range: `bytes=0-${received - 1}` } });
      const m = /bytes (\d+)-(\d+)\/(\d+)/.exec(cr)!; calls++;
      assert.equal(Number(m[1]), received);
      if (Number(m[2]) + 1 < size) assert.equal((Number(m[2]) + 1 - Number(m[1])) % 262_144, 0);
      received = Number(m[2]) + 1;
      return received >= size ? json({ id: 'vid1' }, 200) : new Response('', { status: 308, headers: { range: `bytes=0-${received - 1}` } });
    },
  ], log);
  const c = ctx('YOUTUBE', f, media(size), { budgetBytes: 262_144 * 2 });
  const { r } = await drive('YOUTUBE', c);
  assert.equal(r.kind, 'submitted'); assert.equal((r.state as any).videoId, 'vid1'); assert.ok(calls >= 2);
  const init = log[0]; assert.equal(init.headers.get('x-upload-content-length'), String(size));
  const body = JSON.parse(init.body as string); assert.equal(body.status.privacyStatus, 'public'); assert.ok(body.snippet.description.includes('#a #b'));
  // status: processing -> published only when processed
  const proc = fakeFetch([(u) => u.includes('/videos?part=') ? json({ items: [{ status: { uploadStatus: 'uploaded' } }] }) : undefined], []);
  assert.equal((await ADAPTERS.YOUTUBE.status(ctx('YOUTUBE', proc, media(1)), { videoId: 'vid1' })).kind, 'processing');
  const done = fakeFetch([(u) => u.includes('/videos?part=') ? json({ items: [{ status: { uploadStatus: 'processed', privacyStatus: 'private' } }] }) : undefined], []);
  const s = await ADAPTERS.YOUTUBE.status(ctx('YOUTUBE', done, media(1)), { videoId: 'vid1' }) as any;
  assert.equal(s.kind, 'published'); assert.equal(s.note, 'visibility:private');
  const bad = fakeFetch([(u) => u.includes('/videos?part=') ? json({ items: [{ status: { uploadStatus: 'rejected', rejectionReason: 'duplicate' } }] }) : undefined], []);
  assert.equal((await ADAPTERS.YOUTUBE.status(ctx('YOUTUBE', bad, media(1)), { videoId: 'v' })).kind, 'failed');
});

test('YouTube: quota error is classified as quota with a reset time', async () => {
  const f = fakeFetch([() => json({ error: { errors: [{ reason: 'quotaExceeded' }] } }, 403)], []);
  await assert.rejects(() => ADAPTERS.YOUTUBE.advance(ctx('YOUTUBE', f, media(10)), {}), (e: any) => e instanceof ProviderError && e.failure.class === 'quota' && e.failure.retryAfterMs! > 0);
});

test('network failures become transient, never leak the URL', async () => {
  const f = (async () => { throw new TypeError('fetch failed https://secret.example/?token=abc'); }) as unknown as typeof fetch;
  await assert.rejects(() => ADAPTERS.YOUTUBE.advance(ctx('YOUTUBE', f, media(10)), {}), (e: any) => e.failure.class === 'transient' && !e.message.includes('secret'));
});

test('Instagram: container -> wait -> checkpoint -> publish; at-most-once publish', async () => {
  const log: any[] = []; const saved: ProviderState[] = [];
  let polls = 0;
  const f = fakeFetch([
    (u, i) => u.endsWith('/12345/media') ? json({ id: 'C1' }) : undefined,
    (u) => u.includes('/C1?fields=status_code') ? json({ status_code: ++polls < 2 ? 'IN_PROGRESS' : 'FINISHED' }) : undefined,
    (u) => u.endsWith('/12345/media_publish') ? json({ id: 'M1' }) : undefined,
  ], log);
  const c = ctx('INSTAGRAM', f, media(10), {}, saved);
  let st = (await ADAPTERS.INSTAGRAM.advance(c, {})).state;                      // create
  assert.equal(st.phase, 'wait'); assert.equal(JSON.parse(log[0].body).media_type, 'REELS'); assert.ok(JSON.parse(log[0].body).video_url.startsWith('https://storage.example/signed'));
  let r = await ADAPTERS.INSTAGRAM.advance(c, st); assert.equal(r.kind, 'progress'); assert.equal((r as any).waitMs, 20_000); st = r.state;  // IN_PROGRESS
  r = await ADAPTERS.INSTAGRAM.advance(c, st); st = r.state; assert.equal(st.phase, 'publish');                                           // FINISHED
  r = await ADAPTERS.INSTAGRAM.advance(c, st);
  assert.equal(r.kind, 'published'); assert.equal((r as any).remoteId, 'M1');
  assert.equal(saved.at(-1)!.phase, 'publishing');                                // checkpoint written before the publish call
  assert.equal(log.filter((l) => l.url.endsWith('/media_publish')).length, 1);
  // crash after checkpoint, container already PUBLISHED -> must refuse to publish again
  const f2 = fakeFetch([(u) => u.includes('status_code') ? json({ status_code: 'PUBLISHED' }) : undefined], log);
  await assert.rejects(() => ADAPTERS.INSTAGRAM.advance(ctx('INSTAGRAM', f2, media(10)), { phase: 'publishing', containerId: 'C1' }), (e: any) => e.failure.code === 'AMBIGUOUS_PUBLISH' && e.failure.class === 'permanent');
  // container ERROR -> permanent
  const f3 = fakeFetch([(u) => u.includes('status_code') ? json({ status_code: 'ERROR' }) : undefined], []);
  await assert.rejects(() => ADAPTERS.INSTAGRAM.advance(ctx('INSTAGRAM', f3, media(10)), { phase: 'wait', containerId: 'C1' }), (e: any) => e.failure.class === 'permanent');
});

test('Threads: container -> wait -> publish', async () => {
  const f = fakeFetch([
    (u) => u.endsWith('/12345/threads') ? json({ id: 'T1' }) : undefined,
    (u) => u.includes('/T1?fields=status') ? json({ status: 'FINISHED' }) : undefined,
    (u) => u.endsWith('/threads_publish') ? json({ id: 'P1' }) : undefined,
  ], []);
  const { r } = await drive('THREADS', ctx('THREADS', f, media(10)));
  assert.equal(r.kind, 'published'); assert.equal((r as any).remoteId, 'P1');
});

test('Facebook Reels: start -> upload(file_url) -> finish -> status', async () => {
  const log: any[] = [];
  const f = fakeFetch([
    (u, i) => u.endsWith('/12345/video_reels') && JSON.parse(String(i.body)).upload_phase === 'start' ? json({ video_id: 'V1' }) : undefined,
    (u, i) => u.includes('rupload.facebook.com/video-upload/v26.0/V1') ? json({ success: true }) : undefined,
    (u, i) => u.endsWith('/12345/video_reels') && JSON.parse(String(i.body)).upload_phase === 'finish' ? json({ success: true }) : undefined,
    (u) => u.includes('/V1?fields=status') ? json({ status: { publishing_phase: { status: 'published' } } }) : undefined,
  ], log);
  const c = ctx('FACEBOOK', f, media(10));
  const { r } = await drive('FACEBOOK', c); assert.equal(r.kind, 'submitted');
  const up = log.find((l) => l.url.includes('rupload')); assert.equal(up.headers.get('authorization'), 'OAuth TOKEN'); assert.ok(up.headers.get('file_url'));
  const s = await ADAPTERS.FACEBOOK.status(c, r.state) as any; assert.equal(s.kind, 'published');
  await assert.rejects(() => ADAPTERS.FACEBOOK.advance(c, { phase: 'finishing', videoId: 'V1' }), (e: any) => e.failure.code === 'AMBIGUOUS_PUBLISH');
});

test('TikTok: creator_info privacy fallback (unaudited => SELF_ONLY), chunk plan, upload, status', async () => {
  assert.deepEqual(chunkPlan(3_000_000), { chunkSize: 3_000_000, count: 1 });
  assert.deepEqual(chunkPlan(25 * 1024 * 1024), { chunkSize: 10 * 1024 * 1024, count: 2 });
  const log: any[] = []; let uploaded = 0;
  const f = fakeFetch([
    (u) => u.endsWith('/creator_info/query/') ? json({ data: { privacy_level_options: ['SELF_ONLY'], max_video_post_duration_sec: 600 }, error: { code: 'ok' } }) : undefined,
    (u) => u.endsWith('/video/init/') ? json({ data: { publish_id: 'pub1', upload_url: 'https://open-upload.tiktokapis.com/video/?x=1' } }) : undefined,
    (u, i) => { if (!u.startsWith('https://open-upload')) return; uploaded += (i.body as Uint8Array).length; return new Response('', { status: 201 }); },
    (u) => u.endsWith('/status/fetch/') ? json({ data: { status: 'PUBLISH_COMPLETE', publicaly_available_post_id: ['777'] } }) : undefined,
  ], log);
  const size = 12_000_000;
  const c = ctx('TIKTOK', f, media(size), { budgetBytes: 10 * 1024 * 1024 });
  const { r } = await drive('TIKTOK', c);
  assert.equal(r.kind, 'submitted'); assert.equal(uploaded, size);
  const init = JSON.parse(log.find((l) => l.url.endsWith('/video/init/')).body);
  assert.equal(init.post_info.privacy_level, 'SELF_ONLY'); assert.equal(init.source_info.source, 'FILE_UPLOAD');
  const s = await ADAPTERS.TIKTOK.status(c, r.state) as any;
  assert.equal(s.kind, 'published'); assert.equal(s.remoteId, '777'); assert.match(s.note, /private/);
  const fail = fakeFetch([() => json({ error: { code: 'unaudited_client_can_only_post_to_private_accounts' } }, 403)], []);
  await assert.rejects(() => ADAPTERS.TIKTOK.advance(ctx('TIKTOK', fail, media(10)), {}), (e: any) => e.failure.class === 'approval_blocked');
});

test('X: init -> append(4MiB) -> finalize -> wait -> post; 403 on upload reported as approval_blocked', async () => {
  const log: any[] = [];
  const f = fakeFetch([
    (u) => u.endsWith('/media/upload/initialize') ? json({ data: { id: 'M9' } }) : undefined,
    (u) => u.endsWith('/media/upload/M9/append') ? json({ data: { expires_at: 1 } }, 200) : undefined,
    (u) => u.endsWith('/media/upload/M9/finalize') ? json({ data: { processing_info: { state: 'pending', check_after_secs: 3 } } }) : undefined,
    (u) => u.includes('command=STATUS') ? json({ data: { processing_info: { state: 'succeeded' } } }) : undefined,
    (u) => u.endsWith('/2/tweets') ? json({ data: { id: 'TW1' } }, 201) : undefined,
  ], log);
  const { r } = await drive('X', ctx('X', f, media(9 * 1024 * 1024), { budgetBytes: 4 * 1024 * 1024 }));
  assert.equal(r.kind, 'published'); assert.equal((r as any).remoteId, 'TW1');
  assert.equal(log.filter((l) => l.url.endsWith('/append')).length, 3);
  const tweet = JSON.parse(log.find((l) => l.url.endsWith('/2/tweets')).body); assert.deepEqual(tweet.media.media_ids, ['M9']); assert.ok(tweet.text.length <= 280);
  const f2 = fakeFetch([() => json({ title: 'Forbidden' }, 403)], []);
  await assert.rejects(() => ADAPTERS.X.advance(ctx('X', f2, media(10)), {}), (e: any) => e.failure.code === 'X_MEDIA_AUTH_UNSUPPORTED');
  await assert.rejects(() => ADAPTERS.X.advance(ctx('X', f2, media(10)), { phase: 'posting', mediaId: 'M9' }), (e: any) => e.failure.code === 'AMBIGUOUS_PUBLISH');
});

test('LinkedIn: init -> PUT parts (ETags) -> finalize -> AVAILABLE -> post with x-restli-id', async () => {
  const log: any[] = [];
  const f = fakeFetch([
    (u) => u.includes('action=initializeUpload') ? json({ value: { video: 'urn:li:video:V', uploadToken: 'tok', uploadInstructions: [
      { uploadUrl: 'https://li-upload/1', firstByte: 0, lastByte: 99 }, { uploadUrl: 'https://li-upload/2', firstByte: 100, lastByte: 149 }] } }) : undefined,
    (u) => u === 'https://li-upload/1' ? new Response('', { status: 200, headers: { etag: 'e1' } }) : u === 'https://li-upload/2' ? new Response('', { status: 200, headers: { etag: 'e2' } }) : undefined,
    (u) => u.includes('action=finalizeUpload') ? new Response('', { status: 200 }) : undefined,
    (u) => u.includes('/videos/urn%3Ali%3Avideo%3AV') ? json({ status: 'AVAILABLE' }) : undefined,
    (u) => u.endsWith('/rest/posts') ? new Response('', { status: 201, headers: { 'x-restli-id': 'urn:li:share:S1' } }) : undefined,
  ], log);
  const { r } = await drive('LINKEDIN', ctx('LINKEDIN', f, media(150)));
  assert.equal(r.kind, 'published'); assert.equal((r as any).remoteId, 'urn:li:share:S1');
  const fin = JSON.parse(log.find((l) => l.url.includes('finalizeUpload')).body); assert.deepEqual(fin.finalizeUploadRequest.uploadedPartIds, ['e1', 'e2']);
  assert.equal(log[0].headers.get('linkedin-version'), '202605'); assert.equal(log[0].headers.get('x-restli-protocol-version'), '2.0.0');
  assert.equal(JSON.parse(log[0].body).initializeUploadRequest.owner, 'urn:li:person:12345');
  const post = JSON.parse(log.find((l) => l.url.endsWith('/rest/posts')).body); assert.equal(post.author, 'urn:li:person:12345');
});

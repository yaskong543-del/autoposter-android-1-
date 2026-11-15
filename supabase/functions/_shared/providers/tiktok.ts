import { type ProviderAdapter, type AdapterCtx, type ProviderState, type StepResult, type StatusResult, call, send, readJson, permanent, captionText } from './common.ts';
import { ProviderError } from '../types.ts';
import { classifyHttp } from '../errors.ts';

const P = 'TIKTOK' as const;
const API = 'https://open.tiktokapis.com/v2/post/publish';
const CHUNK = 10 * 1024 * 1024; // >= 5 MiB per TikTok chunk rules; the final chunk absorbs the remainder.

/** Chunk plan: floor(size / CHUNK) chunks (min 1); the last chunk is the remainder-inclusive one. */
export function chunkPlan(size: number): { chunkSize: number; count: number } {
  if (size <= CHUNK) return { chunkSize: size, count: 1 };
  return { chunkSize: CHUNK, count: Math.floor(size / CHUNK) };
}

export const tiktok: ProviderAdapter = {
  platform: P,
  capabilities: { video: true, image: false, maxCaption: 2200, asyncProcessing: true,
    approvalNote: 'Direct Post needs video.publish approval. UNAUDITED apps can only post privately (SELF_ONLY), to private accounts, for at most 5 users per 24h. Public posting needs TikTok\'s audit. FILE_UPLOAD is used because PULL_FROM_URL requires a verified URL domain.' },

  async advance(ctx: AdapterCtx, state: ProviderState): Promise<StepResult> {
    const phase = state.phase ?? 'init';
    if (phase === 'init') {
      // 1) creator_info decides which privacy levels this creator may use right now.
      const info = await call(ctx, P, `${API}/creator_info/query/`, { method: 'POST', headers: hdr(ctx) });
      const options: string[] = info.json?.data?.privacy_level_options ?? [];
      if (!options.length) throw permanent(P, 'TT_NO_PRIVACY_OPTIONS', 'TikTok did not offer any posting privacy option for this account.');
      const wanted = ctx.payload.privacy === 'private' ? 'SELF_ONLY' : 'PUBLIC_TO_EVERYONE';
      const privacy = options.includes(wanted) ? wanted : options.includes('SELF_ONLY') ? 'SELF_ONLY' : options[0];
      const maxSec = Number(info.json?.data?.max_video_post_duration_sec);
      const { chunkSize, count } = chunkPlan(ctx.media.size);
      const { json } = await call(ctx, P, `${API}/video/init/`, {
        method: 'POST', headers: hdr(ctx),
        body: JSON.stringify({
          post_info: { title: captionText(ctx.payload, 2200), privacy_level: privacy },
          source_info: { source: 'FILE_UPLOAD', video_size: ctx.media.size, chunk_size: chunkSize, total_chunk_count: count },
        }),
      });
      const publishId = json?.data?.publish_id, uploadUrl = json?.data?.upload_url;
      if (!publishId || !uploadUrl) throw permanent(P, 'TT_NO_UPLOAD_URL', 'TikTok did not return an upload URL.');
      return { kind: 'progress', progress: 0,
        state: { phase: 'upload', publishId: String(publishId), uploadUrl: String(uploadUrl), offset: 0, chunkSize, count, privacy, maxSec: Number.isFinite(maxSec) ? maxSec : null } };
    }
    if (phase === 'upload') {
      const total = ctx.media.size;
      const chunkSize = Number(state.chunkSize), count = Number(state.count);
      let offset = Number(state.offset);
      let moved = 0;
      while (offset < total) {
        const index = Math.floor(offset / chunkSize);
        const isLast = index >= count - 1;
        const end = isLast ? total : Math.min(total, offset + chunkSize);
        if (moved > 0 && moved + (end - offset) > ctx.budgetBytes) break;
        const bytes = await ctx.media.readRange(offset, end);
        const res = await send(ctx, P, String(state.uploadUrl), {
          method: 'PUT',
          headers: { 'content-type': ctx.media.contentType || 'video/mp4', 'content-length': String(bytes.length), 'content-range': `bytes ${offset}-${end - 1}/${total}` },
          body: bytes as BodyInit,
        });
        if (res.status !== 206 && res.status !== 201 && res.status !== 200) {
          throw new ProviderError(classifyHttp(P, res.status, await readJson(res), res.headers, ctx.nowMs));
        }
        offset = end; moved += bytes.length;
      }
      if (offset >= total) return { kind: 'submitted', state: { ...state, phase: 'processing', offset } };
      return { kind: 'progress', state: { ...state, offset }, progress: offset / total, waitMs: 0 };
    }
    throw permanent(P, 'TT_BAD_STATE', 'Unexpected upload state.');
  },

  async status(ctx: AdapterCtx, state: ProviderState): Promise<StatusResult> {
    const { json } = await call(ctx, P, `${API}/status/fetch/`, { method: 'POST', headers: hdr(ctx), body: JSON.stringify({ publish_id: state.publishId }) });
    const st = json?.data?.status as string | undefined;
    if (st === 'PUBLISH_COMPLETE') {
      const ids = json?.data?.publicaly_available_post_id ?? json?.data?.publicly_available_post_id ?? [];
      const note = state.privacy === 'SELF_ONLY' ? 'visibility:private (TikTok app not audited or creator chose private)' : undefined;
      return { kind: 'published', state, remoteId: String(ids[0] ?? state.publishId), note };
    }
    if (st === 'FAILED') {
      return { kind: 'failed', failure: { platform: P, class: 'permanent', code: `TT_${String(json?.data?.fail_reason ?? 'FAILED').toUpperCase()}`, message: 'TikTok could not publish the video.' } };
    }
    return { kind: 'processing', state, waitMs: 30_000 };
  },
};

const hdr = (ctx: AdapterCtx) => ({ authorization: `Bearer ${ctx.accessToken}`, 'content-type': 'application/json; charset=UTF-8' });

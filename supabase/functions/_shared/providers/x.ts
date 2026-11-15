import { type ProviderAdapter, type AdapterCtx, type ProviderState, type StepResult, type StatusResult, call, permanent, captionText } from './common.ts';
import { ProviderError } from '../types.ts';

const P = 'X' as const;
const MEDIA = 'https://api.x.com/2/media/upload';
const CHUNK = 4 * 1024 * 1024; // X caps each APPEND request at 5 MB

export const x: ProviderAdapter = {
  platform: P,
  capabilities: { video: true, image: false, maxCaption: 280, asyncProcessing: true,
    approvalNote: 'Needs an X developer app on a paid/credit plan (pay-per-use since Feb 2026 per third-party reports; confirm in the X developer console). OAuth2 token needs media.write for upload; X documentation is inconsistent about OAuth 1.0a vs 2.0 for media upload, so a 403 on upload is reported as approval_blocked.' },

  async advance(ctx: AdapterCtx, state: ProviderState): Promise<StepResult> {
    const phase = state.phase ?? 'init';
    const h = { authorization: `Bearer ${ctx.accessToken}` };
    try {
      if (phase === 'init') {
        const { json } = await call(ctx, P, `${MEDIA}/initialize`, {
          method: 'POST', headers: { ...h, 'content-type': 'application/json' },
          body: JSON.stringify({ media_type: ctx.media.contentType || 'video/mp4', total_bytes: ctx.media.size, media_category: 'tweet_video' }),
        });
        const id = json?.data?.id;
        if (!id) throw permanent(P, 'X_NO_MEDIA_ID', 'X did not start the media upload.');
        return { kind: 'progress', state: { phase: 'append', mediaId: String(id), offset: 0, segment: 0 }, progress: 0, waitMs: 0 };
      }
      if (phase === 'append') {
        const total = ctx.media.size;
        let offset = Number(state.offset), segment = Number(state.segment), moved = 0;
        while (offset < total) {
          const end = Math.min(total, offset + CHUNK);
          if (moved > 0 && moved + (end - offset) > ctx.budgetBytes) break;
          const bytes = await ctx.media.readRange(offset, end);
          const fd = new FormData();
          fd.append('segment_index', String(segment));
          fd.append('media', new Blob([bytes as BlobPart], { type: ctx.media.contentType || 'video/mp4' }), ctx.media.name || 'video.mp4');
          await call(ctx, P, `${MEDIA}/${state.mediaId}/append`, { method: 'POST', headers: h, body: fd });
          offset = end; segment += 1; moved += bytes.length;
        }
        if (offset >= total) return { kind: 'progress', state: { ...state, phase: 'finalize', offset, segment }, progress: 0.9, waitMs: 0 };
        return { kind: 'progress', state: { ...state, offset, segment }, progress: offset / total, waitMs: 0 };
      }
      if (phase === 'finalize') {
        const { json } = await call(ctx, P, `${MEDIA}/${state.mediaId}/finalize`, { method: 'POST', headers: h });
        const pi = json?.data?.processing_info;
        if (pi && pi.state !== 'succeeded') {
          if (pi.state === 'failed') throw permanent(P, 'X_MEDIA_FAILED', 'X could not process the video.');
          return { kind: 'progress', state: { ...state, phase: 'wait' }, progress: 0.92, waitMs: Math.max(1, Number(pi.check_after_secs ?? 5)) * 1000 };
        }
        return { kind: 'progress', state: { ...state, phase: 'post' }, progress: 0.95, waitMs: 0 };
      }
      if (phase === 'wait') {
        const { json } = await call(ctx, P, `${MEDIA}?command=STATUS&media_id=${encodeURIComponent(String(state.mediaId))}`, { headers: h });
        const pi = json?.data?.processing_info;
        if (!pi || pi.state === 'succeeded') return { kind: 'progress', state: { ...state, phase: 'post' }, progress: 0.95, waitMs: 0 };
        if (pi.state === 'failed') throw permanent(P, 'X_MEDIA_FAILED', 'X could not process the video.');
        return { kind: 'progress', state, progress: 0.93, waitMs: Math.max(1, Number(pi.check_after_secs ?? 5)) * 1000 };
      }
      if (phase === 'post' || phase === 'posting') {
        if (phase === 'posting') throw permanent(P, 'AMBIGUOUS_PUBLISH', 'The post may already be live on X. Check your profile before retrying.');
        await ctx.checkpoint({ ...state, phase: 'posting' });
        const { json } = await call(ctx, P, 'https://api.x.com/2/tweets', {
          method: 'POST', headers: { ...h, 'content-type': 'application/json' },
          body: JSON.stringify({ text: captionText(ctx.payload, 280), media: { media_ids: [String(state.mediaId)] } }),
        });
        const id = json?.data?.id;
        if (!id) throw permanent(P, 'AMBIGUOUS_PUBLISH', 'X did not confirm the post.');
        return { kind: 'published', state: { ...state, phase: 'done' }, remoteId: String(id), url: `https://x.com/i/status/${id}` };
      }
    } catch (e) {
      // Upload endpoint rejecting an OAuth2 user token is a configuration/plan problem, not a transient failure.
      if (e instanceof ProviderError && e.failure.class === 'approval_blocked' && phase !== 'post') {
        throw new ProviderError({ ...e.failure, code: 'X_MEDIA_AUTH_UNSUPPORTED', message: 'X refused the media upload for this app/token (media.write scope or API plan).' });
      }
      throw e;
    }
    throw permanent(P, 'X_BAD_STATE', 'Unexpected upload state.');
  },
  async status(_ctx, state): Promise<StatusResult> { return { kind: 'published', state, remoteId: '' }; },
};

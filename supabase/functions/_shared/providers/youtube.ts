import { type ProviderAdapter, type AdapterCtx, type ProviderState, type StepResult, type StatusResult, call, send, readJson, permanent, captionText } from './common.ts';
import { ProviderError } from '../types.ts';
import { classifyHttp } from '../errors.ts';

const P = 'YOUTUBE' as const;
const CHUNK = 262_144; // 256 KiB: required granularity for non-final resumable chunks

export const youtube: ProviderAdapter = {
  platform: P,
  capabilities: { video: true, image: false, maxCaption: 5000, asyncProcessing: true,
    approvalNote: 'youtube.upload is a sensitive scope: the Google consent screen must be verified for public users. Quota: 100 uploads/day per project (Video Uploads bucket) plus a per-channel daily limit.' },

  async advance(ctx: AdapterCtx, state: ProviderState): Promise<StepResult> {
    const phase = state.phase ?? 'init';
    if (phase === 'init') {
      const privacy = ctx.payload.privacy === 'private' ? 'private' : ctx.payload.privacy === 'unlisted' ? 'unlisted' : 'public';
      const meta = {
        snippet: { title: (ctx.payload.title || ctx.payload.caption || ctx.media.name).slice(0, 100), description: captionText(ctx.payload, 5000) },
        status: { privacyStatus: privacy, selfDeclaredMadeForKids: false },
      };
      const { res } = await call(ctx, P, 'https://www.googleapis.com/upload/youtube/v3/videos?uploadType=resumable&part=snippet,status', {
        method: 'POST',
        headers: {
          authorization: `Bearer ${ctx.accessToken}`, 'content-type': 'application/json; charset=UTF-8',
          'x-upload-content-length': String(ctx.media.size), 'x-upload-content-type': ctx.media.contentType || 'video/*',
        },
        body: JSON.stringify(meta),
      });
      const session = res.headers.get('location');
      if (!session) throw permanent(P, 'YT_NO_SESSION', 'YouTube did not return an upload session.');
      return { kind: 'progress', state: { phase: 'upload', session, offset: 0 }, progress: 0 };
    }
    if (phase === 'upload') {
      const session = String(state.session);
      const total = ctx.media.size;
      let offset = await queryOffset(ctx, session, total);
      let moved = 0;
      while (offset < total) {
        const remaining = total - offset;
        const room = Math.max(CHUNK, Math.floor((ctx.budgetBytes - moved) / CHUNK) * CHUNK);
        const size = Math.min(remaining, room);
        const end = offset + size; // exclusive; non-final chunks are multiples of 256 KiB because offset and room are
        const bytes = await ctx.media.readRange(offset, end);
        const res = await send(ctx, P, session, {
          method: 'PUT',
          headers: { authorization: `Bearer ${ctx.accessToken}`, 'content-length': String(bytes.length),
            'content-range': `bytes ${offset}-${end - 1}/${total}`, 'content-type': ctx.media.contentType || 'video/*' },
          body: bytes as BodyInit,
        });
        if (res.status === 308) {
          offset = rangeEnd(res.headers.get('range'));
        } else if (res.status === 200 || res.status === 201) {
          const j = await readJson(res);
          if (!j?.id) throw permanent(P, 'YT_NO_VIDEO_ID', 'YouTube accepted the upload but returned no video id.');
          return { kind: 'submitted', state: { phase: 'processing', videoId: String(j.id) } };
        } else {
          throw new ProviderError(classifyHttp(P, res.status, await readJson(res), res.headers, ctx.nowMs));
        }
        moved += size;
        if (moved >= ctx.budgetBytes && offset < total) break;
      }
      return { kind: 'progress', state: { phase: 'upload', session, offset }, progress: total ? offset / total : 1, waitMs: 0 };
    }
    throw permanent(P, 'YT_BAD_STATE', 'Unexpected upload state.');
  },

  async status(ctx: AdapterCtx, state: ProviderState): Promise<StatusResult> {
    const id = String(state.videoId);
    const { json } = await call(ctx, P, `https://www.googleapis.com/youtube/v3/videos?part=status,processingDetails&id=${encodeURIComponent(id)}`, {
      headers: { authorization: `Bearer ${ctx.accessToken}` },
    });
    const item = json?.items?.[0];
    if (!item) return { kind: 'failed', failure: { platform: P, class: 'permanent', code: 'YT_VIDEO_GONE', message: 'The uploaded video is no longer on YouTube.' } };
    const up = item.status?.uploadStatus as string | undefined;
    if (up === 'processed') {
      const priv = item.status?.privacyStatus as string | undefined;
      return { kind: 'published', state, remoteId: id, url: `https://www.youtube.com/watch?v=${id}`,
        note: priv && priv !== 'public' ? `visibility:${priv}` : undefined };
    }
    if (up === 'failed' || up === 'rejected' || up === 'deleted') {
      return { kind: 'failed', failure: { platform: P, class: 'permanent', code: `YT_${String(item.status?.failureReason ?? item.status?.rejectionReason ?? up).toUpperCase()}`, message: 'YouTube could not process the video.' } };
    }
    return { kind: 'processing', state, waitMs: 60_000 };
  },
};

function rangeEnd(h: string | null): number {
  // "bytes=0-524287" -> next offset 524288; no header means nothing received yet.
  const m = /bytes=0-(\d+)/.exec(h ?? '');
  return m ? Number(m[1]) + 1 : 0;
}

async function queryOffset(ctx: AdapterCtx, session: string, total: number): Promise<number> {
  const res = await send(ctx, P, session, {
    method: 'PUT', headers: { authorization: `Bearer ${ctx.accessToken}`, 'content-length': '0', 'content-range': `bytes */${total}` },
  });
  if (res.status === 308) return rangeEnd(res.headers.get('range'));
  if (res.status === 200 || res.status === 201) return total;
  throw new ProviderError(classifyHttp(P, res.status, await readJson(res), res.headers, ctx.nowMs));
}

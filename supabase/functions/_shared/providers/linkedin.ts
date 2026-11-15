import { type ProviderAdapter, type AdapterCtx, type ProviderState, type StepResult, type StatusResult, call, send, permanent, captionText, readJson } from './common.ts';
import { ProviderError } from '../types.ts';
import { classifyHttp } from '../errors.ts';

const P = 'LINKEDIN' as const;
const API = 'https://api.linkedin.com/rest';

const headers = (ctx: AdapterCtx) => ({
  authorization: `Bearer ${ctx.accessToken}`, 'linkedin-version': ctx.config.linkedinVersion,
  'x-restli-protocol-version': '2.0.0', 'content-type': 'application/json',
});

export const linkedin: ProviderAdapter = {
  platform: P,
  capabilities: { video: true, image: false, maxCaption: 3000, asyncProcessing: true,
    approvalNote: 'Member posting uses w_member_social. Organization pages need w_organization_social (restricted to page admins) and the Community Management API product, which LinkedIn must approve. Organization posting is NOT implemented in v0.8.' },

  async advance(ctx: AdapterCtx, state: ProviderState): Promise<StepResult> {
    const phase = state.phase ?? 'init';
    const owner = `urn:li:person:${ctx.accountExternalId}`;
    if (phase === 'init') {
      const { json } = await call(ctx, P, `${API}/videos?action=initializeUpload`, {
        method: 'POST', headers: headers(ctx), body: JSON.stringify({ initializeUploadRequest: { owner, fileSizeBytes: ctx.media.size, uploadCaptions: false, uploadThumbnail: false } }),
      });
      const v = json?.value;
      if (!v?.video || !Array.isArray(v.uploadInstructions)) throw permanent(P, 'LI_NO_UPLOAD', 'LinkedIn did not start the video upload.');
      return { kind: 'progress', progress: 0, waitMs: 0, state: { phase: 'upload', video: String(v.video), uploadToken: String(v.uploadToken ?? ''), parts: v.uploadInstructions, etags: [], next: 0 } };
    }
    if (phase === 'upload') {
      const parts = state.parts as Array<{ uploadUrl: string; firstByte: number; lastByte: number }>;
      const etags = [...(state.etags as string[])];
      let next = Number(state.next), moved = 0;
      while (next < parts.length) {
        const p = parts[next];
        const len = p.lastByte - p.firstByte + 1;
        if (moved > 0 && moved + len > ctx.budgetBytes) break;
        const bytes = await ctx.media.readRange(p.firstByte, p.lastByte + 1);
        const res = await send(ctx, P, p.uploadUrl, { method: 'PUT', headers: { 'content-type': 'application/octet-stream' }, body: bytes as BodyInit });
        if (!res.ok) throw new ProviderError(classifyHttp(P, res.status, await readJson(res), res.headers, ctx.nowMs));
        const etag = res.headers.get('etag');
        if (!etag) throw permanent(P, 'LI_NO_ETAG', 'LinkedIn did not return a part receipt.');
        etags.push(etag); next += 1; moved += len;
      }
      if (next >= parts.length) return { kind: 'progress', state: { ...state, phase: 'finalize', etags, next }, progress: 0.9, waitMs: 0 };
      return { kind: 'progress', state: { ...state, etags, next }, progress: next / parts.length, waitMs: 0 };
    }
    if (phase === 'finalize') {
      await call(ctx, P, `${API}/videos?action=finalizeUpload`, {
        method: 'POST', headers: headers(ctx),
        body: JSON.stringify({ finalizeUploadRequest: { video: state.video, uploadToken: state.uploadToken, uploadedPartIds: state.etags } }),
      }, [200, 201, 204]);
      return { kind: 'progress', state: { ...state, phase: 'wait' }, progress: 0.93, waitMs: 15_000 };
    }
    if (phase === 'wait') {
      const { json } = await call(ctx, P, `${API}/videos/${encodeURIComponent(String(state.video))}`, { headers: headers(ctx) });
      if (json?.status === 'AVAILABLE') return { kind: 'progress', state: { ...state, phase: 'post' }, progress: 0.95, waitMs: 0 };
      if (json?.status === 'PROCESSING_FAILED') throw permanent(P, 'LI_VIDEO_FAILED', 'LinkedIn could not process the video.');
      return { kind: 'progress', state, progress: 0.94, waitMs: 20_000 };
    }
    if (phase === 'post' || phase === 'posting') {
      if (phase === 'posting') throw permanent(P, 'AMBIGUOUS_PUBLISH', 'The post may already be live on LinkedIn. Check your profile before retrying.');
      await ctx.checkpoint({ ...state, phase: 'posting' });
      const { res } = await call(ctx, P, `${API}/posts`, {
        method: 'POST', headers: headers(ctx),
        body: JSON.stringify({
          author: owner, commentary: captionText(ctx.payload, 3000), visibility: 'PUBLIC',
          distribution: { feedDistribution: 'MAIN_FEED', targetEntities: [], thirdPartyDistributionChannels: [] },
          content: { media: { id: state.video } }, lifecycleState: 'PUBLISHED', isReshareDisabledByAuthor: false,
        }),
      }, [200, 201]);
      const urn = res.headers.get('x-restli-id');
      if (!urn) throw permanent(P, 'AMBIGUOUS_PUBLISH', 'LinkedIn did not confirm the post.');
      return { kind: 'published', state: { ...state, phase: 'done' }, remoteId: urn, url: `https://www.linkedin.com/feed/update/${urn}` };
    }
    throw permanent(P, 'LI_BAD_STATE', 'Unexpected upload state.');
  },
  async status(_ctx, state): Promise<StatusResult> { return { kind: 'published', state, remoteId: '' }; },
};

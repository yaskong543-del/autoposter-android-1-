import { type ProviderAdapter, type AdapterCtx, type ProviderState, type StepResult, type StatusResult, call, permanent, captionText } from './common.ts';

const IG_BASE = 'https://graph.instagram.com/v26.0';
const FB_BASE = 'https://graph.facebook.com/v26.0';
const FB_UPLOAD = 'https://rupload.facebook.com/video-upload/v26.0';
const TH_BASE = 'https://graph.threads.net/v1.0';

const auth = (t: string) => ({ authorization: `Bearer ${t}`, 'content-type': 'application/json' });

// ---------- Instagram (Business Login for Instagram): REELS container -> poll -> media_publish ----------
export const instagram: ProviderAdapter = {
  platform: 'INSTAGRAM',
  capabilities: { video: true, image: false, maxCaption: 2200, asyncProcessing: true,
    approvalNote: 'Professional (Business/Creator) accounts only; instagram_business_content_publish needs Meta App Review (Advanced Access) before other users can connect. 100 API-published posts per rolling 24h.' },

  async advance(ctx, state): Promise<StepResult> {
    const phase = state.phase ?? 'create';
    if (phase === 'create') {
      const url = await ctx.media.signedUrl(3600);
      const { json } = await call(ctx, 'INSTAGRAM', `${IG_BASE}/${ctx.accountExternalId}/media`, {
        method: 'POST', headers: auth(ctx.accessToken),
        body: JSON.stringify({ media_type: 'REELS', video_url: url, caption: captionText(ctx.payload, 2200), share_to_feed: true }),
      });
      if (!json?.id) throw permanent('INSTAGRAM', 'IG_NO_CONTAINER', 'Instagram did not return a media container.');
      return { kind: 'progress', state: { phase: 'wait', containerId: String(json.id), createdAtMs: ctx.nowMs }, progress: 0.3, waitMs: 30_000 };
    }
    if (phase === 'wait') {
      const id = String(state.containerId);
      const { json } = await call(ctx, 'INSTAGRAM', `${IG_BASE}/${id}?fields=status_code`, { headers: { authorization: `Bearer ${ctx.accessToken}` } });
      const sc = json?.status_code as string | undefined;
      if (sc === 'FINISHED') return { kind: 'progress', state: { ...state, phase: 'publish' }, progress: 0.8, waitMs: 0 };
      if (sc === 'ERROR' || sc === 'EXPIRED') throw permanent('INSTAGRAM', `IG_CONTAINER_${sc}`, 'Instagram could not process the video.');
      return { kind: 'progress', state, progress: 0.5, waitMs: 20_000 };
    }
    if (phase === 'publish' || phase === 'publishing') {
      const id = String(state.containerId);
      if (phase === 'publishing') {
        // A previous attempt may have published already. Never call media_publish twice.
        const { json } = await call(ctx, 'INSTAGRAM', `${IG_BASE}/${id}?fields=status_code`, { headers: { authorization: `Bearer ${ctx.accessToken}` } });
        if (json?.status_code === 'PUBLISHED') throw permanent('INSTAGRAM', 'AMBIGUOUS_PUBLISH', 'The post may already be live on Instagram. Check your profile before retrying.');
      }
      await ctx.checkpoint({ ...state, phase: 'publishing' });
      const { json } = await call(ctx, 'INSTAGRAM', `${IG_BASE}/${ctx.accountExternalId}/media_publish`, {
        method: 'POST', headers: auth(ctx.accessToken), body: JSON.stringify({ creation_id: id }),
      });
      if (!json?.id) throw permanent('INSTAGRAM', 'AMBIGUOUS_PUBLISH', 'Instagram did not confirm the publication.');
      return { kind: 'published', state: { ...state, phase: 'done', mediaId: String(json.id) }, remoteId: String(json.id) };
    }
    throw permanent('INSTAGRAM', 'IG_BAD_STATE', 'Unexpected upload state.');
  },
  async status(_ctx, state): Promise<StatusResult> {
    return { kind: 'published', state, remoteId: String(state.mediaId ?? '') };
  },
};

// ---------- Threads: container -> (wait ~30s) -> threads_publish ----------
export const threads: ProviderAdapter = {
  platform: 'THREADS',
  capabilities: { video: true, image: false, maxCaption: 500, asyncProcessing: true,
    approvalNote: 'threads_content_publish needs Advanced Access via App Review; until then only your own and tester accounts can publish. 250 API posts per rolling 24h.' },

  async advance(ctx, state): Promise<StepResult> {
    const phase = state.phase ?? 'create';
    if (phase === 'create') {
      const url = await ctx.media.signedUrl(3600);
      const { json } = await call(ctx, 'THREADS', `${TH_BASE}/${ctx.accountExternalId}/threads`, {
        method: 'POST', headers: auth(ctx.accessToken),
        body: JSON.stringify({ media_type: 'VIDEO', video_url: url, text: captionText(ctx.payload, 500) }),
      });
      if (!json?.id) throw permanent('THREADS', 'TH_NO_CONTAINER', 'Threads did not return a container.');
      return { kind: 'progress', state: { phase: 'wait', containerId: String(json.id) }, progress: 0.3, waitMs: 30_000 };
    }
    if (phase === 'wait') {
      const id = String(state.containerId);
      const { json } = await call(ctx, 'THREADS', `${TH_BASE}/${id}?fields=status,error_message`, { headers: { authorization: `Bearer ${ctx.accessToken}` } });
      if (json?.status === 'FINISHED') return { kind: 'progress', state: { ...state, phase: 'publish' }, progress: 0.8, waitMs: 0 };
      if (json?.status === 'ERROR' || json?.status === 'EXPIRED') throw permanent('THREADS', `TH_CONTAINER_${json.status}`, 'Threads could not process the video.');
      return { kind: 'progress', state, progress: 0.5, waitMs: 20_000 };
    }
    if (phase === 'publish' || phase === 'publishing') {
      const id = String(state.containerId);
      if (phase === 'publishing') {
        const { json } = await call(ctx, 'THREADS', `${TH_BASE}/${id}?fields=status`, { headers: { authorization: `Bearer ${ctx.accessToken}` } });
        if (json?.status === 'PUBLISHED') throw permanent('THREADS', 'AMBIGUOUS_PUBLISH', 'The post may already be live on Threads. Check your profile before retrying.');
      }
      await ctx.checkpoint({ ...state, phase: 'publishing' });
      const { json } = await call(ctx, 'THREADS', `${TH_BASE}/${ctx.accountExternalId}/threads_publish`, {
        method: 'POST', headers: auth(ctx.accessToken), body: JSON.stringify({ creation_id: id }),
      });
      if (!json?.id) throw permanent('THREADS', 'AMBIGUOUS_PUBLISH', 'Threads did not confirm the publication.');
      return { kind: 'published', state: { ...state, phase: 'done', mediaId: String(json.id) }, remoteId: String(json.id) };
    }
    throw permanent('THREADS', 'TH_BAD_STATE', 'Unexpected upload state.');
  },
  async status(_ctx, state): Promise<StatusResult> { return { kind: 'published', state, remoteId: String(state.mediaId ?? '') }; },
};

// ---------- Facebook Page Reels: start -> upload (hosted file) -> finish(PUBLISHED) -> status ----------
export const facebook: ProviderAdapter = {
  platform: 'FACEBOOK',
  capabilities: { video: true, image: false, maxCaption: 2200, asyncProcessing: true,
    approvalNote: 'Pages only (never personal profiles). Needs a Page access token from a user with CREATE_CONTENT and pages_manage_posts with Advanced Access. 30 Reels per rolling 24h. Reels cannot be scheduled through the API.' },

  async advance(ctx, state): Promise<StepResult> {
    const phase = state.phase ?? 'start';
    const page = ctx.accountExternalId;
    if (phase === 'start') {
      const { json } = await call(ctx, 'FACEBOOK', `${FB_BASE}/${page}/video_reels`, {
        method: 'POST', headers: { 'content-type': 'application/json' }, body: JSON.stringify({ upload_phase: 'start', access_token: ctx.accessToken }),
      });
      if (!json?.video_id) throw permanent('FACEBOOK', 'FB_NO_VIDEO_ID', 'Facebook did not start an upload session.');
      return { kind: 'progress', state: { phase: 'upload', videoId: String(json.video_id) }, progress: 0.1, waitMs: 0 };
    }
    if (phase === 'upload') {
      const url = await ctx.media.signedUrl(3600);
      const { json } = await call(ctx, 'FACEBOOK', `${FB_UPLOAD}/${state.videoId}`, {
        method: 'POST', headers: { authorization: `OAuth ${ctx.accessToken}`, file_url: url },
      });
      if (json?.success === false) throw permanent('FACEBOOK', 'FB_UPLOAD_REJECTED', 'Facebook could not fetch the video.');
      return { kind: 'progress', state: { ...state, phase: 'finish' }, progress: 0.6, waitMs: 5_000 };
    }
    if (phase === 'finish' || phase === 'finishing') {
      if (phase === 'finishing') throw permanent('FACEBOOK', 'AMBIGUOUS_PUBLISH', 'The Reel may already be live on the Page. Check the Page before retrying.');
      await ctx.checkpoint({ ...state, phase: 'finishing' });
      await call(ctx, 'FACEBOOK', `${FB_BASE}/${page}/video_reels`, {
        method: 'POST', headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ upload_phase: 'finish', video_id: state.videoId, video_state: 'PUBLISHED', description: captionText(ctx.payload, 2200), access_token: ctx.accessToken }),
      });
      return { kind: 'submitted', state: { ...state, phase: 'processing' } };
    }
    throw permanent('FACEBOOK', 'FB_BAD_STATE', 'Unexpected upload state.');
  },
  async status(ctx, state): Promise<StatusResult> {
    const { json } = await call(ctx, 'FACEBOOK', `${FB_BASE}/${state.videoId}?fields=status`, { headers: { authorization: `Bearer ${ctx.accessToken}` } });
    const pub = json?.status?.publishing_phase?.status as string | undefined;
    const vs = json?.status?.video_status as string | undefined;
    if (pub === 'published') return { kind: 'published', state, remoteId: String(state.videoId), url: `https://www.facebook.com/reel/${state.videoId}` };
    if (pub === 'error' || vs === 'error') return { kind: 'failed', failure: { platform: 'FACEBOOK', class: 'permanent', code: 'FB_PROCESSING_ERROR', message: 'Facebook could not process the Reel.' } };
    return { kind: 'processing', state, waitMs: 30_000 };
  },
};


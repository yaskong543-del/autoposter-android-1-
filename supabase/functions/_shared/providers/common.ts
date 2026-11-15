import { classifyHttp } from '../errors.ts';
import { type Platform, type ProviderFailure, ProviderError } from '../types.ts';

export interface MediaSource {
  size: number;
  contentType: string;
  name: string;
  /** Time-limited URL the platform can fetch (private storage signed URL). */
  signedUrl(ttlSeconds: number): Promise<string>;
  /** Bytes [start, endExclusive). Implemented with an HTTP Range request against private storage. */
  readRange(start: number, endExclusive: number): Promise<Uint8Array>;
}

export interface PublishPayload {
  caption: string;
  title?: string;
  hashtags?: string[];
  privacy?: 'public' | 'unlisted' | 'private';
}

export type ProviderState = Record<string, unknown> & { phase?: string };

export interface AdapterCtx {
  fetch: typeof fetch;
  accessToken: string;
  accountExternalId: string;
  accountMeta: Record<string, unknown>;
  payload: PublishPayload;
  media: MediaSource;
  /** Max bytes this invocation may move through the Edge Function (memory/time guard). */
  budgetBytes: number;
  nowMs: number;
  /** Persist state BEFORE a non-repeatable provider call so a crash can be reconciled instead of repeated. */
  checkpoint(state: ProviderState): Promise<void>;
  config: { linkedinVersion: string };
}

export type StepResult =
  | { kind: 'progress'; state: ProviderState; progress: number; waitMs?: number }
  | { kind: 'submitted'; state: ProviderState }
  | { kind: 'published'; state: ProviderState; remoteId: string; url?: string };

export type StatusResult =
  | { kind: 'processing'; state: ProviderState; waitMs?: number }
  | { kind: 'published'; state: ProviderState; remoteId: string; url?: string; note?: string }
  | { kind: 'failed'; failure: ProviderFailure };

export interface Capabilities {
  video: boolean;
  image: boolean;           // v0.8: false for every provider (image publishing is not implemented)
  maxCaption: number;
  asyncProcessing: boolean;
  approvalNote: string;
}

export interface ProviderAdapter {
  platform: Platform;
  capabilities: Capabilities;
  /** Do a bounded amount of work. Must be safe to call again with the returned state. */
  advance(ctx: AdapterCtx, state: ProviderState): Promise<StepResult>;
  /** Poll after `submitted`. */
  status(ctx: AdapterCtx, state: ProviderState): Promise<StatusResult>;
}

export function captionText(p: PublishPayload, max: number): string {
  const tags = (p.hashtags ?? []).map((t) => (t.startsWith('#') ? t : `#${t}`)).join(' ');
  const full = [p.caption, tags].filter(Boolean).join('\n\n');
  return full.length <= max ? full : full.slice(0, max);
}

export function networkError(platform: Platform, e: unknown): ProviderError {
  return new ProviderError({ platform, class: 'transient', code: 'NETWORK', message: 'Network problem while contacting the platform.' });
}

/** fetch() that converts thrown network errors into normalised transient failures (never leaks URLs/tokens). */
export async function send(ctx: AdapterCtx, platform: Platform, url: string, init?: RequestInit): Promise<Response> {
  try { return await ctx.fetch(url, init); } catch (e) { throw networkError(platform, e); }
}

export async function readJson(res: Response): Promise<any> {
  const t = await res.text();
  if (!t) return undefined;
  try { return JSON.parse(t); } catch { return undefined; }
}

/** send() + throw a classified ProviderError unless the status is in `okStatuses` (default: any 2xx). */
export async function call(
  ctx: AdapterCtx, platform: Platform, url: string, init?: RequestInit, okStatuses?: number[],
): Promise<{ res: Response; json: any }> {
  const res = await send(ctx, platform, url, init);
  const json = await readJson(res);
  const ok = okStatuses ? okStatuses.includes(res.status) : res.ok;
  if (!ok) throw new ProviderError(classifyHttp(platform, res.status, json, res.headers, ctx.nowMs));
  return { res, json };
}

export const permanent = (platform: Platform, code: string, message: string): ProviderError =>
  new ProviderError({ platform, class: 'permanent', code, message });

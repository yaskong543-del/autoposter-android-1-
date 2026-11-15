import { type Platform } from '../types.ts';
import { type ProviderAdapter } from './common.ts';
import { youtube } from './youtube.ts';
import { instagram, threads, facebook } from './meta.ts';
import { tiktok } from './tiktok.ts';
import { x } from './x.ts';
import { linkedin } from './linkedin.ts';

export const ADAPTERS: Record<Platform, ProviderAdapter> = {
  YOUTUBE: youtube, INSTAGRAM: instagram, TIKTOK: tiktok, FACEBOOK: facebook, THREADS: threads, X: x, LINKEDIN: linkedin,
};
export function adapterFor(p: Platform): ProviderAdapter { return ADAPTERS[p]; }
export * from './common.ts';

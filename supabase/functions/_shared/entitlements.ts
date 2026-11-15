// Pure mirror of the server rules in migration 004 (the database enforces them; this file documents and tests them).
export type PlanId = 'free' | 'pro' | 'agency';
export interface Entitlement {
  maxProfiles: number | null;   // null = unlimited
  maxDevices: number;
  aiTokensMonthly: number;
  autoSync: boolean;
  cellularUploads: boolean;     // allowed only when the user has also enabled it in settings
  advancedAutomation: boolean;
}
export const ENTITLEMENTS: Record<PlanId, Entitlement> = {
  free:   { maxProfiles: 2,    maxDevices: 1, aiTokensMonthly: 5_000,   autoSync: false, cellularUploads: false, advancedAutomation: false },
  pro:    { maxProfiles: null, maxDevices: 5, aiTokensMonthly: 500_000, autoSync: true,  cellularUploads: true,  advancedAutomation: true },
  agency: { maxProfiles: null, maxDevices: 5, aiTokensMonthly: 500_000, autoSync: true,  cellularUploads: true,  advancedAutomation: true },
};
export const isPremium = (p: PlanId): boolean => p !== 'free';

export function canAddProfile(plan: PlanId, currentCount: number): boolean {
  const m = ENTITLEMENTS[plan].maxProfiles;
  return m === null || currentCount < m;
}
export function canAddDevice(plan: PlanId, activeDevices: number): boolean {
  return activeDevices < ENTITLEMENTS[plan].maxDevices;
}

/**
 * Downgrade / expiry never deletes data. Items beyond the new limit stay visible but are suspended:
 * the oldest N (by created_at) remain active, the rest cannot publish/sync until the user removes items or upgrades.
 */
export function activeSet<T extends { id: string; createdAt: number }>(items: T[], limit: number | null): { active: T[]; suspended: T[] } {
  const sorted = [...items].sort((a, b) => a.createdAt - b.createdAt || a.id.localeCompare(b.id));
  if (limit === null) return { active: sorted, suspended: [] };
  return { active: sorted.slice(0, limit), suspended: sorted.slice(limit) };
}

export interface UploadPolicyInput { plan: PlanId; userAllowsCellular: boolean; network: 'wifi' | 'cellular' | 'none' }
/** Effective background-upload decision (also computed on the device for UX; the server decides which jobs may be sent). */
export function uploadAllowed(i: UploadPolicyInput): { allowed: boolean; reason: 'ok' | 'offline' | 'wifi_only_plan' | 'cellular_disabled' } {
  if (i.network === 'none') return { allowed: false, reason: 'offline' };
  if (i.network === 'wifi') return { allowed: true, reason: 'ok' };
  if (!ENTITLEMENTS[i.plan].cellularUploads) return { allowed: false, reason: 'wifi_only_plan' };
  if (!i.userAllowsCellular) return { allowed: false, reason: 'cellular_disabled' };
  return { allowed: true, reason: 'ok' };
}

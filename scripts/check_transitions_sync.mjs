// Fails when the SQL state machine (public.publish_job_transition_allowed) and the TypeScript one (jobs.ts) disagree.
import { readFileSync } from 'node:fs';
import { TRANSITIONS } from '../supabase/functions/_shared/jobs.ts';
const sql = new Set(readFileSync(process.argv[2], 'utf8').split('\n').map((l) => l.trim()).filter(Boolean));
const ts = new Set(Object.entries(TRANSITIONS).flatMap(([f, ts]) => ts.map((t) => `${f}>${t}`)));
const onlySql = [...sql].filter((x) => !ts.has(x)), onlyTs = [...ts].filter((x) => !sql.has(x));
if (onlySql.length || onlyTs.length) { console.error('state machines differ', { onlySql, onlyTs }); process.exit(1); }
console.log(`state machines agree (${ts.size} transitions)`);

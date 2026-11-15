\pset format unaligned
\pset tuples_only on
select f || '>' || t from (select unnest(array['scheduled','due','leased','uploading','submitted','published','retryable_failure','permanent_failure','cancelled']) f) a
cross join (select unnest(array['scheduled','due','leased','uploading','submitted','published','retryable_failure','permanent_failure','cancelled']) t) b
where public.publish_job_transition_allowed(f, t) order by 1;

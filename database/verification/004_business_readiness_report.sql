-- Read-only, one-cell report for SETUP-13. Run as postgres in the development project.
-- No credentials or customer/personnel/payment rows are returned.
BEGIN READ ONLY;
SET LOCAL statement_timeout = '15s';
SELECT jsonb_build_object(
    'migration_versions', (SELECT jsonb_agg(version ORDER BY version) FROM vrms_private.schema_migrations),
    'legacy_context_function_exists', to_regprocedure('vrms_private.set_app_context(uuid,uuid)') IS NOT NULL,
    'application_table_count', (SELECT count(*) FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='vrms_private' AND c.relkind IN ('r','p')),
    'tables_without_forced_rls', (SELECT coalesce(jsonb_agg(c.relname ORDER BY c.relname), '[]'::jsonb)
        FROM pg_class c JOIN pg_namespace n ON n.oid=c.relnamespace
        WHERE n.nspname='vrms_private' AND c.relkind IN ('r','p') AND (NOT c.relrowsecurity OR NOT c.relforcerowsecurity)),
    'runtime_role', (SELECT jsonb_build_object('login',rolcanlogin,'superuser',rolsuper,'bypass_rls',rolbypassrls,
        'create_role',rolcreaterole,'create_db',rolcreatedb,'replication',rolreplication)
        FROM pg_roles WHERE rolname='smart_vrms_api')
) AS readiness_report;
COMMIT;

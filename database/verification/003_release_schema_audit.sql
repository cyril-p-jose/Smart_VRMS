-- SETUP-13: read-only metadata audit as postgres, isolated development project.
-- No customer/personnel/payment rows or credentials are returned. No substitutions.
BEGIN READ ONLY;
SET LOCAL statement_timeout = '15s';
SELECT version, description FROM vrms_private.schema_migrations ORDER BY version;
SELECT to_regprocedure('vrms_private.set_app_context(uuid,uuid)') IS NOT NULL AS legacy_context_function_exists;
SELECT c.relname AS application_table, c.relrowsecurity AS rls_enabled,
       c.relforcerowsecurity AS rls_forced
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'vrms_private' AND c.relkind IN ('r', 'p') ORDER BY c.relname;
SELECT rolname, rolcanlogin, rolsuper, rolbypassrls, rolcreaterole, rolcreatedb, rolreplication
FROM pg_roles WHERE rolname = 'smart_vrms_api';
SELECT table_name, privilege_type FROM information_schema.role_table_grants
WHERE table_schema = 'vrms_private' AND grantee = 'smart_vrms_api'
ORDER BY table_name, privilege_type;
COMMIT;

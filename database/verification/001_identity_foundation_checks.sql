-- Run after migration 001, as postgres. Assertions raise errors; no persistent writes.
-- These are structural/empty-context checks, NOT authenticated-user isolation tests.
BEGIN;
SET LOCAL statement_timeout = '30s';
DO $checks$
DECLARE
    role_name text;
    table_name text;
    privilege_name text;
BEGIN
    IF current_user <> 'postgres' THEN RAISE EXCEPTION 'Use postgres for setup verification.'; END IF;
    IF NOT pg_has_role('postgres', 'smart_vrms_api', 'SET') THEN
        RAISE EXCEPTION 'Setup operator cannot assume the restricted role for verification.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 1) THEN
        RAISE EXCEPTION 'Migration 001 is missing.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smart_vrms_api'
        AND NOT rolcanlogin AND NOT rolsuper AND NOT rolbypassrls AND NOT rolcreatedb
        AND NOT rolcreaterole AND NOT rolreplication AND NOT rolinherit) THEN
        RAISE EXCEPTION 'Restricted role attributes do not match this setup stage.';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_auth_members WHERE member = 'smart_vrms_api'::regrole) THEN
        RAISE EXCEPTION 'Runtime role inherits or can assume an unexpected role.';
    END IF;
    IF has_schema_privilege('smart_vrms_api', 'vrms_private', 'CREATE') THEN
        RAISE EXCEPTION 'Runtime role must not create schema objects.';
    END IF;
    IF has_schema_privilege('smart_vrms_api', 'public', 'CREATE')
       OR has_database_privilege('smart_vrms_api', current_database(), 'CREATE') THEN
        RAISE EXCEPTION 'Inherited public/database CREATE privileges require review before runtime login.';
    END IF;
    IF (SELECT count(*) FROM pg_policies WHERE schemaname = 'vrms_private') <> 2
       OR (SELECT count(*) FROM pg_policies WHERE schemaname = 'vrms_private'
           AND cmd = 'SELECT' AND roles = ARRAY['smart_vrms_api']::name[]
           AND policyname IN ('own_active_membership', 'active_member_company')) <> 2 THEN
        RAISE EXCEPTION 'Unexpected policy scope.';
    END IF;
    FOREACH table_name IN ARRAY ARRAY['companies', 'memberships', 'membership_audit', 'schema_migrations'] LOOP
        IF NOT EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE n.nspname = 'vrms_private' AND c.relname = table_name
            AND c.relrowsecurity AND c.relforcerowsecurity
            AND c.relowner = 'postgres'::regrole) THEN
            RAISE EXCEPTION 'Table ownership or RLS flags failed.';
        END IF;
        FOREACH role_name IN ARRAY ARRAY['anon', 'authenticated', 'service_role'] LOOP
            IF has_schema_privilege(role_name, 'vrms_private', 'USAGE') THEN
                RAISE EXCEPTION 'A Data API role can access the private schema.';
            END IF;
            FOREACH privilege_name IN ARRAY ARRAY['SELECT', 'INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'] LOOP
                IF has_table_privilege(role_name, 'vrms_private.' || table_name, privilege_name) THEN
                    RAISE EXCEPTION 'A Data API role has private table privileges.';
                END IF;
            END LOOP;
        END LOOP;
        FOREACH privilege_name IN ARRAY ARRAY['INSERT', 'UPDATE', 'DELETE', 'TRUNCATE', 'REFERENCES', 'TRIGGER'] LOOP
            IF has_table_privilege('smart_vrms_api', 'vrms_private.' || table_name, privilege_name) THEN
                RAISE EXCEPTION 'Runtime role has unexpected mutation privileges.';
            END IF;
        END LOOP;
        IF has_table_privilege('smart_vrms_api', 'vrms_private.' || table_name, 'SELECT')
            IS DISTINCT FROM (table_name IN ('companies', 'memberships')) THEN
            RAISE EXCEPTION 'Runtime read privileges do not match the approved scope.';
        END IF;
    END LOOP;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conrelid = 'vrms_private.companies'::regclass
        AND conname = 'company_active_administrator' AND contype = 'f'
        AND condeferrable AND condeferred AND convalidated) THEN
        RAISE EXCEPTION 'Active administrator constraint is missing.';
    END IF;
END
$checks$;

SET LOCAL ROLE smart_vrms_api;
SELECT set_config('vrms.user_id', '', true), set_config('vrms.company_id', '', true);
DO $deny$
BEGIN
    IF EXISTS (SELECT 1 FROM vrms_private.memberships)
       OR EXISTS (SELECT 1 FROM vrms_private.companies) THEN
        RAISE EXCEPTION 'Missing identity must return zero rows.';
    END IF;
END
$deny$;
RESET ROLE;
SELECT 'SETUP-05 foundation checks PASS; live staff isolation and runtime login remain pending' AS result;
ROLLBACK;

-- SETUP-06: run entire file as postgres. No substitutions. All mutations roll back.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
DO $state$
BEGIN
    IF current_user <> 'postgres' THEN RAISE EXCEPTION 'Use the setup operator.'; END IF;
    IF (SELECT count(*) FROM vrms_private.companies) <> 1
       OR (SELECT count(*) FROM vrms_private.memberships) <> 1 THEN
        RAISE EXCEPTION 'Expected the initial company and exactly one membership.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vrms_private.companies c JOIN vrms_private.memberships m
        ON m.company_id = c.id AND m.auth_user_id = c.primary_admin_user_id
        WHERE m.active AND m.role = 'ADMINISTRATOR') THEN
        RAISE EXCEPTION 'Active administrator relationship missing.';
    END IF;
    IF (SELECT count(*) FROM vrms_private.membership_audit WHERE event = 'ADMIN_BOOTSTRAPPED') <> 1 THEN
        RAISE EXCEPTION 'Bootstrap audit missing or duplicated.';
    END IF;
    PERFORM set_config('vrms.test_user', primary_admin_user_id::text, true),
            set_config('vrms.test_company', id::text, true) FROM vrms_private.companies;
END
$state$;

SET LOCAL ROLE smart_vrms_api;
DO $reads$
BEGIN
    PERFORM set_config('vrms.user_id', '', true), set_config('vrms.company_id', '', true);
    IF EXISTS (SELECT 1 FROM vrms_private.memberships) OR EXISTS (SELECT 1 FROM vrms_private.companies) THEN
        RAISE EXCEPTION 'Missing context exposed populated records.';
    END IF;
    PERFORM set_config('vrms.user_id', current_setting('vrms.test_user'), true),
            set_config('vrms.company_id', current_setting('vrms.test_company'), true);
    IF (SELECT count(*) FROM vrms_private.memberships) <> 1 OR (SELECT count(*) FROM vrms_private.companies) <> 1 THEN
        RAISE EXCEPTION 'Active administrator cannot read its membership/company.';
    END IF;
    PERFORM set_config('vrms.company_id', gen_random_uuid()::text, true);
    IF EXISTS (SELECT 1 FROM vrms_private.companies) THEN RAISE EXCEPTION 'Wrong company exposed data.'; END IF;
    PERFORM set_config('vrms.company_id', current_setting('vrms.test_company'), true),
            set_config('vrms.user_id', gen_random_uuid()::text, true);
    IF EXISTS (SELECT 1 FROM vrms_private.memberships) OR EXISTS (SELECT 1 FROM vrms_private.companies) THEN
        RAISE EXCEPTION 'Nonmember exposed records.';
    END IF;
    PERFORM set_config('vrms.user_id', current_setting('vrms.test_user'), true);
    BEGIN
        UPDATE vrms_private.memberships SET role = 'EMPLOYEE';
        RAISE EXCEPTION 'Runtime role unexpectedly changed membership.';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
    BEGIN
        PERFORM 1 FROM vrms_private.membership_audit;
        RAISE EXCEPTION 'Runtime role unexpectedly read audit records.';
    EXCEPTION WHEN insufficient_privilege THEN NULL;
    END;
END
$reads$;
RESET ROLE;

-- Test the database constraint as operator: the failed subtransaction rolls back.
DO $last_admin$
DECLARE failed_constraint text;
BEGIN
    BEGIN
        UPDATE vrms_private.memberships SET active = false;
        SET CONSTRAINTS ALL IMMEDIATE;
        RAISE EXCEPTION 'Last-administrator deactivation was not blocked.';
    EXCEPTION WHEN foreign_key_violation THEN
        GET STACKED DIAGNOSTICS failed_constraint = CONSTRAINT_NAME;
        IF failed_constraint <> 'company_active_administrator' THEN RAISE; END IF;
    END;
END
$last_admin$;

-- Inactive membership RLS test: temporarily defer the admin FK, then restore
-- via savepoint rollback, never committing a company without an active admin.
SAVEPOINT inactive_test;
SET CONSTRAINTS ALL DEFERRED;
UPDATE vrms_private.memberships SET active = false;
SET LOCAL ROLE smart_vrms_api;
DO $inactive$
BEGIN
    IF EXISTS (SELECT 1 FROM vrms_private.memberships) OR EXISTS (SELECT 1 FROM vrms_private.companies) THEN
        RAISE EXCEPTION 'Inactive membership exposed records.';
    END IF;
END
$inactive$;
RESET ROLE;
ROLLBACK TO SAVEPOINT inactive_test;
SELECT 'SETUP-06 administrator checks PASS; real login and employee tests remain pending' AS result;
ROLLBACK;

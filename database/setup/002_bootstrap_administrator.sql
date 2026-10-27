-- SETUP-06: trusted operator only, isolated development project, run as postgres.
-- Replace ONLY the zero UUID below with your own confirmed Auth user's UID.
-- No password, email, token or API key belongs in this file.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';
DO $bootstrap$
DECLARE
    admin_uid uuid := '00000000-0000-0000-0000-000000000000';
    new_company_id uuid := gen_random_uuid();
BEGIN
    IF current_user <> 'postgres' THEN RAISE EXCEPTION 'Bootstrap requires the setup operator.'; END IF;
    IF admin_uid = '00000000-0000-0000-0000-000000000000'::uuid THEN
        RAISE EXCEPTION 'Replace the admin_uid placeholder with your confirmed Auth user UID.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 1) THEN
        RAISE EXCEPTION 'Migration 001 must pass before bootstrap.';
    END IF;
    LOCK TABLE vrms_private.companies, vrms_private.memberships IN SHARE ROW EXCLUSIVE MODE;
    IF EXISTS (SELECT 1 FROM vrms_private.companies) OR EXISTS (SELECT 1 FROM vrms_private.memberships) THEN
        RAISE EXCEPTION 'Company or membership already exists. Bootstrap will not overwrite it.';
    END IF;
    -- Read provider state only. Never insert/update provider-owned Auth records.
    PERFORM 1 FROM auth.users WHERE id = admin_uid
        AND email_confirmed_at IS NOT NULL AND email IS NOT NULL
        AND is_anonymous IS FALSE AND deleted_at IS NULL
        AND (banned_until IS NULL OR banned_until <= now()) FOR SHARE;
    IF NOT FOUND THEN RAISE EXCEPTION 'Selected Auth user is missing, unconfirmed, anonymous, deleted or banned.'; END IF;

    INSERT INTO vrms_private.companies(id, display_name, primary_admin_user_id)
        VALUES (new_company_id, 'Smart VRMS', admin_uid);
    INSERT INTO vrms_private.memberships(auth_user_id, company_id, role, active, created_by)
        VALUES (admin_uid, new_company_id, 'ADMINISTRATOR', true, admin_uid);
    INSERT INTO vrms_private.membership_audit(company_id, actor_user_id, subject_user_id, event)
        VALUES (new_company_id, admin_uid, admin_uid, 'ADMIN_BOOTSTRAPPED');
    -- Check the circular company/admin relationship before the success response.
    SET CONSTRAINTS ALL IMMEDIATE;
END
$bootstrap$;
COMMIT;

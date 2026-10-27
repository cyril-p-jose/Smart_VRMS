-- Smart VRMS / SETUP-05 / migration 001. Run once in the isolated development project.
-- Execute the entire file as postgres in Supabase SQL Editor. No passwords are needed.
-- Transactional and deliberately not IF NOT EXISTS: never silently accept schema drift.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $guard$
BEGIN
    IF current_user <> 'postgres' THEN
        RAISE EXCEPTION 'Run this setup migration as postgres in the development project.';
    END IF;
    IF EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'vrms_private')
       OR EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smart_vrms_api') THEN
        RAISE EXCEPTION 'VRMS objects already exist. Stop and inspect; do not delete or rerun blindly.';
    END IF;
END
$guard$;

-- No login/password until the separate restricted-runtime-credential setup.
CREATE ROLE smart_vrms_api NOLOGIN NOINHERIT NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
-- The creator already receives role administration rights. Re-granting ADMIN
-- can form a forbidden grant cycle on Supabase. Only enable explicit SET ROLE
-- for the setup checks; do not inherit application privileges into postgres.
GRANT smart_vrms_api TO postgres WITH INHERIT FALSE, SET TRUE;
CREATE SCHEMA vrms_private AUTHORIZATION postgres;
REVOKE ALL ON SCHEMA vrms_private FROM PUBLIC, anon, authenticated, service_role;
GRANT USAGE ON SCHEMA vrms_private TO smart_vrms_api;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA vrms_private REVOKE ALL ON TABLES FROM PUBLIC, anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA vrms_private REVOKE ALL ON SEQUENCES FROM PUBLIC, anon, authenticated, service_role;
ALTER DEFAULT PRIVILEGES FOR ROLE postgres IN SCHEMA vrms_private REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC, anon, authenticated, service_role;

CREATE TABLE vrms_private.schema_migrations (
    version integer PRIMARY KEY,
    description text NOT NULL,
    applied_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE vrms_private.companies (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    singleton boolean NOT NULL DEFAULT true UNIQUE CHECK (singleton),
    display_name text NOT NULL CHECK (length(btrim(display_name)) BETWEEN 1 AND 120),
    currency text NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    time_zone text NOT NULL DEFAULT 'Asia/Kolkata' CHECK (time_zone = 'Asia/Kolkata'),
    primary_admin_user_id uuid NOT NULL,
    primary_admin_role text GENERATED ALWAYS AS ('ADMINISTRATOR'::text) STORED,
    primary_admin_active boolean GENERATED ALWAYS AS (true) STORED,
    created_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0)
);

CREATE TABLE vrms_private.memberships (
    auth_user_id uuid PRIMARY KEY REFERENCES auth.users(id) ON DELETE RESTRICT,
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    role text NOT NULL CHECK (role IN ('ADMINISTRATOR', 'EMPLOYEE')),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    created_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    UNIQUE (company_id, auth_user_id, role, active)
);

-- A company always points to an active administrator in the SAME company.
-- Deferred checking permits atomic first bootstrap and later administrator handover.
-- Demoting/revoking/deleting that administrator without a replacement fails at commit.
ALTER TABLE vrms_private.companies ADD CONSTRAINT company_active_administrator
    FOREIGN KEY (id, primary_admin_user_id, primary_admin_role, primary_admin_active)
    REFERENCES vrms_private.memberships(company_id, auth_user_id, role, active)
    DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE vrms_private.membership_audit (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    actor_user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
    subject_user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
    event text NOT NULL CHECK (event IN ('ADMIN_BOOTSTRAPPED', 'MEMBER_INVITED', 'MEMBER_ACTIVATED', 'MEMBER_REVOKED', 'ADMIN_TRANSFERRED')),
    occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX membership_audit_company_time ON vrms_private.membership_audit(company_id, occurred_at);

ALTER TABLE vrms_private.schema_migrations ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.schema_migrations FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.companies ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.companies FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.memberships ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.memberships FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.membership_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.membership_audit FORCE ROW LEVEL SECURITY;

-- Only a trusted Java server may assert these transaction-local settings, after
-- fresh provider validation. A database login is NOT an end-user credential.
-- Membership lookup precedes company selection, so it only needs the verified UID.
CREATE POLICY own_active_membership ON vrms_private.memberships
    FOR SELECT TO smart_vrms_api
    USING (active AND auth_user_id = nullif(current_setting('vrms.user_id', true), '')::uuid);

CREATE POLICY active_member_company ON vrms_private.companies
    FOR SELECT TO smart_vrms_api
    USING (id = nullif(current_setting('vrms.company_id', true), '')::uuid
        AND EXISTS (SELECT 1 FROM vrms_private.memberships m WHERE m.company_id = companies.id));

-- No grants or policies for application writes, audit reads, migration reads,
-- self-promotion or employee invitations. Those require later reviewed operations.
REVOKE ALL ON ALL TABLES IN SCHEMA vrms_private FROM PUBLIC, anon, authenticated, service_role, smart_vrms_api;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA vrms_private FROM PUBLIC, anon, authenticated, service_role, smart_vrms_api;
GRANT SELECT ON vrms_private.companies, vrms_private.memberships TO smart_vrms_api;

INSERT INTO vrms_private.schema_migrations(version, description)
VALUES (1, 'Private company and membership foundation; read-only NOLOGIN server role');
COMMIT;

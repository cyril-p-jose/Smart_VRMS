-- Smart VRMS / Stage 2B / migration 003.
-- Run once in the isolated development project as postgres.
-- Transactional and non-destructive: adds audit, idempotency, and private file metadata.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $guard$
BEGIN
    IF current_user <> 'postgres' THEN
        RAISE EXCEPTION 'Run this migration as postgres in the development project.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 1) THEN
        RAISE EXCEPTION 'Migration 001 must be applied before migration 003.';
    END IF;
    IF EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 2) THEN
        RAISE EXCEPTION 'Migration 003 is already recorded as applied.';
    END IF;
END
$guard$;

-- ============================================================================
-- 1. IMMUTABLE BUSINESS AUDIT EVENTS (FR-DATA-001)
-- ============================================================================
CREATE TABLE vrms_private.audit_events (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    actor_user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
    action text NOT NULL CHECK (length(btrim(action)) BETWEEN 1 AND 100),
    resource_type text NOT NULL CHECK (length(btrim(resource_type)) BETWEEN 1 AND 50),
    resource_id text NOT NULL CHECK (length(btrim(resource_id)) BETWEEN 1 AND 100),
    details jsonb NOT NULL DEFAULT '{}'::jsonb,
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX audit_events_company_time ON vrms_private.audit_events(company_id, occurred_at DESC);
CREATE INDEX audit_events_resource ON vrms_private.audit_events(company_id, resource_type, resource_id);

ALTER TABLE vrms_private.audit_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.audit_events FORCE ROW LEVEL SECURITY;

CREATE POLICY audit_events_select ON vrms_private.audit_events
    FOR SELECT TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY audit_events_insert ON vrms_private.audit_events
    FOR INSERT TO smart_vrms_api
    WITH CHECK (
        company_id = nullif(current_setting('vrms.company_id', true), '')::uuid
        AND actor_user_id = nullif(current_setting('vrms.user_id', true), '')::uuid
    );

-- ============================================================================
-- 2. IDEMPOTENT TRANSACTION OUTCOME STORE (FR-DATA-003)
-- ============================================================================
CREATE TABLE vrms_private.idempotency_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    idempotency_key text NOT NULL CHECK (length(btrim(idempotency_key)) BETWEEN 16 AND 128),
    actor_user_id uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
    operation text NOT NULL CHECK (length(btrim(operation)) BETWEEN 1 AND 100),
    request_hash text NOT NULL CHECK (length(request_hash) = 64),
    status text NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED')),
    response_status integer CHECK (response_status >= 100 AND response_status <= 599),
    response_body text,
    created_at timestamptz NOT NULL DEFAULT now(),
    expires_at timestamptz NOT NULL,
    UNIQUE (company_id, idempotency_key)
);

CREATE INDEX idempotency_company_key ON vrms_private.idempotency_records(company_id, idempotency_key);
CREATE INDEX idempotency_expires ON vrms_private.idempotency_records(expires_at);

ALTER TABLE vrms_private.idempotency_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.idempotency_records FORCE ROW LEVEL SECURITY;

CREATE POLICY idempotency_own_company ON vrms_private.idempotency_records
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

-- ============================================================================
-- 3. PRIVATE FILE METADATA & LIFECYCLE (FR-DATA-002, FR-ACC-003)
-- ============================================================================
CREATE TABLE vrms_private.file_metadata (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    uploaded_by uuid NOT NULL REFERENCES auth.users(id) ON DELETE RESTRICT,
    storage_bucket text NOT NULL CHECK (storage_bucket IN ('vrms-private-media', 'vrms-private-documents')),
    storage_path text NOT NULL UNIQUE CHECK (length(storage_path) BETWEEN 10 AND 255),
    file_name text NOT NULL CHECK (length(btrim(file_name)) BETWEEN 1 AND 255),
    content_type text NOT NULL CHECK (content_type IN ('image/jpeg', 'image/png', 'application/pdf')),
    size_bytes bigint NOT NULL CHECK (size_bytes > 0 AND size_bytes <= 10485760), -- max 10MB
    category text NOT NULL CHECK (category IN (
        'VEHICLE_PHOTO', 'VEHICLE_DOC', 'DRIVER_DOC',
        'STAFF_PHOTO', 'PAYROLL_STATEMENT', 'CUSTOMER_DOC',
        'INVOICE_PDF', 'RECEIPT_IMAGE'
    )),
    access_tier text NOT NULL CHECK (access_tier IN ('ADMIN_ONLY', 'OPERATIONAL_STAFF')),
    status text NOT NULL CHECK (status IN ('PENDING', 'FINALIZED', 'PURGED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    finalized_at timestamptz,
    CONSTRAINT payroll_must_be_admin_only CHECK (
        category <> 'PAYROLL_STATEMENT' OR access_tier = 'ADMIN_ONLY'
    )
);

CREATE INDEX file_metadata_company_cat ON vrms_private.file_metadata(company_id, category, status);

ALTER TABLE vrms_private.file_metadata ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.file_metadata FORCE ROW LEVEL SECURITY;

-- Administrators can select all company files; Employees can only select OPERATIONAL_STAFF files.
CREATE POLICY file_metadata_select ON vrms_private.file_metadata
    FOR SELECT TO smart_vrms_api
    USING (
        company_id = nullif(current_setting('vrms.company_id', true), '')::uuid
        AND (
            current_setting('vrms.role', true) = 'ADMINISTRATOR'
            OR (current_setting('vrms.role', true) = 'EMPLOYEE' AND access_tier = 'OPERATIONAL_STAFF')
        )
    );

CREATE POLICY file_metadata_insert ON vrms_private.file_metadata
    FOR INSERT TO smart_vrms_api
    WITH CHECK (
        company_id = nullif(current_setting('vrms.company_id', true), '')::uuid
        AND uploaded_by = nullif(current_setting('vrms.user_id', true), '')::uuid
        AND (
            current_setting('vrms.role', true) = 'ADMINISTRATOR'
            OR (current_setting('vrms.role', true) = 'EMPLOYEE' AND access_tier = 'OPERATIONAL_STAFF')
        )
    );

CREATE POLICY file_metadata_update ON vrms_private.file_metadata
    FOR UPDATE TO smart_vrms_api
    USING (
        company_id = nullif(current_setting('vrms.company_id', true), '')::uuid
        AND (
            current_setting('vrms.role', true) = 'ADMINISTRATOR'
            OR (current_setting('vrms.role', true) = 'EMPLOYEE' AND access_tier = 'OPERATIONAL_STAFF')
        )
    )
    WITH CHECK (
        company_id = nullif(current_setting('vrms.company_id', true), '')::uuid
    );

-- ============================================================================
-- 4. MEMBERSHIP INVITATIONS, ACTIVATION & REVOCATION (FR-AUTH-002, FR-EMP-004)
-- ============================================================================
-- Add INSERT and UPDATE policies for memberships to allow authorized Administrator management.
CREATE POLICY admin_insert_membership ON vrms_private.memberships
    FOR INSERT TO smart_vrms_api
    WITH CHECK (
        company_id = nullif(current_setting('vrms.company_id', true), '')::uuid
        AND current_setting('vrms.role', true) = 'ADMINISTRATOR'
    );

CREATE POLICY admin_update_membership ON vrms_private.memberships
    FOR UPDATE TO smart_vrms_api
    USING (
        company_id = nullif(current_setting('vrms.company_id', true), '')::uuid
        AND current_setting('vrms.role', true) = 'ADMINISTRATOR'
    )
    WITH CHECK (
        company_id = nullif(current_setting('vrms.company_id', true), '')::uuid
    );

-- Company update policy for administrator handover
CREATE POLICY admin_update_company ON vrms_private.companies
    FOR UPDATE TO smart_vrms_api
    USING (
        id = nullif(current_setting('vrms.company_id', true), '')::uuid
        AND current_setting('vrms.role', true) = 'ADMINISTRATOR'
    )
    WITH CHECK (
        id = nullif(current_setting('vrms.company_id', true), '')::uuid
    );

-- Explicit grants to smart_vrms_api
GRANT SELECT, INSERT ON vrms_private.audit_events TO smart_vrms_api;
GRANT SELECT, INSERT, UPDATE, DELETE ON vrms_private.idempotency_records TO smart_vrms_api;
GRANT SELECT, INSERT, UPDATE ON vrms_private.file_metadata TO smart_vrms_api;
GRANT INSERT, UPDATE ON vrms_private.memberships TO smart_vrms_api;
GRANT UPDATE ON vrms_private.companies TO smart_vrms_api;

-- Record migration version
INSERT INTO vrms_private.schema_migrations(version, description)
VALUES (2, 'Shared security: audit events, idempotency outcome store, file metadata and member management');

COMMIT;

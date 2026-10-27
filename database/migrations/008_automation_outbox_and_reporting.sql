-- Smart VRMS / Stage 7 / migration 008.
-- Run once in the isolated development project as postgres.
-- Transactional and non-destructive: outbox, delivery attempts, reminders, reviews, and reporting support.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $guard$
BEGIN
    IF current_user <> 'postgres' THEN
        RAISE EXCEPTION 'Run this migration as postgres in the development project.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 6) THEN
        RAISE EXCEPTION 'Migration 007 must be applied before migration 008.';
    END IF;
    IF EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 7) THEN
        RAISE EXCEPTION 'Migration 008 is already recorded as applied.';
    END IF;
END
$guard$;

-- ============================================================================
-- 1. TRANSACTIONAL OUTBOX & ATTEMPTS (FR-BOOK-011, FR-MSG-001, FR-MSG-002)
-- ============================================================================
CREATE TABLE vrms_private.outbox_messages (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    event_type text NOT NULL CHECK (event_type IN ('BOOKING_CONFIRMATION', 'EXPIRY_REMINDER_72H', 'EXPIRY_REMINDER_EXPIRED', 'SERVICE_SETTLEMENT', 'MAINTENANCE_ALERT')),
    idempotency_key text NOT NULL,
    recipient_email text NOT NULL CHECK (position('@' in recipient_email) > 1),
    recipient_role text NOT NULL CHECK (recipient_role IN ('CUSTOMER', 'ADMINISTRATOR', 'STAFF_FLEET', 'DRIVER')),
    subject text NOT NULL CHECK (length(btrim(subject)) BETWEEN 1 AND 255),
    template_name text NOT NULL,
    payload_json jsonb NOT NULL DEFAULT '{}'::jsonb,
    attachment_doc_id uuid REFERENCES vrms_private.billing_documents(id) ON DELETE SET NULL,
    status text NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED', 'CLAIMED', 'ACCEPTED', 'FAILED', 'SUPERSEDED')),
    lease_until timestamptz,
    claimed_by text,
    attempts_count integer NOT NULL DEFAULT 0 CHECK (attempts_count >= 0),
    max_attempts integer NOT NULL DEFAULT 5 CHECK (max_attempts > 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_attempt_at timestamptz,
    last_error text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_outbox_idempotency_key UNIQUE (company_id, idempotency_key)
);

CREATE INDEX idx_outbox_poll ON vrms_private.outbox_messages(company_id, status, next_attempt_at)
    WHERE status IN ('QUEUED', 'CLAIMED');
CREATE INDEX idx_outbox_created ON vrms_private.outbox_messages(company_id, created_at);

CREATE TABLE vrms_private.delivery_attempts (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    outbox_id uuid NOT NULL REFERENCES vrms_private.outbox_messages(id) ON DELETE CASCADE,
    attempt_number integer NOT NULL CHECK (attempt_number > 0),
    started_at timestamptz NOT NULL DEFAULT now(),
    finished_at timestamptz,
    provider_name text NOT NULL DEFAULT 'TEST_SMTP_PROVIDER',
    provider_response_code integer,
    provider_message_id text,
    error_message text,
    outcome text NOT NULL CHECK (outcome IN ('ACCEPTED', 'RETRYABLE_ERROR', 'PERMANENT_FAILURE')),
    CONSTRAINT uq_outbox_attempt UNIQUE (outbox_id, attempt_number)
);

CREATE INDEX idx_attempts_outbox ON vrms_private.delivery_attempts(company_id, outbox_id);

-- ============================================================================
-- 2. DOCUMENT REMINDER SCANS (FR-DOC-003, FR-DOC-004)
-- ============================================================================
CREATE TABLE vrms_private.document_reminder_scans (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    document_id uuid NOT NULL REFERENCES vrms_private.vehicle_documents(id) ON DELETE CASCADE,
    document_version integer NOT NULL CHECK (document_version > 0),
    reminder_stage text NOT NULL CHECK (reminder_stage IN ('LESS_THAN_72H', 'EXPIRED')),
    recipient_email text NOT NULL,
    outbox_id uuid REFERENCES vrms_private.outbox_messages(id) ON DELETE SET NULL,
    dispatched_at timestamptz NOT NULL DEFAULT now(),
    superseded boolean NOT NULL DEFAULT false,
    CONSTRAINT uq_reminder_version_stage UNIQUE (company_id, document_id, document_version, reminder_stage, recipient_email)
);

CREATE INDEX idx_reminder_doc_ver ON vrms_private.document_reminder_scans(company_id, document_id, document_version);

-- ============================================================================
-- 3. SERVICE REVIEWS & TRUSTWORTHY FEEDBACK (FR-REV-001, FR-REV-002)
-- ============================================================================
CREATE TABLE vrms_private.service_reviews (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL REFERENCES vrms_private.bookings(id) ON DELETE RESTRICT,
    customer_id uuid NOT NULL REFERENCES vrms_private.customers(id) ON DELETE RESTRICT,
    visit_id uuid NOT NULL REFERENCES vrms_private.customer_visits(id) ON DELETE RESTRICT,
    rating integer NOT NULL CHECK (rating BETWEEN 1 AND 5),
    feedback_text text NOT NULL CHECK (length(btrim(feedback_text)) BETWEEN 1 AND 2000),
    communication_source text NOT NULL CHECK (communication_source IN ('PHONE', 'EMAIL', 'IN_PERSON', 'DESK_FORM')),
    consent_given boolean NOT NULL DEFAULT true,
    status text NOT NULL DEFAULT 'RECORDED' CHECK (status IN ('RECORDED', 'CORRECTED', 'FLAGGED_DUPLICATE')),
    recorded_by uuid NOT NULL REFERENCES auth.users(id),
    recorded_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_booking_review UNIQUE (company_id, booking_id)
);

CREATE INDEX idx_reviews_customer ON vrms_private.service_reviews(company_id, customer_id);

CREATE TABLE vrms_private.service_review_corrections (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    review_id uuid NOT NULL REFERENCES vrms_private.service_reviews(id) ON DELETE CASCADE,
    previous_rating integer NOT NULL CHECK (previous_rating BETWEEN 1 AND 5),
    revised_rating integer NOT NULL CHECK (revised_rating BETWEEN 1 AND 5),
    previous_feedback text NOT NULL,
    revised_feedback text NOT NULL,
    correction_reason text NOT NULL CHECK (length(btrim(correction_reason)) BETWEEN 1 AND 500),
    corrected_by uuid NOT NULL REFERENCES auth.users(id),
    corrected_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_review_corrections ON vrms_private.service_review_corrections(company_id, review_id);

-- ============================================================================
-- 4. ROW LEVEL SECURITY (RLS) & RESTRICTED GRANTS
-- ============================================================================
ALTER TABLE vrms_private.outbox_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.outbox_messages FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.delivery_attempts ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.delivery_attempts FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.document_reminder_scans ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.document_reminder_scans FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.service_reviews ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.service_reviews FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.service_review_corrections ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.service_review_corrections FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_outbox ON vrms_private.outbox_messages
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY tenant_isolation_attempts ON vrms_private.delivery_attempts
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY tenant_isolation_reminder_scans ON vrms_private.document_reminder_scans
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY tenant_isolation_service_reviews ON vrms_private.service_reviews
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY tenant_isolation_review_corrections ON vrms_private.service_review_corrections
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE
    vrms_private.outbox_messages,
    vrms_private.delivery_attempts,
    vrms_private.document_reminder_scans,
    vrms_private.service_reviews,
    vrms_private.service_review_corrections
TO smart_vrms_api;

-- Record migration version
INSERT INTO vrms_private.schema_migrations (version, description)
VALUES (7, 'automation outbox, reminders, reviews, and reporting');

COMMIT;

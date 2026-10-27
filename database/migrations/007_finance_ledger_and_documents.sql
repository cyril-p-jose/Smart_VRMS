-- Smart VRMS / Stage 6 / migration 007.
-- Run once in the isolated development project as postgres.
-- Transactional and non-destructive: finance, payments, ledger, deposits, and billing documents.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $guard$
BEGIN
    IF current_user <> 'postgres' THEN
        RAISE EXCEPTION 'Run this migration as postgres in the development project.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 5) THEN
        RAISE EXCEPTION 'Migration 006 must be applied before migration 007.';
    END IF;
    IF EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 6) THEN
        RAISE EXCEPTION 'Migration 007 is already recorded as applied.';
    END IF;
END
$guard$;

-- ============================================================================
-- 1. PAYMENT REQUESTS & UPI QR (FR-PAY-003)
-- ============================================================================
CREATE TABLE vrms_private.payment_requests (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL REFERENCES vrms_private.bookings(id) ON DELETE RESTRICT,
    customer_id uuid NOT NULL REFERENCES vrms_private.customers(id) ON DELETE RESTRICT,
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    currency text NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    payment_type text NOT NULL CHECK (payment_type IN ('ADVANCE', 'RENTAL_BALANCE', 'DAMAGE_SETTLEMENT', 'SECURITY_DEPOSIT')),
    due_at timestamptz NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PARTIALLY_PAID', 'PAID', 'EXPIRED', 'CANCELLED')),
    merchant_vpa text NOT NULL,
    merchant_name text NOT NULL,
    qr_payload text NOT NULL,
    notes text,
    created_by uuid NOT NULL REFERENCES auth.users(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_requests_booking ON vrms_private.payment_requests(company_id, booking_id);

-- ============================================================================
-- 2. PAYMENTS & ALLOCATIONS (FR-PAY-001, FR-PAY-004, FR-FIN-001)
-- ============================================================================
CREATE TABLE vrms_private.payments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL REFERENCES vrms_private.bookings(id) ON DELETE RESTRICT,
    customer_id uuid NOT NULL REFERENCES vrms_private.customers(id) ON DELETE RESTRICT,
    payment_request_id uuid REFERENCES vrms_private.payment_requests(id) ON DELETE SET NULL,
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    currency text NOT NULL DEFAULT 'INR' CHECK (currency = 'INR'),
    payment_method text NOT NULL CHECK (payment_method IN ('CASH', 'UPI', 'BANK_TRANSFER', 'CARD_POS')),
    external_reference text,
    status text NOT NULL DEFAULT 'VERIFIED' CHECK (status IN ('VERIFIED', 'PENDING_RECONCILIATION', 'REJECTED')),
    verification_evidence text NOT NULL,
    verified_by uuid NOT NULL REFERENCES auth.users(id),
    verified_at timestamptz NOT NULL DEFAULT now(),
    notes text,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Unique constraint preventing duplicate posting of the same external UTR / bank reference
CREATE UNIQUE INDEX uq_payments_external_ref ON vrms_private.payments(company_id, external_reference)
    WHERE (status = 'VERIFIED' AND external_reference IS NOT NULL AND external_reference <> '');

CREATE TABLE vrms_private.payment_allocations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    payment_id uuid NOT NULL REFERENCES vrms_private.payments(id) ON DELETE CASCADE,
    booking_id uuid NOT NULL REFERENCES vrms_private.bookings(id) ON DELETE RESTRICT,
    allocation_type text NOT NULL CHECK (allocation_type IN ('ADVANCE', 'RENTAL_CHARGE', 'SECURITY_DEPOSIT', 'DAMAGE_RECOVERY')),
    allocated_paise bigint NOT NULL CHECK (allocated_paise > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_payment_alloc_booking ON vrms_private.payment_allocations(company_id, booking_id);

-- ============================================================================
-- 3. REFUNDABLE SECURITY DEPOSITS (FR-FIN-001, FR-PAY-001, FR-PAY-002)
-- ============================================================================
CREATE TABLE vrms_private.refundable_deposits (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL REFERENCES vrms_private.bookings(id) ON DELETE RESTRICT,
    customer_id uuid NOT NULL REFERENCES vrms_private.customers(id) ON DELETE RESTRICT,
    deposit_received_paise bigint NOT NULL DEFAULT 0 CHECK (deposit_received_paise >= 0),
    deposit_refunded_paise bigint NOT NULL DEFAULT 0 CHECK (deposit_refunded_paise >= 0),
    deposit_applied_paise bigint NOT NULL DEFAULT 0 CHECK (deposit_applied_paise >= 0),
    status text NOT NULL DEFAULT 'HELD' CHECK (status IN ('HELD', 'PARTIALLY_REFUNDED', 'FULLY_REFUNDED', 'FORFEITED_APPLIED')),
    held_at timestamptz NOT NULL DEFAULT now(),
    refunded_at timestamptz,
    refund_ref text,
    refund_approved_by uuid REFERENCES auth.users(id),
    notes text,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT chk_deposit_math CHECK (deposit_refunded_paise + deposit_applied_paise <= deposit_received_paise)
);

CREATE INDEX idx_deposits_booking ON vrms_private.refundable_deposits(company_id, booking_id);

-- ============================================================================
-- 4. FINANCIAL CORRECTIONS: REFUNDS & CREDIT NOTES (FR-PAY-002, FR-FIN-004)
-- ============================================================================
CREATE TABLE vrms_private.financial_corrections (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL REFERENCES vrms_private.bookings(id) ON DELETE RESTRICT,
    payment_id uuid REFERENCES vrms_private.payments(id) ON DELETE RESTRICT,
    correction_type text NOT NULL CHECK (correction_type IN ('CREDIT_NOTE', 'REFUND', 'REVERSAL', 'DISCOUNT_ADJUSTMENT')),
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    reason text NOT NULL,
    approved_by uuid NOT NULL REFERENCES auth.users(id),
    bank_payout_ref text,
    status text NOT NULL DEFAULT 'PROCESSED' CHECK (status IN ('APPROVED', 'PROCESSED', 'REJECTED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

-- ============================================================================
-- 5. BILLING DOCUMENTS & IMMUTABLE SNAPSHOTS (FR-BOOK-009, FR-PDF-001, FR-PDF-002)
-- ============================================================================
CREATE TABLE vrms_private.billing_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL REFERENCES vrms_private.bookings(id) ON DELETE RESTRICT,
    customer_id uuid NOT NULL REFERENCES vrms_private.customers(id) ON DELETE RESTRICT,
    document_type text NOT NULL CHECK (document_type IN ('QUOTATION', 'PRO_FORMA_INVOICE', 'TAX_INVOICE_BETA', 'PAYMENT_RECEIPT', 'CREDIT_NOTE', 'ACCOUNT_STATEMENT')),
    document_number text NOT NULL,
    document_version integer NOT NULL DEFAULT 1 CHECK (document_version > 0),
    status text NOT NULL DEFAULT 'ISSUED' CHECK (status IN ('DRAFT', 'ISSUED', 'SUPERSEDED', 'CANCELLED')),
    total_paise bigint NOT NULL CHECK (total_paise >= 0),
    advance_paid_paise bigint NOT NULL DEFAULT 0 CHECK (advance_paid_paise >= 0),
    balance_due_paise bigint NOT NULL DEFAULT 0,
    deposit_held_paise bigint NOT NULL DEFAULT 0 CHECK (deposit_held_paise >= 0),
    tax_paise bigint NOT NULL DEFAULT 0,
    is_beta_proforma boolean NOT NULL DEFAULT true,
    snapshot_json text NOT NULL,
    file_metadata_id uuid REFERENCES vrms_private.file_metadata(id),
    issued_by uuid NOT NULL REFERENCES auth.users(id),
    issued_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_billing_doc_number UNIQUE (company_id, document_number)
);

CREATE TABLE vrms_private.billing_items (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    billing_document_id uuid NOT NULL REFERENCES vrms_private.billing_documents(id) ON DELETE CASCADE,
    assignment_id uuid REFERENCES vrms_private.booking_assignments(id) ON DELETE SET NULL,
    item_type text NOT NULL CHECK (item_type IN ('RENTAL_CHARGE', 'DRIVER_FEE', 'EXTENSION_CHARGE', 'DAMAGE_RECOVERY', 'DISCOUNT')),
    description text NOT NULL,
    quantity integer NOT NULL DEFAULT 1 CHECK (quantity > 0),
    unit_rate_paise bigint NOT NULL,
    total_paise bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- ============================================================================
-- 6. IMMUTABLE FINANCIAL LEDGER ENTRIES (FR-FIN-001, FR-FIN-002, FR-FIN-004)
-- ============================================================================
CREATE TABLE vrms_private.ledger_entries (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    booking_id uuid REFERENCES vrms_private.bookings(id) ON DELETE SET NULL,
    entry_type text NOT NULL CHECK (entry_type IN ('REVENUE_EARNED', 'CASH_RECEIVED', 'RECEIVABLE_DEBT', 'DEPOSIT_LIABILITY', 'EXPENSE_INCIDENT', 'REFUND_PAID')),
    direction text NOT NULL CHECK (direction IN ('DEBIT', 'CREDIT')),
    amount_paise bigint NOT NULL CHECK (amount_paise > 0),
    source_ref_type text NOT NULL,
    source_ref_id uuid NOT NULL,
    description text NOT NULL,
    posted_by uuid NOT NULL REFERENCES auth.users(id),
    posted_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_ledger_company_time ON vrms_private.ledger_entries(company_id, posted_at);

-- ============================================================================
-- 7. ROW LEVEL SECURITY (RLS) & GRANTS
-- ============================================================================
ALTER TABLE vrms_private.payment_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.payment_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.payments ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.payments FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.payment_allocations ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.payment_allocations FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.refundable_deposits ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.refundable_deposits FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.financial_corrections ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.financial_corrections FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.billing_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.billing_documents FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.billing_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.billing_items FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.ledger_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.ledger_entries FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_payment_requests ON vrms_private.payment_requests
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY tenant_isolation_payments ON vrms_private.payments
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY tenant_isolation_payment_allocations ON vrms_private.payment_allocations
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY tenant_isolation_refundable_deposits ON vrms_private.refundable_deposits
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY tenant_isolation_financial_corrections ON vrms_private.financial_corrections
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY tenant_isolation_billing_documents ON vrms_private.billing_documents
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY tenant_isolation_billing_items ON vrms_private.billing_items
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY tenant_isolation_ledger_entries ON vrms_private.ledger_entries
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON TABLE
    vrms_private.payment_requests,
    vrms_private.payments,
    vrms_private.payment_allocations,
    vrms_private.refundable_deposits,
    vrms_private.financial_corrections,
    vrms_private.billing_documents,
    vrms_private.billing_items,
    vrms_private.ledger_entries
TO smart_vrms_api;

-- Record migration version
INSERT INTO vrms_private.schema_migrations (version, description)
VALUES (6, 'finance, payments, ledger, deposits, and billing documents');

COMMIT;

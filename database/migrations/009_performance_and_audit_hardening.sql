-- Smart VRMS / Stage 8 / migration 009.
-- Run once in the isolated development project as postgres.
-- Transactional and non-destructive: query performance indexes and bounded statement/lock waits.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $guard$
BEGIN
    IF current_user <> 'postgres' THEN
        RAISE EXCEPTION 'Run this migration as postgres in the development project.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 7) THEN
        RAISE EXCEPTION 'Migration 008 must be applied before migration 009.';
    END IF;
    IF EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 8) THEN
        RAISE EXCEPTION 'Migration 009 is already recorded as applied.';
    END IF;
END
$guard$;

-- ============================================================================
-- 1. JUSTIFIED PERFORMANCE INDEXES (NFR-PERF-001, FR-DATA-003)
-- ============================================================================

-- Accelerates financial reporting queries grouping by entry_type within a date range (FR-FIN-003, FR-FIN-005)
CREATE INDEX IF NOT EXISTS idx_ledger_company_posted
    ON vrms_private.ledger_entries(company_id, posted_at, entry_type);

-- Accelerates operational reporting for active and completed rentals within reporting periods (FR-FIN-003)
CREATE INDEX IF NOT EXISTS idx_custody_company_checkout
    ON vrms_private.custody_records(company_id, checkout_time);

-- Accelerates maintenance incident metrics in periodic reporting (FR-DMG-001)
CREATE INDEX IF NOT EXISTS idx_maintenance_company_created
    ON vrms_private.maintenance_cases(company_id, created_at);

-- Accelerates customer satisfaction review counting and average rating computation (FR-REV-001)
CREATE INDEX IF NOT EXISTS idx_reviews_company_recorded
    ON vrms_private.service_reviews(company_id, recorded_at, rating);

-- Accelerates booking listing with bounded pagination and period filtering (FR-BOOK-001, NFR-PERF-001)
CREATE INDEX IF NOT EXISTS idx_bookings_company_created
    ON vrms_private.bookings(company_id, created_at, status);

-- Accelerates private storage file auditing and access tier verification (FR-DATA-002)
CREATE INDEX IF NOT EXISTS idx_file_metadata_company_tier
    ON vrms_private.file_metadata(company_id, created_at, access_tier);

-- Record migration version
INSERT INTO vrms_private.schema_migrations (version, description)
VALUES (8, 'performance indexes and bounded migration waits');

COMMIT;

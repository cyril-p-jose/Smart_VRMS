-- Smart VRMS / Stage 5 / migration 006.
-- Run once in the isolated development project as postgres.
-- Transactional and non-destructive: operations, custody, extensions, and maintenance.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $guard$
BEGIN
    IF current_user <> 'postgres' THEN
        RAISE EXCEPTION 'Run this migration as postgres in the development project.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 4) THEN
        RAISE EXCEPTION 'Migration 005 must be applied before migration 006.';
    END IF;
    IF EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 5) THEN
        RAISE EXCEPTION 'Migration 006 is already recorded as applied.';
    END IF;
END
$guard$;

-- ============================================================================
-- 1. CUSTODY & PHYSICAL INSPECTION (FR-OPS-001, FR-OPS-002, FR-OPS-003)
-- ============================================================================
CREATE TABLE vrms_private.custody_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    assignment_id uuid NOT NULL REFERENCES vrms_private.booking_assignments(id) ON DELETE RESTRICT,
    vehicle_id uuid NOT NULL REFERENCES vrms_private.vehicles(id) ON DELETE RESTRICT,
    checkout_time timestamptz NOT NULL,
    checkout_odometer integer NOT NULL CHECK (checkout_odometer >= 0),
    checkout_fuel_percent integer NOT NULL CHECK (checkout_fuel_percent BETWEEN 0 AND 100),
    checkout_notes text,
    checkout_by uuid NOT NULL REFERENCES auth.users(id),
    return_time timestamptz CHECK (return_time IS NULL OR return_time >= checkout_time),
    return_odometer integer CHECK (return_odometer IS NULL OR return_odometer >= checkout_odometer),
    return_fuel_percent integer CHECK (return_fuel_percent IS NULL OR return_fuel_percent BETWEEN 0 AND 100),
    inspection_status text NOT NULL DEFAULT 'PENDING' CHECK (inspection_status IN ('PENDING', 'ACCEPTABLE', 'DAMAGED')),
    damage_notes text,
    damage_photo_file_id uuid REFERENCES vrms_private.file_metadata(id),
    returned_by uuid REFERENCES auth.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    UNIQUE (company_id, assignment_id)
);

CREATE INDEX custody_company_assign ON vrms_private.custody_records(company_id, assignment_id);
CREATE INDEX custody_vehicle ON vrms_private.custody_records(company_id, vehicle_id);
CREATE INDEX custody_status ON vrms_private.custody_records(company_id, inspection_status);

ALTER TABLE vrms_private.custody_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.custody_records FORCE ROW LEVEL SECURITY;

CREATE POLICY custody_tenant_isolation ON vrms_private.custody_records
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON vrms_private.custody_records TO smart_vrms_api;

-- ============================================================================
-- 2. EXTENSION RECORDS (FR-EXT-001, FR-EXT-002, FR-EXT-003)
-- ============================================================================
CREATE TABLE vrms_private.extension_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL REFERENCES vrms_private.bookings(id) ON DELETE RESTRICT,
    assignment_id uuid NOT NULL REFERENCES vrms_private.booking_assignments(id) ON DELETE RESTRICT,
    previous_end_time timestamptz NOT NULL,
    revised_end_time timestamptz NOT NULL CHECK (revised_end_time > previous_end_time),
    incremental_charge_paise bigint NOT NULL CHECK (incremental_charge_paise >= 0),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 255),
    extended_by uuid NOT NULL REFERENCES auth.users(id),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX extension_company_booking ON vrms_private.extension_records(company_id, booking_id);
CREATE INDEX extension_assignment ON vrms_private.extension_records(company_id, assignment_id);

ALTER TABLE vrms_private.extension_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.extension_records FORCE ROW LEVEL SECURITY;

CREATE POLICY extension_tenant_isolation ON vrms_private.extension_records
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON vrms_private.extension_records TO smart_vrms_api;

-- ============================================================================
-- 3. MAINTENANCE & DAMAGE CASES (FR-DMG-001, FR-DMG-002, FR-DMG-003, FR-DMG-004)
-- ============================================================================
CREATE TABLE vrms_private.maintenance_cases (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    vehicle_id uuid NOT NULL REFERENCES vrms_private.vehicles(id) ON DELETE RESTRICT,
    assignment_id uuid REFERENCES vrms_private.booking_assignments(id) ON DELETE RESTRICT,
    custody_id uuid REFERENCES vrms_private.custody_records(id) ON DELETE RESTRICT,
    restriction_id uuid REFERENCES vrms_private.asset_restrictions(id) ON DELETE SET NULL,
    issue_type text NOT NULL CHECK (issue_type IN ('CUSTOMER_DAMAGE', 'ORDINARY_WEAR', 'ROUTINE_SERVICE', 'BREAKDOWN')),
    status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'ASSESSED', 'REPAIR_IN_PROGRESS', 'RESOLVED')),
    estimated_cost_paise bigint NOT NULL DEFAULT 0 CHECK (estimated_cost_paise >= 0),
    alleged_customer_recovery_paise bigint NOT NULL DEFAULT 0 CHECK (alleged_customer_recovery_paise >= 0),
    actual_cost_paise bigint NOT NULL DEFAULT 0 CHECK (actual_cost_paise >= 0),
    approved_customer_recovery_paise bigint NOT NULL DEFAULT 0 CHECK (approved_customer_recovery_paise >= 0),
    notes text NOT NULL CHECK (length(btrim(notes)) BETWEEN 1 AND 1000),
    damage_photo_file_id uuid REFERENCES vrms_private.file_metadata(id),
    resolution_notes text,
    reported_by uuid NOT NULL REFERENCES auth.users(id),
    resolved_by uuid REFERENCES auth.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    resolved_at timestamptz,
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0)
);

CREATE INDEX maintenance_company_veh ON vrms_private.maintenance_cases(company_id, vehicle_id, status);
CREATE INDEX maintenance_assign ON vrms_private.maintenance_cases(company_id, assignment_id);

ALTER TABLE vrms_private.maintenance_cases ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.maintenance_cases FORCE ROW LEVEL SECURITY;

CREATE POLICY maintenance_tenant_isolation ON vrms_private.maintenance_cases
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON vrms_private.maintenance_cases TO smart_vrms_api;

-- Record migration 006
INSERT INTO vrms_private.schema_migrations (version, description)
VALUES (5, 'operations, custody, extensions, and maintenance');

COMMIT;

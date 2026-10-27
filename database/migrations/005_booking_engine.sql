-- Smart VRMS / Stage 4 / migration 005.
-- Run once in the isolated development project as postgres.
-- Transactional and non-destructive: booking engine, packages, reservations, and rates.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $guard$
BEGIN
    IF current_user <> 'postgres' THEN
        RAISE EXCEPTION 'Run this migration as postgres in the development project.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 3) THEN
        RAISE EXCEPTION 'Migration 004 must be applied before migration 005.';
    END IF;
    IF EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 4) THEN
        RAISE EXCEPTION 'Migration 005 is already recorded as applied.';
    END IF;
END
$guard$;

-- ============================================================================
-- 1. RATE PLANS & MASTER PRICING (FR-BOOK-007, FR-DATA-001)
-- ============================================================================
CREATE TABLE vrms_private.rate_plans (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    vehicle_id uuid REFERENCES vrms_private.vehicles(id) ON DELETE RESTRICT,
    category_id uuid REFERENCES vrms_private.vehicle_categories(id) ON DELETE RESTRICT,
    daily_rate_paise bigint NOT NULL CHECK (daily_rate_paise > 0),
    hourly_rate_paise bigint NOT NULL CHECK (hourly_rate_paise > 0),
    driver_daily_fee_paise bigint NOT NULL DEFAULT 0 CHECK (driver_daily_fee_paise >= 0),
    driver_hourly_fee_paise bigint NOT NULL DEFAULT 0 CHECK (driver_hourly_fee_paise >= 0),
    effective_from timestamptz NOT NULL DEFAULT now(),
    effective_to timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    CHECK (vehicle_id IS NOT NULL OR category_id IS NOT NULL)
);

CREATE INDEX rate_plans_lookup ON vrms_private.rate_plans(company_id, category_id, vehicle_id);

ALTER TABLE vrms_private.rate_plans ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.rate_plans FORCE ROW LEVEL SECURITY;

CREATE POLICY rate_plans_tenant_isolation ON vrms_private.rate_plans
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON vrms_private.rate_plans TO smart_vrms_api;

-- ============================================================================
-- 2. BOOKINGS (FR-BOOK-001, FR-BOOK-005, FR-BOOK-007, FR-BOOK-008, FR-BOOK-010)
-- ============================================================================
CREATE TABLE vrms_private.bookings (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    customer_id uuid NOT NULL REFERENCES vrms_private.customers(id) ON DELETE RESTRICT,
    visit_id uuid NOT NULL REFERENCES vrms_private.customer_visits(id) ON DELETE RESTRICT,
    booking_number text NOT NULL,
    status text NOT NULL CHECK (status IN ('CONFIRMED', 'ACTIVE', 'COMPLETED', 'CANCELLED')),
    start_time timestamptz NOT NULL,
    end_time timestamptz NOT NULL,
    base_total_paise bigint NOT NULL CHECK (base_total_paise >= 0),
    driver_total_paise bigint NOT NULL DEFAULT 0 CHECK (driver_total_paise >= 0),
    tax_paise bigint NOT NULL DEFAULT 0 CHECK (tax_paise >= 0),
    total_paise bigint NOT NULL CHECK (total_paise >= 0),
    agreed_advance_paise bigint NOT NULL DEFAULT 0 CHECK (agreed_advance_paise >= 0),
    actual_advance_received_paise bigint NOT NULL DEFAULT 0 CHECK (actual_advance_received_paise >= 0),
    deposit_paise bigint NOT NULL DEFAULT 0 CHECK (deposit_paise >= 0),
    pricing_snapshot jsonb NOT NULL,
    created_by uuid NOT NULL REFERENCES auth.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    UNIQUE (company_id, booking_number),
    CHECK (end_time > start_time)
);

CREATE INDEX bookings_company_time ON vrms_private.bookings(company_id, start_time, end_time);
CREATE INDEX bookings_customer ON vrms_private.bookings(company_id, customer_id);
CREATE INDEX bookings_status ON vrms_private.bookings(company_id, status);

ALTER TABLE vrms_private.bookings ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.bookings FORCE ROW LEVEL SECURITY;

CREATE POLICY bookings_tenant_isolation ON vrms_private.bookings
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON vrms_private.bookings TO smart_vrms_api;

-- ============================================================================
-- 3. BOOKING ASSIGNMENTS (FR-BOOK-002, FR-BOOK-003, FR-BOOK-007)
-- ============================================================================
CREATE TABLE vrms_private.booking_assignments (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL REFERENCES vrms_private.bookings(id) ON DELETE CASCADE,
    vehicle_id uuid NOT NULL REFERENCES vrms_private.vehicles(id) ON DELETE RESTRICT,
    driver_id uuid REFERENCES vrms_private.driver_profiles(id) ON DELETE RESTRICT,
    driver_required boolean NOT NULL DEFAULT false,
    start_time timestamptz NOT NULL,
    end_time timestamptz NOT NULL,
    daily_rate_paise bigint NOT NULL CHECK (daily_rate_paise > 0),
    hourly_rate_paise bigint NOT NULL CHECK (hourly_rate_paise > 0),
    vehicle_total_paise bigint NOT NULL CHECK (vehicle_total_paise >= 0),
    driver_total_paise bigint NOT NULL DEFAULT 0 CHECK (driver_total_paise >= 0),
    assignment_total_paise bigint NOT NULL CHECK (assignment_total_paise >= 0),
    status text NOT NULL CHECK (status IN ('RESERVED', 'CHECKED_OUT', 'RETURNED', 'CANCELLED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (end_time > start_time),
    UNIQUE (booking_id, vehicle_id)
);

CREATE INDEX assignments_booking ON vrms_private.booking_assignments(company_id, booking_id);
CREATE INDEX assignments_vehicle ON vrms_private.booking_assignments(company_id, vehicle_id);
CREATE INDEX assignments_driver ON vrms_private.booking_assignments(company_id, driver_id);

ALTER TABLE vrms_private.booking_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.booking_assignments FORCE ROW LEVEL SECURITY;

CREATE POLICY assignments_tenant_isolation ON vrms_private.booking_assignments
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON vrms_private.booking_assignments TO smart_vrms_api;

-- ============================================================================
-- 4. CONCURRENT RESOURCE RESERVATIONS (FR-BOOK-010, FR-VEH-005, FR-DRV-002)
-- ============================================================================
CREATE TABLE vrms_private.vehicle_reservations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    vehicle_id uuid NOT NULL REFERENCES vrms_private.vehicles(id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL REFERENCES vrms_private.bookings(id) ON DELETE CASCADE,
    assignment_id uuid NOT NULL REFERENCES vrms_private.booking_assignments(id) ON DELETE CASCADE,
    start_time timestamptz NOT NULL,
    end_time timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (end_time > start_time)
);

CREATE INDEX vehicle_res_lookup ON vrms_private.vehicle_reservations(company_id, vehicle_id, start_time, end_time) WHERE status = 'ACTIVE';

ALTER TABLE vrms_private.vehicle_reservations ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.vehicle_reservations FORCE ROW LEVEL SECURITY;

CREATE POLICY vehicle_res_tenant_isolation ON vrms_private.vehicle_reservations
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON vrms_private.vehicle_reservations TO smart_vrms_api;

CREATE TABLE vrms_private.driver_reservations (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    driver_id uuid NOT NULL REFERENCES vrms_private.driver_profiles(id) ON DELETE RESTRICT,
    booking_id uuid NOT NULL REFERENCES vrms_private.bookings(id) ON DELETE CASCADE,
    assignment_id uuid NOT NULL REFERENCES vrms_private.booking_assignments(id) ON DELETE CASCADE,
    start_time timestamptz NOT NULL,
    end_time timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE', 'RELEASED')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (end_time > start_time)
);

CREATE INDEX driver_res_lookup ON vrms_private.driver_reservations(company_id, driver_id, start_time, end_time) WHERE status = 'ACTIVE';

ALTER TABLE vrms_private.driver_reservations ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.driver_reservations FORCE ROW LEVEL SECURITY;

CREATE POLICY driver_res_tenant_isolation ON vrms_private.driver_reservations
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

GRANT SELECT, INSERT, UPDATE ON vrms_private.driver_reservations TO smart_vrms_api;

-- Record migration 005
INSERT INTO vrms_private.schema_migrations (version, description)
VALUES (4, 'booking engine, packages, reservations, and rates');

COMMIT;

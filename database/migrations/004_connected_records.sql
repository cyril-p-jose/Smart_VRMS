-- Smart VRMS / Stage 3 / migration 004.
-- Run once in the isolated development project as postgres.
-- Transactional and non-destructive: connected personnel, fleet, driver, and customer records.
BEGIN;
SET LOCAL lock_timeout = '5s';
SET LOCAL statement_timeout = '30s';

DO $guard$
BEGIN
    IF current_user <> 'postgres' THEN
        RAISE EXCEPTION 'Run this migration as postgres in the development project.';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 2) THEN
        RAISE EXCEPTION 'Migration 003 must be applied before migration 004.';
    END IF;
    IF EXISTS (SELECT 1 FROM vrms_private.schema_migrations WHERE version = 3) THEN
        RAISE EXCEPTION 'Migration 004 is already recorded as applied.';
    END IF;
END
$guard$;

-- ============================================================================
-- 1. PERSONNEL & ATTENDANCE (FR-EMP-001, FR-EMP-002, FR-EMP-003, FR-EMP-004)
-- ============================================================================
CREATE TABLE vrms_private.employees (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    auth_user_id uuid REFERENCES auth.users(id) ON DELETE SET NULL,
    full_name text NOT NULL CHECK (length(btrim(full_name)) BETWEEN 1 AND 120),
    contact_number text NOT NULL CHECK (length(btrim(contact_number)) BETWEEN 7 AND 20),
    email text CHECK (email IS NULL OR email ~* '^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$'),
    address text NOT NULL CHECK (length(btrim(address)) BETWEEN 1 AND 255),
    joining_date date NOT NULL,
    employment_status text NOT NULL CHECK (employment_status IN ('ACTIVE', 'ON_LEAVE', 'DEACTIVATED')),
    is_driver boolean NOT NULL DEFAULT false,
    photo_file_id uuid REFERENCES vrms_private.file_metadata(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0)
);

CREATE INDEX employees_company_status ON vrms_private.employees(company_id, employment_status);
CREATE INDEX employees_company_contact ON vrms_private.employees(company_id, contact_number);

CREATE TABLE vrms_private.attendance_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL REFERENCES vrms_private.employees(id) ON DELETE RESTRICT,
    record_date date NOT NULL,
    status text NOT NULL CHECK (status IN ('PRESENT', 'ABSENT', 'PARTIAL_DAY', 'ON_LEAVE')),
    notes text,
    recorded_by uuid NOT NULL REFERENCES auth.users(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(company_id, employee_id, record_date)
);

CREATE INDEX attendance_emp_date ON vrms_private.attendance_records(company_id, employee_id, record_date);

-- Administrator-only compensation tracking (FR-EMP-006, FR-EMP-007, FR-ACC-003)
CREATE TABLE vrms_private.compensation_records (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL REFERENCES vrms_private.employees(id) ON DELETE RESTRICT,
    arrangement_type text NOT NULL CHECK (arrangement_type IN ('FIXED_MONTHLY', 'DAILY_WAGE', 'PER_TRIP', 'MIXED')),
    base_rate_paise bigint NOT NULL CHECK (base_rate_paise >= 0),
    trip_allowance_paise bigint NOT NULL DEFAULT 0 CHECK (trip_allowance_paise >= 0),
    effective_from date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0)
);

CREATE INDEX compensation_emp ON vrms_private.compensation_records(company_id, employee_id);

-- ============================================================================
-- 2. DRIVERS (FR-DRV-001, FR-DRV-003, FR-DRV-004)
-- ============================================================================
CREATE TABLE vrms_private.driver_profiles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    employee_id uuid NOT NULL UNIQUE REFERENCES vrms_private.employees(id) ON DELETE RESTRICT,
    licence_number text NOT NULL CHECK (length(btrim(licence_number)) BETWEEN 3 AND 50),
    licence_classes text[] NOT NULL CHECK (cardinality(licence_classes) > 0),
    licence_expiry date NOT NULL,
    experience_years integer NOT NULL CHECK (experience_years >= 0),
    duty_status text NOT NULL CHECK (duty_status IN ('AVAILABLE', 'ASSIGNED', 'ON_LEAVE', 'UNAVAILABLE')),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0)
);

CREATE INDEX driver_profiles_company ON vrms_private.driver_profiles(company_id, duty_status);

-- ============================================================================
-- 3. FLEET & RESTRICTIONS (FR-VEH-001, FR-VEH-002, FR-VEH-003, FR-VEH-006, FR-DOC-001, FR-DOC-002)
-- ============================================================================
CREATE TABLE vrms_private.vehicle_categories (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    name text NOT NULL CHECK (length(btrim(name)) BETWEEN 1 AND 50),
    code text NOT NULL CHECK (length(btrim(code)) BETWEEN 1 AND 20),
    UNIQUE(company_id, code)
);

CREATE TABLE vrms_private.vehicles (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    category_id uuid NOT NULL REFERENCES vrms_private.vehicle_categories(id) ON DELETE RESTRICT,
    registration_plate text NOT NULL CHECK (length(btrim(registration_plate)) BETWEEN 4 AND 20),
    manufacturer text NOT NULL CHECK (length(btrim(manufacturer)) BETWEEN 1 AND 80),
    model text NOT NULL CHECK (length(btrim(model)) BETWEEN 1 AND 80),
    seating_capacity integer NOT NULL CHECK (seating_capacity > 0),
    daily_rate_paise bigint NOT NULL CHECK (daily_rate_paise > 0),
    hourly_rate_paise bigint NOT NULL DEFAULT 0 CHECK (hourly_rate_paise >= 0),
    operational_status text NOT NULL CHECK (operational_status IN ('AVAILABLE', 'RENTED', 'MAINTENANCE', 'FROZEN')),
    photo_file_id uuid REFERENCES vrms_private.file_metadata(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    UNIQUE(company_id, registration_plate)
);

CREATE INDEX vehicles_company_cat ON vrms_private.vehicles(company_id, category_id, operational_status);

CREATE TABLE vrms_private.vehicle_documents (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    vehicle_id uuid NOT NULL REFERENCES vrms_private.vehicles(id) ON DELETE RESTRICT,
    doc_type text NOT NULL CHECK (doc_type IN ('RC', 'INSURANCE', 'PUC', 'PERMIT', 'FITNESS', 'TAX')),
    file_id uuid NOT NULL REFERENCES vrms_private.file_metadata(id),
    issue_date date NOT NULL,
    expiry_date date,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX vehicle_docs_veh ON vrms_private.vehicle_documents(company_id, vehicle_id, doc_type);

CREATE TABLE vrms_private.asset_restrictions (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    asset_type text NOT NULL CHECK (asset_type IN ('VEHICLE', 'DRIVER')),
    asset_id uuid NOT NULL,
    restriction_type text NOT NULL CHECK (restriction_type IN ('MAINTENANCE', 'FREEZE', 'LEAVE', 'DISCIPLINARY')),
    start_time timestamptz NOT NULL,
    end_time timestamptz,
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 255),
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX asset_restrictions_lookup ON vrms_private.asset_restrictions(company_id, asset_type, asset_id, active);

-- ============================================================================
-- 4. CUSTOMERS & VISITS (FR-CUST-001, FR-CUST-002, FR-CUST-008)
-- ============================================================================
CREATE TABLE vrms_private.customers (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    full_name text NOT NULL CHECK (length(btrim(full_name)) BETWEEN 1 AND 120),
    mobile_number text NOT NULL CHECK (length(btrim(mobile_number)) BETWEEN 7 AND 20),
    email text,
    address text NOT NULL CHECK (length(btrim(address)) BETWEEN 1 AND 255),
    created_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0)
);

CREATE INDEX customers_company_mobile ON vrms_private.customers(company_id, mobile_number);

CREATE TABLE vrms_private.customer_visits (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id uuid NOT NULL REFERENCES vrms_private.companies(id) ON DELETE RESTRICT,
    customer_id uuid NOT NULL REFERENCES vrms_private.customers(id) ON DELETE RESTRICT,
    age_at_visit integer NOT NULL CHECK (age_at_visit >= 18 AND age_at_visit <= 120),
    visit_date timestamptz NOT NULL DEFAULT now(),
    purpose text,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX customer_visits_cust ON vrms_private.customer_visits(company_id, customer_id, visit_date DESC);

-- ============================================================================
-- 5. ROW LEVEL SECURITY POLICIES & GRANTS
-- ============================================================================
ALTER TABLE vrms_private.employees ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.employees FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.attendance_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.attendance_records FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.compensation_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.compensation_records FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.driver_profiles ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.driver_profiles FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.vehicle_categories ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.vehicle_categories FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.vehicles ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.vehicles FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.vehicle_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.vehicle_documents FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.asset_restrictions ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.asset_restrictions FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.customers ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.customers FORCE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.customer_visits ENABLE ROW LEVEL SECURITY;
ALTER TABLE vrms_private.customer_visits FORCE ROW LEVEL SECURITY;

-- Standard own-company policies for smart_vrms_api
CREATE POLICY employees_own_company ON vrms_private.employees
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY attendance_own_company ON vrms_private.attendance_records
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

-- STRICT: Compensation is ADMINISTRATOR ONLY (FR-ACC-003, FR-EMP-006, FR-EMP-007)
CREATE POLICY compensation_admin_only ON vrms_private.compensation_records
    FOR ALL TO smart_vrms_api
    USING (
        company_id = nullif(current_setting('vrms.company_id', true), '')::uuid
        AND current_setting('vrms.role', true) = 'ADMINISTRATOR'
    )
    WITH CHECK (
        company_id = nullif(current_setting('vrms.company_id', true), '')::uuid
        AND current_setting('vrms.role', true) = 'ADMINISTRATOR'
    );

CREATE POLICY drivers_own_company ON vrms_private.driver_profiles
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY vehicle_cats_own_company ON vrms_private.vehicle_categories
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY vehicles_own_company ON vrms_private.vehicles
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY vehicle_docs_own_company ON vrms_private.vehicle_documents
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY asset_restrictions_own_company ON vrms_private.asset_restrictions
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY customers_own_company ON vrms_private.customers
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

CREATE POLICY customer_visits_own_company ON vrms_private.customer_visits
    FOR ALL TO smart_vrms_api
    USING (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid)
    WITH CHECK (company_id = nullif(current_setting('vrms.company_id', true), '')::uuid);

-- Grants
GRANT SELECT, INSERT, UPDATE ON vrms_private.employees TO smart_vrms_api;
GRANT SELECT, INSERT, UPDATE ON vrms_private.attendance_records TO smart_vrms_api;
GRANT SELECT, INSERT, UPDATE ON vrms_private.compensation_records TO smart_vrms_api;
GRANT SELECT, INSERT, UPDATE ON vrms_private.driver_profiles TO smart_vrms_api;
GRANT SELECT, INSERT, UPDATE ON vrms_private.vehicle_categories TO smart_vrms_api;
GRANT SELECT, INSERT, UPDATE ON vrms_private.vehicles TO smart_vrms_api;
GRANT SELECT, INSERT, UPDATE ON vrms_private.vehicle_documents TO smart_vrms_api;
GRANT SELECT, INSERT, UPDATE ON vrms_private.asset_restrictions TO smart_vrms_api;
GRANT SELECT, INSERT, UPDATE ON vrms_private.customers TO smart_vrms_api;
GRANT SELECT, INSERT, UPDATE ON vrms_private.customer_visits TO smart_vrms_api;

-- Record migration version
INSERT INTO vrms_private.schema_migrations(version, description)
VALUES (3, 'Connected records: employees, attendance, compensation, drivers, fleet, restrictions, customers and visits');

COMMIT;

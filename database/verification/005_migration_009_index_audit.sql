-- SETUP-13 follow-up: read-only verification of migration 009 and its indexes.
-- Run as postgres in the isolated development project. Reads catalog metadata only.
BEGIN READ ONLY;
SET LOCAL statement_timeout = '15s';

WITH expected(index_name) AS (
    VALUES
        ('idx_ledger_company_posted'),
        ('idx_custody_company_checkout'),
        ('idx_maintenance_company_created'),
        ('idx_reviews_company_recorded'),
        ('idx_bookings_company_created'),
        ('idx_file_metadata_company_tier')
)
SELECT jsonb_build_object(
    'migration_009_recorded', EXISTS (
        SELECT 1 FROM vrms_private.schema_migrations
        WHERE version = 8 AND description = 'performance indexes and bounded migration waits'
    ),
    'missing_index_count', (
        SELECT count(*)
        FROM expected e
        LEFT JOIN pg_indexes i
          ON i.schemaname = 'vrms_private' AND i.indexname = e.index_name
        WHERE i.indexname IS NULL
    ),
    'indexes', (
        SELECT jsonb_agg(jsonb_build_object(
            'name', e.index_name,
            'present', i.indexname IS NOT NULL,
            'definition', i.indexdef
        ) ORDER BY e.index_name)
        FROM expected e
        LEFT JOIN pg_indexes i
          ON i.schemaname = 'vrms_private' AND i.indexname = e.index_name
    )
) AS migration_009_verification;

COMMIT;

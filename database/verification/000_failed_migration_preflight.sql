-- Read-only recovery check. First execute ROLLBACK; as a separate editor run.
-- Both flags must be false before retrying corrected migration 001.
SELECT
    EXISTS (SELECT 1 FROM pg_namespace WHERE nspname = 'vrms_private') AS vrms_schema_exists,
    EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'smart_vrms_api') AS vrms_role_exists;

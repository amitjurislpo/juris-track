-- Reverses V1__baseline.sql. Flyway Community has no undo: run with psql as the schema owner, then delete the version 1
-- row from flyway_schema_history. THIS DROPS EVERY TABLE AND ALL DATA. Take a backup first (scripts/backup.sh).

DROP TABLE IF EXISTS audit_logs;
DROP TABLE IF EXISTS break_sessions;
DROP TABLE IF EXISTS work_sessions;
DROP TABLE IF EXISTS workdays;
DROP TABLE IF EXISTS login_otps;
DROP TABLE IF EXISTS refresh_tokens;
DROP TABLE IF EXISTS sessions;
DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS businesses;

DROP FUNCTION IF EXISTS juristrack_tenant_id();
DROP TYPE IF EXISTS workday_status;
DROP TYPE IF EXISTS user_role;

ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE SELECT, INSERT, UPDATE, DELETE ON TABLES FROM juristrack_tenant;
REVOKE USAGE ON SCHEMA public FROM juristrack_tenant;
-- The role itself is left in place (roles are cluster-wide); drop it with DROP ROLE juristrack_tenant if nothing else uses it.

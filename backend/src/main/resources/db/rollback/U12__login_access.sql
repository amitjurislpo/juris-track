-- Reverses V12__login_access.sql. Flyway Community has no undo: run with psql as the schema owner, then delete the version 12 row
-- from flyway_schema_history. People whose login was disabled can sign in again afterwards.

ALTER TABLE users DROP COLUMN IF EXISTS is_login_enabled;

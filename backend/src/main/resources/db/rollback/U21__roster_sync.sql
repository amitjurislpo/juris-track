-- Reverses V21__roster_sync.sql. Flyway Community has no undo: run with psql as the schema owner, then delete the version 21 row from
-- flyway_schema_history. Shift codes and the "deactivated by the roster sync" marks are lost (everybody deactivated stays deactivated).

ALTER TABLE users DROP COLUMN IF EXISTS is_deactivated_by_roster_sync;
DROP INDEX IF EXISTS shifts_business_id_code_key;
ALTER TABLE shifts DROP CONSTRAINT IF EXISTS shifts_code_length;
ALTER TABLE shifts DROP COLUMN IF EXISTS code;

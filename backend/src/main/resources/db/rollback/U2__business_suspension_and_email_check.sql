-- Reverses V2__business_suspension_and_email_check.sql. Flyway Community has no undo: run with psql as the schema owner, then
-- delete the version 2 row from flyway_schema_history. Suspended businesses become active again (the flag is dropped).

DROP FUNCTION IF EXISTS juristrack_is_email_in_use(TEXT, UUID);
ALTER TABLE businesses DROP COLUMN IF EXISTS is_suspended;

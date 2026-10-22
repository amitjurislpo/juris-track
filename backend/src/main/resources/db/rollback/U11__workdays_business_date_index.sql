-- Reverses V11__workdays_business_date_index.sql. Flyway Community has no undo: run with psql as the schema owner, then delete the
-- version 11 row from flyway_schema_history. Only an index is dropped; no data changes.

DROP INDEX IF EXISTS workdays_business_id_work_date_idx;

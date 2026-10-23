-- Reverses V20__shifts.sql. Flyway Community has no undo: run with psql as the schema owner, then delete the version 20 row from
-- flyway_schema_history. The shift snapshot columns of workdays and both shift tables are dropped (work_date keeps its values: for
-- workdays attributed to a night shift it is the shift date, not the date the work started).

DROP TRIGGER IF EXISTS businesses_default_shift ON businesses;
DROP FUNCTION IF EXISTS juristrack_create_default_shift();
ALTER TABLE workdays
    DROP COLUMN IF EXISTS extra_seconds,
    DROP COLUMN IF EXISTS is_off_day,
    DROP COLUMN IF EXISTS is_outside_shift,
    DROP COLUMN IF EXISTS scheduled_end_at,
    DROP COLUMN IF EXISTS scheduled_start_at,
    DROP COLUMN IF EXISTS shift_name,
    DROP COLUMN IF EXISTS shift_id;
DROP TABLE IF EXISTS employee_schedule_entries;
DROP TABLE IF EXISTS employee_shift_assignments;
DROP TABLE IF EXISTS shifts;

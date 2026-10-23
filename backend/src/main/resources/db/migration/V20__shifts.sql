-- V20: shift-based calculation (owner decision 2026-10-24, reference: Time Doctor). Time is attributed per SHIFT, not per calendar day:
-- a US shift 19:30-04:30 or a UK shift 14:00-23:00 is one workday of nine hours whichever calendar dates it touches.
--
-- * shifts: the business's shift templates (a start and an end time in the BUSINESS time zone; an end at or before the start means the
--   shift ends on the next day), with the minutes before the start and after the end in which work still belongs to the shift.
--   Every business has exactly one default shift "General day" (00:00-24:00, no grace) = the calendar day = the behaviour before this
--   migration. A trigger gives every new business its default shift, whichever way the business is created.
-- * employee_shift_assignments: which shift an employee works from which date on (history; no assignment = the default shift).
-- * employee_schedule_entries: the roster, one entry per employee and date (a shift, or NULL = a week off; the weekly off is rotational).
-- * workdays keep a snapshot of the shift they were attributed to (shift_id, shift_name, scheduled_start_at, scheduled_end_at,
--   is_outside_shift), so changing a shift never rewrites old workdays. work_date is the SHIFT DATE from now on (for a default-shift
--   workday it is the calendar date, as before); the unique key (employee_id, work_date) therefore means "one workday per shift
--   occurrence".

CREATE TABLE shifts (
    id                   UUID NOT NULL DEFAULT gen_random_uuid(),
    business_id          UUID NOT NULL DEFAULT juristrack_tenant_id(),
    name                 TEXT NOT NULL,
    start_minute         INTEGER NOT NULL,                    -- minutes after midnight on the business time zone's wall clock (19:30 = 1170)
    end_minute           INTEGER NOT NULL,                    -- at or before start_minute = ends on the next day (equal = 24 hours)
    early_start_minutes  INTEGER NOT NULL DEFAULT 180,
    late_end_minutes     INTEGER NOT NULL DEFAULT 180,
    is_default           BOOLEAN NOT NULL DEFAULT FALSE,
    is_active            BOOLEAN NOT NULL DEFAULT TRUE,
    created_at           TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT shifts_pkey PRIMARY KEY (id),
    CONSTRAINT shifts_name_not_blank CHECK (length(btrim(name)) BETWEEN 1 AND 60),
    CONSTRAINT shifts_start_minute_range CHECK (start_minute BETWEEN 0 AND 1439),
    CONSTRAINT shifts_end_minute_range CHECK (end_minute BETWEEN 0 AND 1439),
    CONSTRAINT shifts_early_start_range CHECK (early_start_minutes BETWEEN 0 AND 720),
    CONSTRAINT shifts_late_end_range CHECK (late_end_minutes BETWEEN 0 AND 720),
    CONSTRAINT shifts_default_is_active CHECK (NOT is_default OR is_active)
);
CREATE UNIQUE INDEX shifts_business_id_name_key ON shifts (business_id, lower(name));
CREATE UNIQUE INDEX shifts_business_id_default_key ON shifts (business_id) WHERE is_default;
ALTER TABLE shifts ADD CONSTRAINT shifts_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE RESTRICT ON UPDATE CASCADE;

CREATE TABLE employee_shift_assignments (
    id              UUID NOT NULL DEFAULT gen_random_uuid(),
    business_id     UUID NOT NULL DEFAULT juristrack_tenant_id(),
    employee_id     UUID NOT NULL,
    shift_id        UUID NOT NULL,
    effective_from  DATE NOT NULL,                            -- shift date in the business time zone
    created_at      TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT employee_shift_assignments_pkey PRIMARY KEY (id)
);
CREATE UNIQUE INDEX employee_shift_assignments_employee_id_effective_from_key ON employee_shift_assignments (employee_id, effective_from);
CREATE INDEX employee_shift_assignments_shift_id_idx ON employee_shift_assignments (shift_id);
ALTER TABLE employee_shift_assignments ADD CONSTRAINT employee_shift_assignments_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE employee_shift_assignments ADD CONSTRAINT employee_shift_assignments_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE employee_shift_assignments ADD CONSTRAINT employee_shift_assignments_shift_id_fkey FOREIGN KEY (shift_id) REFERENCES shifts (id) ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE workdays
    ADD COLUMN shift_id            UUID,
    ADD COLUMN shift_name          TEXT,
    ADD COLUMN scheduled_start_at  TIMESTAMPTZ(6),
    ADD COLUMN scheduled_end_at    TIMESTAMPTZ(6),
    ADD COLUMN is_outside_shift    BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN is_off_day          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN extra_seconds       INTEGER;
-- the roster: one entry per employee and date overrides the assigned shift (shift_id NULL = a week off; the weekly off can be any day)
CREATE TABLE employee_schedule_entries (
    id           UUID NOT NULL DEFAULT gen_random_uuid(),
    business_id  UUID NOT NULL DEFAULT juristrack_tenant_id(),
    employee_id  UUID NOT NULL,
    entry_date   DATE NOT NULL,                               -- shift date in the business time zone
    shift_id     UUID,
    reason       TEXT,
    created_by   UUID,
    created_at   TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT employee_schedule_entries_pkey PRIMARY KEY (id),
    CONSTRAINT employee_schedule_entries_reason_length CHECK (reason IS NULL OR length(reason) <= 500)
);
CREATE UNIQUE INDEX employee_schedule_entries_employee_id_entry_date_key ON employee_schedule_entries (employee_id, entry_date);
CREATE INDEX employee_schedule_entries_business_id_entry_date_idx ON employee_schedule_entries (business_id, entry_date);
ALTER TABLE employee_schedule_entries ADD CONSTRAINT employee_schedule_entries_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE employee_schedule_entries ADD CONSTRAINT employee_schedule_entries_employee_id_fkey FOREIGN KEY (employee_id) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE employee_schedule_entries ADD CONSTRAINT employee_schedule_entries_shift_id_fkey FOREIGN KEY (shift_id) REFERENCES shifts (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE employee_schedule_entries ADD CONSTRAINT employee_schedule_entries_created_by_fkey FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT ON UPDATE CASCADE;

ALTER TABLE workdays ADD CONSTRAINT workdays_shift_id_fkey FOREIGN KEY (shift_id) REFERENCES shifts (id) ON DELETE RESTRICT ON UPDATE CASCADE;
COMMENT ON COLUMN workdays.work_date IS 'the shift date in the business time zone (the calendar date for the default shift)';

-- ── row-level security, as for every business-owned table ──
DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['shifts', 'employee_shift_assignments', 'employee_schedule_entries'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (juristrack_tenant_id() IS NULL OR business_id = juristrack_tenant_id()) '
                    || 'WITH CHECK (juristrack_tenant_id() IS NULL OR business_id = juristrack_tenant_id())', table_name);
    END LOOP;
END $$;
GRANT SELECT, INSERT, UPDATE, DELETE ON shifts, employee_shift_assignments, employee_schedule_entries TO juristrack_tenant;

-- ── every business has its default shift ──
CREATE FUNCTION juristrack_create_default_shift() RETURNS trigger AS $$
BEGIN
    INSERT INTO shifts (business_id, name, start_minute, end_minute, early_start_minutes, late_end_minutes, is_default)
    VALUES (NEW.id, 'General day', 0, 0, 0, 0, TRUE);
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER businesses_default_shift AFTER INSERT ON businesses FOR EACH ROW EXECUTE FUNCTION juristrack_create_default_shift();

INSERT INTO shifts (business_id, name, start_minute, end_minute, early_start_minutes, late_end_minutes, is_default)
SELECT id, 'General day', 0, 0, 0, 0, TRUE FROM businesses;

-- existing workdays belong to the default shift of their business: the calendar day of their date in the zone they were started in
UPDATE workdays SET
    shift_id = (SELECT shift.id FROM shifts shift WHERE shift.business_id = workdays.business_id AND shift.is_default),
    shift_name = 'General day',
    scheduled_start_at = work_date::timestamp AT TIME ZONE time_zone,
    scheduled_end_at = (work_date + 1)::timestamp AT TIME ZONE time_zone,
    extra_seconds = CASE WHEN status = 'COMPLETED' THEN 0 END;

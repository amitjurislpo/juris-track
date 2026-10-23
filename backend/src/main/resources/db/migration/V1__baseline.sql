-- JurisTrack baseline: the old Prisma schema (init, integrity_checks, display_timezones) as plain SQL, rebuilt for the
-- Spring Boot API. Fresh schema: no data is migrated from the old app.
-- Changes against the old schema:
--   * snake_case tables and columns, UUID ids;
--   * multi-business like JurisOne: one row per business in "businesses" (replaces the OrganizationSetting singleton),
--     business_id on every business-owned table and row-level security (policy "tenant_isolation");
--   * sign-in by emailed code: no password columns; "sessions", "refresh_tokens" and "login_otps" follow JurisOne's tables;
--   * embedded in JurisOne, businesses and users are provisioned just in time from JurisOne's signed token and remember
--     the JurisOne business / employee they belong to (both columns stay NULL when JurisTrack runs standalone).
--
-- Source-of-truth principle (unchanged): productive and break time come from the timestamps on work_sessions /
-- break_sessions. The totals cached on workdays are recomputed from those intervals and never incremented directly.
-- "Only one open X" rules use nullable UNIQUE "active key" columns (the owner id while open, NULL once closed) plus the
-- CHECK constraints below. The old checks let an open row with a NULL key through (NULL = x is not false); here an open
-- interval must carry its key (IS NOT NULL added).

-- ── enums ───────────────────────────────────────────────────────────────────
-- SUPER_ADMIN: the platform user (no business), creates businesses and their ADMIN (the owner); the owner adds
-- EMPLOYEEs and assigns each one a reporting manager (users.manager_id; the reporting line replaces the old app's teams).
-- MANAGER sees the work, break and productive time of their reports. Everybody tracks their own time the same way.
CREATE TYPE user_role AS ENUM ('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'EMPLOYEE');
CREATE TYPE workday_status AS ENUM ('WORKING', 'ON_BREAK', 'COMPLETED');

-- ── tenancy ─────────────────────────────────────────────────────────────────
-- The business of the current request, or NULL in system mode (sign-in, provisioning, commands).
CREATE FUNCTION juristrack_tenant_id() RETURNS UUID LANGUAGE sql STABLE AS $$
    SELECT NULLIF(current_setting('app.business_id', true), '')::uuid
$$;

-- ── businesses ──────────────────────────────────────────────────────────────
CREATE TABLE businesses (
    id                    UUID NOT NULL DEFAULT gen_random_uuid(),
    name                  TEXT NOT NULL,
    time_zone             TEXT NOT NULL DEFAULT 'Asia/Kolkata',
    stale_workday_hours   INTEGER NOT NULL DEFAULT 14,
    -- IANA zones viewers may switch the display to (the business time zone is always offered)
    display_time_zones    TEXT[] NOT NULL DEFAULT ARRAY['Asia/Kolkata', 'America/New_York']::TEXT[],
    -- the JurisOne business this one belongs to when embedded in JurisOne (NULL standalone)
    jurisone_business_id  UUID,
    created_at            TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT businesses_pkey PRIMARY KEY (id),
    CONSTRAINT businesses_stale_workday_hours_range CHECK (stale_workday_hours BETWEEN 1 AND 48)
);
CREATE UNIQUE INDEX businesses_jurisone_business_id_key ON businesses (jurisone_business_id);

-- ── users ───────────────────────────────────────────────────────────────────
CREATE TABLE users (
    id                    UUID NOT NULL DEFAULT gen_random_uuid(),
    business_id           UUID DEFAULT juristrack_tenant_id(),   -- NULL only for the platform super admin
    employee_code         TEXT,
    first_name            TEXT NOT NULL,
    last_name             TEXT NOT NULL,
    email                 TEXT NOT NULL,           -- the sign-in: unique across every business
    role                  user_role NOT NULL DEFAULT 'EMPLOYEE',
    is_active             BOOLEAN NOT NULL DEFAULT true,
    last_login_at         TIMESTAMPTZ(6),
    -- the reporting manager (same business); the manager sees this user's work, break and productive time
    manager_id            UUID,
    -- the JurisOne employee this user is when embedded in JurisOne (NULL standalone); matched first, then email.
    -- Embedded, JurisOne's roster sync and token keep manager_id in line with JurisOne's reporting lines.
    jurisone_employee_id  UUID,
    created_at            TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT users_pkey PRIMARY KEY (id),
    -- the super admin belongs to no business; everybody else belongs to exactly one
    CONSTRAINT users_super_admin_has_no_business CHECK ((role = 'SUPER_ADMIN') = (business_id IS NULL)),
    CONSTRAINT users_not_own_manager CHECK (manager_id <> id),
    -- a user outside any business (the super admin) has no manager
    CONSTRAINT users_manager_needs_business CHECK (business_id IS NOT NULL OR manager_id IS NULL)
);
CREATE UNIQUE INDEX users_email_key ON users (email);
CREATE UNIQUE INDEX users_business_id_employee_code_key ON users (business_id, employee_code);
CREATE UNIQUE INDEX users_jurisone_employee_id_key ON users (jurisone_employee_id);
CREATE INDEX users_business_id_role_is_active_idx ON users (business_id, role, is_active);
CREATE INDEX users_last_name_first_name_idx ON users (last_name, first_name);
CREATE INDEX users_manager_id_idx ON users (manager_id);
-- target of the same-business manager foreign key
CREATE UNIQUE INDEX users_business_id_id_key ON users (business_id, id);

-- ── sign-in (global tables: sign-in runs before any business is known, so no business_id / RLS, as in JurisOne) ──
-- One row per signed-in device (the refresh-token family); the short-lived JWT access token carries its id.
CREATE TABLE sessions (
    id              TEXT NOT NULL,                 -- SHA-256 of the token; the raw token only lives in the cookie
    user_id         UUID NOT NULL,
    created_at      TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at      TIMESTAMPTZ(6) NOT NULL,
    last_seen_at    TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address      TEXT,
    user_agent      TEXT,
    revoked_at      TIMESTAMPTZ(6),
    revoked_reason  TEXT,

    CONSTRAINT sessions_pkey PRIMARY KEY (id)
);
CREATE INDEX sessions_user_id_idx ON sessions (user_id);
CREATE INDEX sessions_expires_at_idx ON sessions (expires_at);

-- Refresh tokens rotate on every use: each is stored once (SHA-256), marked used when exchanged, and presenting a used
-- one again revokes the whole session.
CREATE TABLE refresh_tokens (
    token_hash   TEXT NOT NULL,
    session_id   TEXT NOT NULL,
    expires_at   TIMESTAMPTZ(6) NOT NULL,
    used_at      TIMESTAMPTZ(6),
    created_at   TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address   TEXT,
    user_agent   TEXT,

    CONSTRAINT refresh_tokens_pkey PRIMARY KEY (token_hash)
);
CREATE INDEX refresh_tokens_session_id_idx ON refresh_tokens (session_id);

-- Emailed sign-in codes. One row per code sent; only a salted SHA-256 of the code is stored.
CREATE TABLE login_otps (
    id          UUID NOT NULL DEFAULT gen_random_uuid(),
    email       TEXT NOT NULL,
    code_hash   TEXT NOT NULL,
    expires_at  TIMESTAMPTZ(6) NOT NULL,
    attempts    INTEGER NOT NULL DEFAULT 0,
    used_at     TIMESTAMPTZ(6),
    created_at  TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ip_address  TEXT,

    CONSTRAINT login_otps_pkey PRIMARY KEY (id)
);
CREATE INDEX login_otps_email_created_at_idx ON login_otps (email, created_at DESC);

-- ── time tracking ───────────────────────────────────────────────────────────
CREATE TABLE workdays (
    id                  UUID NOT NULL DEFAULT gen_random_uuid(),
    business_id         UUID NOT NULL DEFAULT juristrack_tenant_id(),
    employee_id         UUID NOT NULL,
    manager_id          UUID,                     -- the employee's manager when the workday started (reporting snapshot)
    work_date           DATE NOT NULL,            -- calendar date in the business time zone when work started
    time_zone           TEXT NOT NULL,
    started_at          TIMESTAMPTZ(6) NOT NULL,
    ended_at            TIMESTAMPTZ(6),
    status              workday_status NOT NULL DEFAULT 'WORKING',
    active_employee_id  UUID,                     -- employee_id while the workday is open, NULL once completed
    -- derived cache, recomputed from the sessions on completion / correction
    productive_seconds  INTEGER,
    break_seconds       INTEGER,
    break_count         INTEGER,
    created_at          TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT workdays_pkey PRIMARY KEY (id),
    CONSTRAINT workdays_end_after_start CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT workdays_active_key_matches CHECK (active_employee_id IS NULL OR active_employee_id = employee_id),
    CONSTRAINT workdays_open_state_consistent CHECK (
        (status = 'COMPLETED' AND ended_at IS NOT NULL AND active_employee_id IS NULL)
        OR (status <> 'COMPLETED' AND ended_at IS NULL AND active_employee_id IS NOT NULL)
    ),
    CONSTRAINT workdays_totals_non_negative CHECK (
        (productive_seconds IS NULL OR productive_seconds >= 0)
        AND (break_seconds IS NULL OR break_seconds >= 0)
        AND (break_count IS NULL OR break_count >= 0)
    )
);
CREATE UNIQUE INDEX workdays_active_employee_id_key ON workdays (active_employee_id);
CREATE UNIQUE INDEX workdays_employee_id_work_date_key ON workdays (employee_id, work_date);
CREATE INDEX workdays_work_date_status_idx ON workdays (work_date, status);
CREATE INDEX workdays_manager_id_work_date_idx ON workdays (manager_id, work_date);
CREATE INDEX workdays_status_idx ON workdays (status);
CREATE INDEX workdays_started_at_idx ON workdays (started_at);

CREATE TABLE work_sessions (
    id               UUID NOT NULL DEFAULT gen_random_uuid(),
    business_id      UUID NOT NULL DEFAULT juristrack_tenant_id(),
    employee_id      UUID NOT NULL,
    workday_id       UUID NOT NULL,
    started_at       TIMESTAMPTZ(6) NOT NULL,
    ended_at         TIMESTAMPTZ(6),
    open_workday_id  UUID,                        -- workday_id while open, NULL once closed (one open session per day)
    created_at       TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT work_sessions_pkey PRIMARY KEY (id),
    CONSTRAINT work_sessions_end_after_start CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT work_sessions_open_key_consistent CHECK (
        (ended_at IS NULL AND open_workday_id IS NOT NULL AND open_workday_id = workday_id) OR (ended_at IS NOT NULL AND open_workday_id IS NULL)
    )
);
CREATE UNIQUE INDEX work_sessions_open_workday_id_key ON work_sessions (open_workday_id);
CREATE INDEX work_sessions_workday_id_started_at_idx ON work_sessions (workday_id, started_at);
CREATE INDEX work_sessions_employee_id_started_at_idx ON work_sessions (employee_id, started_at);
CREATE INDEX work_sessions_ended_at_idx ON work_sessions (ended_at);

CREATE TABLE break_sessions (
    id               UUID NOT NULL DEFAULT gen_random_uuid(),
    business_id      UUID NOT NULL DEFAULT juristrack_tenant_id(),
    employee_id      UUID NOT NULL,
    workday_id       UUID NOT NULL,
    started_at       TIMESTAMPTZ(6) NOT NULL,
    ended_at         TIMESTAMPTZ(6),
    open_workday_id  UUID,                        -- workday_id while open, NULL once closed (one open break per day)
    created_at       TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT break_sessions_pkey PRIMARY KEY (id),
    CONSTRAINT break_sessions_end_after_start CHECK (ended_at IS NULL OR ended_at >= started_at),
    CONSTRAINT break_sessions_open_key_consistent CHECK (
        (ended_at IS NULL AND open_workday_id IS NOT NULL AND open_workday_id = workday_id) OR (ended_at IS NOT NULL AND open_workday_id IS NULL)
    )
);
CREATE UNIQUE INDEX break_sessions_open_workday_id_key ON break_sessions (open_workday_id);
CREATE INDEX break_sessions_workday_id_started_at_idx ON break_sessions (workday_id, started_at);
CREATE INDEX break_sessions_employee_id_started_at_idx ON break_sessions (employee_id, started_at);
CREATE INDEX break_sessions_ended_at_idx ON break_sessions (ended_at);

-- ── audit log ───────────────────────────────────────────────────────────────
CREATE TABLE audit_logs (
    id           UUID NOT NULL DEFAULT gen_random_uuid(),
    business_id  UUID DEFAULT juristrack_tenant_id(),   -- NULL = a system event outside any business
    actor_id     UUID,
    subject_id   UUID,
    action       TEXT NOT NULL,
    entity_type  TEXT NOT NULL,
    entity_id    TEXT,
    "before"     JSONB,
    "after"      JSONB,
    reason       TEXT,
    created_at   TIMESTAMPTZ(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT audit_logs_pkey PRIMARY KEY (id)
);
CREATE INDEX audit_logs_business_id_created_at_idx ON audit_logs (business_id, created_at);
CREATE INDEX audit_logs_entity_type_entity_id_idx ON audit_logs (entity_type, entity_id);
CREATE INDEX audit_logs_actor_id_idx ON audit_logs (actor_id);
CREATE INDEX audit_logs_subject_id_idx ON audit_logs (subject_id);
CREATE INDEX audit_logs_action_idx ON audit_logs (action);

-- ── foreign keys ────────────────────────────────────────────────────────────
ALTER TABLE users            ADD CONSTRAINT users_business_id_fkey            FOREIGN KEY (business_id)    REFERENCES businesses (id) ON DELETE RESTRICT ON UPDATE CASCADE;
-- the manager must be a user of the same business; deleting the manager clears only manager_id
ALTER TABLE users            ADD CONSTRAINT users_manager_id_fkey             FOREIGN KEY (business_id, manager_id) REFERENCES users (business_id, id)
    ON DELETE SET NULL (manager_id);   -- no update cascade: a manager with reports cannot move to another business
ALTER TABLE sessions         ADD CONSTRAINT sessions_user_id_fkey             FOREIGN KEY (user_id)        REFERENCES users (id)      ON DELETE CASCADE  ON UPDATE CASCADE;
ALTER TABLE refresh_tokens   ADD CONSTRAINT refresh_tokens_session_id_fkey    FOREIGN KEY (session_id)     REFERENCES sessions (id)   ON DELETE CASCADE  ON UPDATE CASCADE;
ALTER TABLE workdays         ADD CONSTRAINT workdays_business_id_fkey         FOREIGN KEY (business_id)    REFERENCES businesses (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE workdays         ADD CONSTRAINT workdays_employee_id_fkey         FOREIGN KEY (employee_id)    REFERENCES users (id)      ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE workdays         ADD CONSTRAINT workdays_manager_id_fkey          FOREIGN KEY (manager_id)     REFERENCES users (id)      ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE work_sessions    ADD CONSTRAINT work_sessions_business_id_fkey    FOREIGN KEY (business_id)    REFERENCES businesses (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE work_sessions    ADD CONSTRAINT work_sessions_employee_id_fkey    FOREIGN KEY (employee_id)    REFERENCES users (id)      ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE work_sessions    ADD CONSTRAINT work_sessions_workday_id_fkey     FOREIGN KEY (workday_id)     REFERENCES workdays (id)   ON DELETE CASCADE  ON UPDATE CASCADE;
ALTER TABLE break_sessions   ADD CONSTRAINT break_sessions_business_id_fkey   FOREIGN KEY (business_id)    REFERENCES businesses (id) ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE break_sessions   ADD CONSTRAINT break_sessions_employee_id_fkey   FOREIGN KEY (employee_id)    REFERENCES users (id)      ON DELETE RESTRICT ON UPDATE CASCADE;
ALTER TABLE break_sessions   ADD CONSTRAINT break_sessions_workday_id_fkey    FOREIGN KEY (workday_id)     REFERENCES workdays (id)   ON DELETE CASCADE  ON UPDATE CASCADE;
ALTER TABLE audit_logs       ADD CONSTRAINT audit_logs_business_id_fkey       FOREIGN KEY (business_id)    REFERENCES businesses (id) ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE audit_logs       ADD CONSTRAINT audit_logs_actor_id_fkey          FOREIGN KEY (actor_id)       REFERENCES users (id)      ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE audit_logs       ADD CONSTRAINT audit_logs_subject_id_fkey        FOREIGN KEY (subject_id)     REFERENCES users (id)      ON DELETE SET NULL ON UPDATE CASCADE;

-- ── row-level security ──────────────────────────────────────────────────────
-- Business-owned rows are visible and writable only inside the current business. System mode (app.business_id unset:
-- sign-in, the super admin's requests, commands such as seed) sees every row, as in JurisOne. Inside a business the
-- super admin's row (business_id NULL) is invisible.
ALTER TABLE businesses ENABLE ROW LEVEL SECURITY;
ALTER TABLE businesses FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON businesses
    USING (juristrack_tenant_id() IS NULL OR id = juristrack_tenant_id())
    WITH CHECK (juristrack_tenant_id() IS NULL OR id = juristrack_tenant_id());

DO $$
DECLARE table_name TEXT;
BEGIN
    FOREACH table_name IN ARRAY ARRAY['users', 'workdays', 'work_sessions', 'break_sessions', 'audit_logs'] LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', table_name);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', table_name);
        EXECUTE format('CREATE POLICY tenant_isolation ON %I USING (juristrack_tenant_id() IS NULL OR business_id = juristrack_tenant_id()) '
                    || 'WITH CHECK (juristrack_tenant_id() IS NULL OR business_id = juristrack_tenant_id())', table_name);
    END LOOP;
END $$;

-- ── the role business requests run as ───────────────────────────────────────
-- PostgreSQL superusers (like the docker-compose user) skip row-level security, so business requests switch to this
-- plain role (SET ROLE) and switch back (RESET ROLE) afterwards. Creating it needs CREATEROLE once.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'juristrack_tenant') THEN
        CREATE ROLE juristrack_tenant NOLOGIN;
    END IF;
END $$;
DO $$
BEGIN
    EXECUTE format('GRANT juristrack_tenant TO %I', current_user);
EXCEPTION WHEN duplicate_object OR invalid_grant_operation THEN NULL;  -- already a member / superuser
END $$;
GRANT USAGE ON SCHEMA public TO juristrack_tenant;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO juristrack_tenant;
GRANT EXECUTE ON FUNCTION juristrack_tenant_id() TO juristrack_tenant;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO juristrack_tenant;

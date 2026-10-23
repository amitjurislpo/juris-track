-- V3: a complete, append-only audit of every movement (owner decision 2026-10-23, MIGRATION_PLAN.md "Audit policy").
--
-- * Every entry now carries the request it came from and how it ended: source (STANDALONE: the standalone cookie session or a
--   standalone sign-in request; INTEGRATION: JurisOne's Bearer token; SYSTEM: no HTTP request, e.g. a command), the actor's role at
--   that moment, IP address, user agent (at most 400 characters), request id (X-Request-Id, at most 64 characters), HTTP method,
--   request path (never the query string) and outcome (SUCCESS, DENIED: refused for a rule, a role or a security reason, FAILED:
--   it went wrong), plus a short category for filtering.
-- * The table is append-only: a trigger refuses UPDATE, DELETE and TRUNCATE for every role (also the schema owner), and the role
--   business requests run as (juristrack_tenant) has no UPDATE / DELETE right on it at all. Only DROP (the reset command's
--   Flyway clean) removes rows.
-- * The references to users and businesses no longer clear themselves (ON DELETE SET NULL would rewrite history, and the trigger
--   refuses it anyway): users and businesses are never hard-deleted (they are deactivated / suspended), so a user or business that
--   appears in the audit log cannot be deleted (RESTRICT), and ids never change (no ON UPDATE CASCADE).
-- * Row-level security is unchanged (policy tenant_isolation from V1): inside a business only that business's rows are visible;
--   system mode (the super admin, sign-in, commands) sees every row; rows without a business (platform events, sign-in attempts for
--   unknown emails) are visible in system mode only.

ALTER TABLE audit_logs
    ADD COLUMN category      TEXT,
    ADD COLUMN outcome       TEXT NOT NULL DEFAULT 'SUCCESS',
    ADD COLUMN source        TEXT,
    ADD COLUMN actor_role    TEXT,
    ADD COLUMN ip_address    TEXT,
    ADD COLUMN user_agent    TEXT,
    ADD COLUMN request_id    TEXT,
    ADD COLUMN http_method   TEXT,
    ADD COLUMN request_path  TEXT;

-- entries written before V3 (development databases only): category from the action, source from the action (the request is unknown)
UPDATE audit_logs SET
    category = CASE split_part(action, '.', 1)
        WHEN 'auth' THEN 'auth'
        WHEN 'integration' THEN 'integration'
        WHEN 'business' THEN 'business'
        WHEN 'employee' THEN 'people'
        WHEN 'time' THEN 'time'
        WHEN 'workday' THEN 'time'
        ELSE 'security'
    END,
    source = CASE WHEN action LIKE 'integration.%' THEN 'INTEGRATION' ELSE 'STANDALONE' END,
    actor_role = (SELECT person.role::text FROM users person WHERE person.id = audit_logs.actor_id);

ALTER TABLE audit_logs
    ALTER COLUMN category SET NOT NULL,
    ALTER COLUMN source SET NOT NULL,
    ALTER COLUMN outcome DROP DEFAULT,
    ADD CONSTRAINT audit_logs_category_known CHECK (category IN ('auth', 'people', 'business', 'time', 'security', 'access', 'integration', 'audit')),
    ADD CONSTRAINT audit_logs_outcome_known CHECK (outcome IN ('SUCCESS', 'DENIED', 'FAILED')),
    ADD CONSTRAINT audit_logs_source_known CHECK (source IN ('STANDALONE', 'INTEGRATION', 'SYSTEM')),
    ADD CONSTRAINT audit_logs_actor_role_known CHECK (actor_role IS NULL OR actor_role IN ('SUPER_ADMIN', 'ADMIN', 'MANAGER', 'EMPLOYEE')),
    ADD CONSTRAINT audit_logs_user_agent_length CHECK (user_agent IS NULL OR length(user_agent) <= 400),
    ADD CONSTRAINT audit_logs_request_id_length CHECK (request_id IS NULL OR length(request_id) <= 64),
    ADD CONSTRAINT audit_logs_request_path_length CHECK (request_path IS NULL OR length(request_path) <= 300);

-- ── references that never rewrite history ──
ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_business_id_fkey;
ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_actor_id_fkey;
ALTER TABLE audit_logs DROP CONSTRAINT audit_logs_subject_id_fkey;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE RESTRICT;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_actor_id_fkey    FOREIGN KEY (actor_id)    REFERENCES users (id)      ON DELETE RESTRICT;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_subject_id_fkey  FOREIGN KEY (subject_id)  REFERENCES users (id)      ON DELETE RESTRICT;

-- ── indexes for the audit log pages (newest first, filtered by business, actor, subject, action, category, entity, request) ──
DROP INDEX audit_logs_business_id_created_at_idx;
DROP INDEX audit_logs_actor_id_idx;
DROP INDEX audit_logs_subject_id_idx;
DROP INDEX audit_logs_action_idx;
CREATE INDEX audit_logs_business_id_created_at_idx ON audit_logs (business_id, created_at DESC);
CREATE INDEX audit_logs_created_at_idx ON audit_logs (created_at DESC);
CREATE INDEX audit_logs_actor_id_created_at_idx ON audit_logs (actor_id, created_at DESC);
CREATE INDEX audit_logs_subject_id_created_at_idx ON audit_logs (subject_id, created_at DESC);
-- text_pattern_ops: also serves the prefix filter (action LIKE 'time.%')
CREATE INDEX audit_logs_action_created_at_idx ON audit_logs (action text_pattern_ops, created_at DESC);
CREATE INDEX audit_logs_business_id_category_created_at_idx ON audit_logs (business_id, category, created_at DESC);
CREATE INDEX audit_logs_request_id_idx ON audit_logs (request_id);
-- audit_logs_entity_type_entity_id_idx (entity_type, entity_id) from V1 stays

-- ── append-only ──
CREATE FUNCTION juristrack_refuse_audit_log_change() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'audit_logs is append-only: % is not allowed', TG_OP USING ERRCODE = 'insufficient_privilege';
END $$;

CREATE TRIGGER audit_logs_append_only BEFORE UPDATE OR DELETE ON audit_logs
    FOR EACH ROW EXECUTE FUNCTION juristrack_refuse_audit_log_change();
CREATE TRIGGER audit_logs_no_truncate BEFORE TRUNCATE ON audit_logs
    FOR EACH STATEMENT EXECUTE FUNCTION juristrack_refuse_audit_log_change();

REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs FROM juristrack_tenant;

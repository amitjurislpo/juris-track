-- Reverses V3__complete_append_only_audit.sql. Flyway Community has no undo: run with psql as the schema owner, then delete the
-- version 3 row from flyway_schema_history. The request context, outcome and category of every entry are dropped, and the audit
-- log is no longer protected against changes.

DROP TRIGGER IF EXISTS audit_logs_no_truncate ON audit_logs;
DROP TRIGGER IF EXISTS audit_logs_append_only ON audit_logs;
DROP FUNCTION IF EXISTS juristrack_refuse_audit_log_change();
GRANT UPDATE, DELETE ON audit_logs TO juristrack_tenant;

DROP INDEX IF EXISTS audit_logs_request_id_idx;
DROP INDEX IF EXISTS audit_logs_business_id_category_created_at_idx;
DROP INDEX IF EXISTS audit_logs_action_created_at_idx;
DROP INDEX IF EXISTS audit_logs_subject_id_created_at_idx;
DROP INDEX IF EXISTS audit_logs_actor_id_created_at_idx;
DROP INDEX IF EXISTS audit_logs_created_at_idx;
DROP INDEX IF EXISTS audit_logs_business_id_created_at_idx;
CREATE INDEX audit_logs_business_id_created_at_idx ON audit_logs (business_id, created_at);
CREATE INDEX audit_logs_actor_id_idx ON audit_logs (actor_id);
CREATE INDEX audit_logs_subject_id_idx ON audit_logs (subject_id);
CREATE INDEX audit_logs_action_idx ON audit_logs (action);

ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_business_id_fkey;
ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_actor_id_fkey;
ALTER TABLE audit_logs DROP CONSTRAINT IF EXISTS audit_logs_subject_id_fkey;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_business_id_fkey FOREIGN KEY (business_id) REFERENCES businesses (id) ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_actor_id_fkey    FOREIGN KEY (actor_id)    REFERENCES users (id)      ON DELETE SET NULL ON UPDATE CASCADE;
ALTER TABLE audit_logs ADD CONSTRAINT audit_logs_subject_id_fkey  FOREIGN KEY (subject_id)  REFERENCES users (id)      ON DELETE SET NULL ON UPDATE CASCADE;

ALTER TABLE audit_logs
    DROP COLUMN IF EXISTS request_path,
    DROP COLUMN IF EXISTS http_method,
    DROP COLUMN IF EXISTS request_id,
    DROP COLUMN IF EXISTS user_agent,
    DROP COLUMN IF EXISTS ip_address,
    DROP COLUMN IF EXISTS actor_role,
    DROP COLUMN IF EXISTS source,
    DROP COLUMN IF EXISTS outcome,
    DROP COLUMN IF EXISTS category;

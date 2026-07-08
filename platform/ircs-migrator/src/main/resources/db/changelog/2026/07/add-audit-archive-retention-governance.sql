-- liquibase formatted sql

-- changeset codex:20260611-audit-archive-retention-governance
ALTER TABLE request_audit_logs
    ADD COLUMN IF NOT EXISTS audit_class VARCHAR(32);

UPDATE request_audit_logs
   SET audit_class = 'BEHAVIOR'
 WHERE audit_class IS NULL;

ALTER TABLE request_audit_logs
    ALTER COLUMN audit_class SET DEFAULT 'BEHAVIOR';

ALTER TABLE request_audit_logs
    ALTER COLUMN audit_class SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_req_audit_class_created
    ON request_audit_logs (audit_class, created_at DESC);

ALTER TABLE worker_job_audit_events
    ADD COLUMN IF NOT EXISTS audit_class VARCHAR(32);

UPDATE worker_job_audit_events
   SET audit_class = 'SYSTEM'
 WHERE audit_class IS NULL;

ALTER TABLE worker_job_audit_events
    ALTER COLUMN audit_class SET DEFAULT 'SYSTEM';

ALTER TABLE worker_job_audit_events
    ALTER COLUMN audit_class SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_worker_job_audit_class_created
    ON worker_job_audit_events (audit_class, created_at DESC);

ALTER TABLE notification_mail_send_history
    ADD COLUMN IF NOT EXISTS audit_class VARCHAR(32);

UPDATE notification_mail_send_history
   SET audit_class = 'SYSTEM'
 WHERE audit_class IS NULL;

ALTER TABLE notification_mail_send_history
    ALTER COLUMN audit_class SET DEFAULT 'SYSTEM';

ALTER TABLE notification_mail_send_history
    ALTER COLUMN audit_class SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_notification_mail_send_history_audit_class_created
    ON notification_mail_send_history (audit_class, created_at DESC);

CREATE TABLE IF NOT EXISTS audit_es_replication_outbox (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    audit_class VARCHAR(32) NOT NULL,
    source_table VARCHAR(128) NOT NULL,
    source_id UUID NOT NULL,
    event_type VARCHAR(32) NOT NULL DEFAULT 'UPSERT',
    payload TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_error TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_es_outbox_status_next_attempt
    ON audit_es_replication_outbox (status, next_attempt_at, created_at);

CREATE INDEX IF NOT EXISTS idx_audit_es_outbox_source
    ON audit_es_replication_outbox (source_table, source_id);

CREATE INDEX IF NOT EXISTS idx_audit_es_outbox_class_created
    ON audit_es_replication_outbox (audit_class, created_at DESC);

CREATE TABLE IF NOT EXISTS audit_archive_entries (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    audit_class VARCHAR(32) NOT NULL,
    archive_type VARCHAR(64) NOT NULL,
    source_table VARCHAR(128) NOT NULL,
    source_id UUID NOT NULL,
    source_created_at TIMESTAMPTZ,
    retention_days INTEGER,
    reason VARCHAR(256),
    payload TEXT
);

CREATE INDEX IF NOT EXISTS idx_audit_archive_source
    ON audit_archive_entries (source_table, source_id);

CREATE INDEX IF NOT EXISTS idx_audit_archive_class_created
    ON audit_archive_entries (audit_class, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_archive_type_created
    ON audit_archive_entries (archive_type, created_at DESC);

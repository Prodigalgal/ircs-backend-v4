CREATE TABLE IF NOT EXISTS notification_mail_send_history (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    audit_class VARCHAR(32) NOT NULL DEFAULT 'SYSTEM',
    correlation_id VARCHAR(128),
    recipient VARCHAR(320),
    subject VARCHAR(512),
    template_code VARCHAR(256),
    delivery_mode VARCHAR(32),
    status VARCHAR(32) NOT NULL,
    credential_id UUID,
    failure_code VARCHAR(256),
    failure_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_notification_mail_send_history_created
    ON notification_mail_send_history (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notification_mail_send_history_status_mode_created
    ON notification_mail_send_history (status, delivery_mode, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notification_mail_send_history_template_created
    ON notification_mail_send_history (template_code, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notification_mail_send_history_correlation
    ON notification_mail_send_history (correlation_id);

CREATE INDEX IF NOT EXISTS idx_notification_mail_send_history_audit_class_created
    ON notification_mail_send_history (audit_class, created_at DESC);

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

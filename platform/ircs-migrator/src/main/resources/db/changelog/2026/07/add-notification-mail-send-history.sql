--liquibase formatted sql

--changeset prodigalgal:20260609-add-notification-mail-send-history
CREATE TABLE IF NOT EXISTS notification_mail_send_history (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
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

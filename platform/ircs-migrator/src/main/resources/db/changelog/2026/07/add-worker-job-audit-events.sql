--liquibase formatted sql

--changeset prodigalgal:20260607-add-worker-job-audit-events
CREATE TABLE IF NOT EXISTS worker_job_audit_events (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    job_source VARCHAR(128) NOT NULL,
    job_type VARCHAR(64) NOT NULL,
    job_name VARCHAR(128) NOT NULL,
    correlation_id VARCHAR(128),
    status VARCHAR(32) NOT NULL,
    duration_ms BIGINT NOT NULL DEFAULT 0,
    error_class VARCHAR(256),
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_worker_job_audit_created
    ON worker_job_audit_events (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_worker_job_audit_source_status
    ON worker_job_audit_events (job_source, status);
CREATE INDEX IF NOT EXISTS idx_worker_job_audit_job_name
    ON worker_job_audit_events (job_name);
CREATE INDEX IF NOT EXISTS idx_worker_job_audit_correlation
    ON worker_job_audit_events (correlation_id);

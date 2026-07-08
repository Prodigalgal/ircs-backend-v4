--liquibase formatted sql

--changeset prodigalgal:20260702-add-request-audit-summary-snapshot
CREATE TABLE IF NOT EXISTS ops_request_audit_summary_snapshots (
    snapshot_key VARCHAR(64) PRIMARY KEY,
    total_count BIGINT NOT NULL DEFAULT 0,
    error_count BIGINT NOT NULL DEFAULT 0,
    slow_count BIGINT NOT NULL DEFAULT 0,
    max_duration_ms BIGINT,
    generated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    stale_until TIMESTAMPTZ NOT NULL,
    source VARCHAR(64) NOT NULL DEFAULT 'database',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ops_request_audit_summary_snapshots_stale_until
    ON ops_request_audit_summary_snapshots (stale_until DESC);

CREATE INDEX IF NOT EXISTS idx_ops_request_audit_summary_snapshots_generated
    ON ops_request_audit_summary_snapshots (generated_at DESC);

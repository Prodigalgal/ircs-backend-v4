--liquibase formatted sql

--changeset prodigalgal:20260702-add-dashboard-read-model-snapshots
CREATE TABLE IF NOT EXISTS ops_dashboard_read_model_snapshots (
    snapshot_key VARCHAR(128) PRIMARY KEY,
    payload JSONB NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    stale_until TIMESTAMPTZ NOT NULL,
    source VARCHAR(64) NOT NULL DEFAULT 'database',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ops_dashboard_read_model_snapshots_stale_until
    ON ops_dashboard_read_model_snapshots (stale_until DESC);

CREATE INDEX IF NOT EXISTS idx_ops_dashboard_read_model_snapshots_generated
    ON ops_dashboard_read_model_snapshots (generated_at DESC);

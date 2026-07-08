--liquibase formatted sql

--changeset prodigalgal:20260701-add-dashboard-stats-snapshot
CREATE TABLE IF NOT EXISTS ops_dashboard_stats_snapshots (
    snapshot_key VARCHAR(64) PRIMARY KEY,
    raw_count_db BIGINT NOT NULL DEFAULT 0,
    raw_count_es BIGINT NOT NULL DEFAULT 0,
    unified_count_db BIGINT NOT NULL DEFAULT 0,
    unified_count_es BIGINT NOT NULL DEFAULT 0,
    total_tasks BIGINT NOT NULL DEFAULT 0,
    pending_normalization BIGINT NOT NULL DEFAULT 0,
    pending_enrichment BIGINT NOT NULL DEFAULT 0,
    normalization_failed BIGINT NOT NULL DEFAULT 0,
    enrichment_missing_douban BIGINT NOT NULL DEFAULT 0,
    enrichment_missing_tmdb BIGINT NOT NULL DEFAULT 0,
    image_download_failed BIGINT NOT NULL DEFAULT 0,
    image_dead_link BIGINT NOT NULL DEFAULT 0,
    generated_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    stale_until TIMESTAMPTZ NOT NULL,
    source VARCHAR(64) NOT NULL DEFAULT 'database',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_ops_dashboard_stats_snapshots_stale_until
    ON ops_dashboard_stats_snapshots (stale_until DESC);

CREATE INDEX IF NOT EXISTS idx_ops_dashboard_stats_snapshots_generated
    ON ops_dashboard_stats_snapshots (generated_at DESC);

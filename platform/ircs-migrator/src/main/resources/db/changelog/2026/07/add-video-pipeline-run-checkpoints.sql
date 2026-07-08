--liquibase formatted sql

--changeset prodigalgal:20260617-add-video-pipeline-run-checkpoints
CREATE TABLE IF NOT EXISTS raw_video_pipeline_runs (
    raw_video_id UUID NOT NULL REFERENCES raw_videos(id) ON DELETE CASCADE,
    pipeline_version VARCHAR(128) NOT NULL,
    step VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    expected_count INTEGER NOT NULL DEFAULT 0,
    completed_count INTEGER NOT NULL DEFAULT 0,
    success_count INTEGER NOT NULL DEFAULT 0,
    failure_count INTEGER NOT NULL DEFAULT 0,
    retryable_failure_count INTEGER NOT NULL DEFAULT 0,
    permanent_failure_count INTEGER NOT NULL DEFAULT 0,
    next_retry_at TIMESTAMPTZ,
    claimed_by VARCHAR(128),
    claimed_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (raw_video_id, pipeline_version, step)
);

CREATE TABLE IF NOT EXISTS raw_video_enrichment_provider_runs (
    raw_video_id UUID NOT NULL REFERENCES raw_videos(id) ON DELETE CASCADE,
    pipeline_version VARCHAR(128) NOT NULL,
    provider_type VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    retryable BOOLEAN,
    error_code VARCHAR(128),
    error_message TEXT,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    dispatched_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (raw_video_id, pipeline_version, provider_type)
);

CREATE INDEX IF NOT EXISTS idx_raw_video_pipeline_runs_active
    ON raw_video_pipeline_runs (step, status, next_retry_at, updated_at, raw_video_id)
    WHERE status IN ('PENDING', 'FAILED', 'PROCESSING');

CREATE INDEX IF NOT EXISTS idx_raw_video_provider_runs_pending
    ON raw_video_enrichment_provider_runs (provider_type, updated_at, raw_video_id)
    WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_raw_video_provider_runs_pipeline
    ON raw_video_enrichment_provider_runs (raw_video_id, pipeline_version, status);

--liquibase formatted sql

--changeset prodigalgal:2026-07-add-magnet-providers
CREATE TABLE IF NOT EXISTS magnet_providers (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint,
    code varchar(64) NOT NULL,
    name varchar(100) NOT NULL,
    provider_type varchar(50) NOT NULL,
    base_url varchar(500) NOT NULL,
    enabled boolean NOT NULL DEFAULT true,
    priority integer NOT NULL DEFAULT 100,
    risk_level varchar(50) NOT NULL DEFAULT 'HIGH',
    supported_external_ids jsonb NOT NULL DEFAULT '[]'::jsonb,
    min_delay_ms integer NOT NULL DEFAULT 1000,
    max_delay_ms integer NOT NULL DEFAULT 3000,
    timeout_ms integer NOT NULL DEFAULT 10000,
    result_limit integer NOT NULL DEFAULT 20,
    auto_approve_allowed boolean NOT NULL DEFAULT false,
    content_policy varchar(500),
    last_health_check_at timestamptz,
    last_health_status varchar(50),
    last_error_message text,
    CONSTRAINT uk_magnet_providers_code UNIQUE (code)
);

CREATE INDEX IF NOT EXISTS idx_magnet_providers_enabled_priority
    ON magnet_providers(enabled, priority);

--changeset prodigalgal:2026-07-seed-default-magnet-providers
INSERT INTO magnet_providers (
    id,
    code,
    name,
    provider_type,
    base_url,
    enabled,
    priority,
    risk_level,
    supported_external_ids,
    min_delay_ms,
    max_delay_ms,
    timeout_ms,
    result_limit,
    auto_approve_allowed,
    content_policy
) VALUES
(
    gen_random_uuid(),
    'yts_bz',
    'YTS.BZ',
    'YTS_BZ',
    'https://movies-api.accel.li/api/v2',
    true,
    10,
    'HIGH',
    '["IMDB"]'::jsonb,
    1000,
    3000,
    10000,
    20,
    true,
    '仅使用 IMDb 外部 ID 查询电影资源；不使用标题、年份或其他兜底关键词。'
),
(
    gen_random_uuid(),
    'thepiratebay',
    'The Pirate Bay APIBay',
    'THE_PIRATE_BAY',
    'https://apibay.org',
    true,
    20,
    'HIGH',
    '["IMDB"]'::jsonb,
    2000,
    5000,
    10000,
    20,
    true,
    '仅使用 IMDb 外部 ID 字符串查询；不使用标题、年份、TMDB ID、豆瓣 ID 或其他兜底关键词。'
)
ON CONFLICT (code) DO NOTHING;

--changeset prodigalgal:2026-07-add-magnet-links
CREATE TABLE IF NOT EXISTS magnet_links (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint,
    unified_video_id uuid NOT NULL,
    provider_id uuid,
    provider_code varchar(64) NOT NULL,
    info_hash varchar(64) NOT NULL,
    magnet_uri text NOT NULL,
    title varchar(500) NOT NULL,
    size_bytes bigint,
    size_label varchar(100),
    published_at timestamptz,
    seeders integer,
    leechers integer,
    quality varchar(50),
    resolution varchar(50),
    matched_external_id_type varchar(20) NOT NULL,
    matched_external_id_value varchar(100) NOT NULL,
    match_score integer NOT NULL DEFAULT 100,
    status varchar(30) NOT NULL DEFAULT 'APPROVED',
    source_url varchar(1000),
    tags jsonb NOT NULL DEFAULT '[]'::jsonb,
    provider_evidence jsonb NOT NULL DEFAULT '{}'::jsonb,
    first_seen_at timestamptz NOT NULL DEFAULT now(),
    last_seen_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_magnet_links_unified_video
        FOREIGN KEY (unified_video_id) REFERENCES unified_videos(id) ON DELETE CASCADE,
    CONSTRAINT fk_magnet_links_provider
        FOREIGN KEY (provider_id) REFERENCES magnet_providers(id) ON DELETE SET NULL,
    CONSTRAINT uk_magnet_links_unified_hash UNIQUE (unified_video_id, info_hash)
);

CREATE INDEX IF NOT EXISTS idx_magnet_links_unified_status
    ON magnet_links(unified_video_id, status);

CREATE INDEX IF NOT EXISTS idx_magnet_links_info_hash
    ON magnet_links(info_hash);

CREATE INDEX IF NOT EXISTS idx_magnet_links_provider_code
    ON magnet_links(provider_code);

--changeset prodigalgal:2026-07-add-magnet-search-jobs
CREATE TABLE IF NOT EXISTS magnet_search_jobs (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint,
    unified_video_id uuid NOT NULL,
    trigger_type varchar(30) NOT NULL,
    status varchar(30) NOT NULL,
    provider_codes jsonb NOT NULL DEFAULT '[]'::jsonb,
    external_id_plan jsonb NOT NULL DEFAULT '[]'::jsonb,
    started_at timestamptz,
    finished_at timestamptz,
    total_candidates integer NOT NULL DEFAULT 0,
    accepted_count integer NOT NULL DEFAULT 0,
    rejected_count integer NOT NULL DEFAULT 0,
    skipped_reason varchar(255),
    error_message text,
    CONSTRAINT fk_magnet_jobs_unified_video
        FOREIGN KEY (unified_video_id) REFERENCES unified_videos(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_magnet_jobs_unified_created
    ON magnet_search_jobs(unified_video_id, created_at DESC);

--changeset prodigalgal:2026-07-add-magnet-provider-runs
CREATE TABLE IF NOT EXISTS magnet_provider_runs (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint,
    job_id uuid NOT NULL,
    provider_id uuid,
    provider_code varchar(64) NOT NULL,
    external_id_type varchar(20) NOT NULL,
    external_id_value varchar(100) NOT NULL,
    status varchar(30) NOT NULL,
    request_url varchar(1000),
    http_status integer,
    candidate_count integer NOT NULL DEFAULT 0,
    accepted_count integer NOT NULL DEFAULT 0,
    duration_ms bigint,
    error_message text,
    CONSTRAINT fk_magnet_provider_runs_job
        FOREIGN KEY (job_id) REFERENCES magnet_search_jobs(id) ON DELETE CASCADE,
    CONSTRAINT fk_magnet_provider_runs_provider
        FOREIGN KEY (provider_id) REFERENCES magnet_providers(id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_magnet_provider_runs_job
    ON magnet_provider_runs(job_id);

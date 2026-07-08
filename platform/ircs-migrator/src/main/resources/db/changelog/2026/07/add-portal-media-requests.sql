-- liquibase formatted sql

-- changeset codex:202607-add-portal-media-requests
CREATE TABLE IF NOT EXISTS portal_media_requests (
    id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    member_id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    normalized_title VARCHAR(255) NOT NULL,
    release_year INTEGER NOT NULL DEFAULT 0,
    extra_info TEXT,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    request_count INTEGER NOT NULL DEFAULT 1,
    last_requested_at TIMESTAMPTZ NOT NULL,
    scheduled_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    last_error_message TEXT,
    CONSTRAINT pk_portal_media_requests PRIMARY KEY (id),
    CONSTRAINT fk_portal_media_requests_member FOREIGN KEY (member_id) REFERENCES members(id) ON DELETE CASCADE,
    CONSTRAINT uk_portal_media_requests_title_year UNIQUE (normalized_title, release_year)
);

CREATE INDEX IF NOT EXISTS idx_portal_media_requests_member_created
    ON portal_media_requests (member_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_portal_media_requests_status_requested
    ON portal_media_requests (status, last_requested_at ASC, id ASC);

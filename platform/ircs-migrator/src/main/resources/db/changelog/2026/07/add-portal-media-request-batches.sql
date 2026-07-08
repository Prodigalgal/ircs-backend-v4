CREATE TABLE IF NOT EXISTS portal_media_request_batches (
    id UUID NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(32) NOT NULL DEFAULT 'READY',
    request_count INTEGER NOT NULL DEFAULT 0,
    scheduled_count INTEGER NOT NULL DEFAULT 0,
    skipped_count INTEGER NOT NULL DEFAULT 0,
    failed_count INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP WITHOUT TIME ZONE,
    cancelled_at TIMESTAMP WITHOUT TIME ZONE,
    completed_at TIMESTAMP WITHOUT TIME ZONE,
    last_error_message VARCHAR(1000),
    CONSTRAINT pk_portal_media_request_batches PRIMARY KEY (id)
);

CREATE TABLE IF NOT EXISTS portal_media_request_batch_items (
    id UUID NOT NULL,
    batch_id UUID NOT NULL,
    media_request_id UUID NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    title VARCHAR(255) NOT NULL,
    release_year INTEGER NOT NULL DEFAULT 0,
    request_count INTEGER NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'READY',
    existing_video_id UUID,
    existing_video_source VARCHAR(32),
    scheduled_task_count INTEGER NOT NULL DEFAULT 0,
    last_error_message VARCHAR(1000),
    CONSTRAINT pk_portal_media_request_batch_items PRIMARY KEY (id),
    CONSTRAINT fk_media_request_batch_items_batch FOREIGN KEY (batch_id) REFERENCES portal_media_request_batches(id) ON DELETE CASCADE,
    CONSTRAINT fk_media_request_batch_items_request FOREIGN KEY (media_request_id) REFERENCES portal_media_requests(id) ON DELETE CASCADE,
    CONSTRAINT uk_media_request_batch_items_request UNIQUE (batch_id, media_request_id)
);

ALTER TABLE portal_media_requests
    ADD COLUMN IF NOT EXISTS current_batch_id UUID;

CREATE INDEX IF NOT EXISTS idx_portal_media_request_batches_status_created
    ON portal_media_request_batches (status, created_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_portal_media_request_batch_items_batch
    ON portal_media_request_batch_items (batch_id, status, id);

CREATE INDEX IF NOT EXISTS idx_portal_media_request_batch_items_request
    ON portal_media_request_batch_items (media_request_id);

CREATE INDEX IF NOT EXISTS idx_portal_media_requests_current_batch
    ON portal_media_requests (current_batch_id);

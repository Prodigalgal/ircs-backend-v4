ALTER TABLE portal_media_requests
    ADD COLUMN IF NOT EXISTS existing_video_id UUID,
    ADD COLUMN IF NOT EXISTS existing_video_source VARCHAR(32);

CREATE INDEX IF NOT EXISTS idx_portal_media_requests_existing_video
    ON portal_media_requests (existing_video_id)
    WHERE existing_video_id IS NOT NULL;

-- liquibase formatted sql

-- changeset antigravity:refactor-video-association-join-table
-- comment: Migrate RawVideo→UnifiedVideo from direct FK to join table for fast TRUNCATE unbinding

-- Step 1: Create the new join table
CREATE TABLE raw_video_unified_video
(
    raw_video_id     UUID NOT NULL,
    unified_video_id UUID NOT NULL,
    CONSTRAINT pk_raw_video_unified_video PRIMARY KEY (raw_video_id, unified_video_id),
    CONSTRAINT fk_rv_uv_raw_video FOREIGN KEY (raw_video_id) REFERENCES raw_videos (id),
    CONSTRAINT fk_rv_uv_unified_video FOREIGN KEY (unified_video_id) REFERENCES unified_videos (id)
);

CREATE INDEX idx_rv_uv_raw_video_id ON raw_video_unified_video (raw_video_id);
CREATE INDEX idx_rv_uv_unified_video_id ON raw_video_unified_video (unified_video_id);

-- Step 2: Migrate existing data from the FK column into the join table
INSERT INTO raw_video_unified_video (raw_video_id, unified_video_id)
SELECT id, unified_video_id
FROM raw_videos
WHERE unified_video_id IS NOT NULL;

-- Step 3: Drop the old FK constraint, index, and column
ALTER TABLE raw_videos DROP CONSTRAINT IF EXISTS fk_raw_videos_on_unified_video;
DROP INDEX IF EXISTS idx_raw_videos_unified_video_id;
ALTER TABLE raw_videos DROP COLUMN IF EXISTS unified_video_id;

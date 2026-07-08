-- liquibase formatted sql

-- changeset zzp84:1781200000000-add-unified-video-content-visibility-column
ALTER TABLE unified_videos
    ADD COLUMN IF NOT EXISTS content_visibility VARCHAR(32) NOT NULL DEFAULT 'PUBLIC';

UPDATE unified_videos
   SET content_visibility = 'PUBLIC'
 WHERE content_visibility IS NULL
    OR trim(content_visibility) = '';

-- changeset zzp84:1781200000001-add-unified-video-content-visibility-constraint splitStatements:false
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'ck_unified_videos_content_visibility'
    ) THEN
        ALTER TABLE unified_videos
            ADD CONSTRAINT ck_unified_videos_content_visibility
            CHECK (content_visibility IN ('PUBLIC', 'MEMBER', 'ADMIN', 'DRAFT', 'HIDDEN'));
    END IF;
END $$;

-- changeset zzp84:1781200000002-add-unified-video-content-visibility-indexes
CREATE INDEX IF NOT EXISTS idx_unified_videos_content_visibility
    ON unified_videos (content_visibility);

CREATE INDEX IF NOT EXISTS idx_unified_videos_category_visibility
    ON unified_videos (category_id, content_visibility);

-- liquibase formatted sql

-- changeset zzp84:1781200000001-add-unified-video-tags
CREATE TABLE IF NOT EXISTS unified_video_tags
(
    unified_video_id UUID NOT NULL,
    tag              VARCHAR(128) NOT NULL,
    CONSTRAINT pk_unified_video_tags PRIMARY KEY (unified_video_id, tag),
    CONSTRAINT fk_unified_video_tags_video FOREIGN KEY (unified_video_id) REFERENCES unified_videos (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_unified_video_tags_tag
    ON unified_video_tags (tag);

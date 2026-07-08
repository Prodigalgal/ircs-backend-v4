-- liquibase formatted sql

-- changeset antigravity:rename-subtitle-to-aliastitle
-- 说明: 将视频实体中的 sub_title (副标题) 重命名为 alias_title (别名标题)，以更准确反映其用途

-- 1. 重命名 raw_videos 表字段
ALTER TABLE raw_videos RENAME COLUMN sub_title TO alias_title;

-- 2. 重命名 unified_videos 表字段
ALTER TABLE unified_videos RENAME COLUMN sub_title TO alias_title;

-- 3. 重建受影响的 GIN 索引 (UnifiedVideo)
DROP INDEX IF EXISTS idx_unified_videos_sub_title_trgm;
CREATE INDEX idx_unified_videos_alias_title_trgm ON unified_videos USING gin (alias_title gin_trgm_ops);
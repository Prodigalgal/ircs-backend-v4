-- liquibase formatted sql

-- changeset zzp84:1772600000000-add-updated-at-indexes
-- 说明: 为增量同步调度器添加索引，避免 WHERE updated_at > ? 查询导致全表扫描

CREATE INDEX IF NOT EXISTS idx_raw_videos_updated_at ON raw_videos (updated_at);

CREATE INDEX IF NOT EXISTS idx_unified_videos_updated_at ON unified_videos (updated_at);
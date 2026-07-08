-- liquibase formatted sql

-- changeset zzp84:1785600000000-add-admin-video-read-indexes
-- 说明: 优化后台内容管理列表页的常用筛选、排序与模糊搜索读路径。

CREATE INDEX IF NOT EXISTS idx_raw_videos_admin_norm_updated
    ON raw_videos (normalization_status, updated_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_raw_videos_admin_enrich_updated
    ON raw_videos (enrichment_status, updated_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_raw_videos_admin_agg_updated
    ON raw_videos (aggregation_status, updated_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_raw_videos_admin_data_source_updated
    ON raw_videos (data_source_id, updated_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_raw_videos_admin_category_updated
    ON raw_videos (category_code, updated_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_raw_videos_title_trgm
    ON raw_videos USING gin (title gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_raw_videos_source_category_trgm
    ON raw_videos USING gin ((coalesce(source_category_name, source_category_code)) gin_trgm_ops);

CREATE INDEX IF NOT EXISTS idx_unified_videos_admin_visibility_updated
    ON unified_videos (content_visibility, updated_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_unified_videos_admin_category_updated
    ON unified_videos (category_code, updated_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_unified_videos_admin_metadata_updated
    ON unified_videos (metadata_status, updated_at DESC, id);

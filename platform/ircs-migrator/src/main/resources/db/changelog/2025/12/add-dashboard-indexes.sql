-- liquibase formatted sql

-- changeset zzp84:1767965000000-dashboard-indexes
-- 优化 Dashboard 统计查询性能

-- 1. 优化按时间维度的趋势统计
CREATE INDEX idx_videos_created_at ON videos (created_at);

-- 2. 优化按状态统计 (normalization_status, enrichment_status)
CREATE INDEX idx_videos_norm_status ON videos (normalization_status);
CREATE INDEX idx_videos_enrich_status ON videos (enrichment_status);

-- 3. 优化分类和来源统计的多表 JOIN
-- videos -> data_source_categories (外键通常已有索引，但显式确认)
CREATE INDEX IF NOT EXISTS idx_videos_ds_cat_id ON videos (data_source_category_id);
CREATE INDEX IF NOT EXISTS idx_videos_data_source_id ON videos (data_source_id);

-- data_source_categories -> categories / data_sources
CREATE INDEX IF NOT EXISTS idx_ds_cats_category_id ON data_source_categories (category_id);
CREATE INDEX IF NOT EXISTS idx_ds_cats_ds_id ON data_source_categories (data_source_id);

-- 4. 优化缺失 ID 的统计 (WHERE douban_id IS NULL ...)
CREATE INDEX idx_videos_douban_id ON videos (douban_id);
CREATE INDEX idx_videos_tmdb_id ON videos (tmdb_id);
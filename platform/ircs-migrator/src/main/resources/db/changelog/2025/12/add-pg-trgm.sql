-- liquibase formatted sql

-- changeset zzp84:1766838896132-add-pg-trgm
-- 启用 pg_trgm 扩展 (用于模糊匹配)
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- 为 unified_videos 表的 title 字段创建 GIN 索引
-- 这对于 similarity() 函数的性能至关重要
CREATE INDEX idx_unified_videos_title_trgm ON unified_videos USING gin (title gin_trgm_ops);
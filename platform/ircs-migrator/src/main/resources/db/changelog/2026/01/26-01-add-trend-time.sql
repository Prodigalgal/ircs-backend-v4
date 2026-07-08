-- liquibase formatted sql

-- changeset zzp84:1770200000000-add-trend-time
-- 说明: 为聚合视频添加最后上榜时间，用于构建动态热门榜单

ALTER TABLE unified_videos ADD COLUMN last_trend_at TIMESTAMP WITHOUT TIME ZONE;

-- 创建索引以支持 ORDER BY last_trend_at DESC 高效查询
CREATE INDEX idx_unified_videos_trend ON unified_videos (last_trend_at DESC NULLS LAST);
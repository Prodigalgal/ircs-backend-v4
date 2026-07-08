-- liquibase formatted sql

-- changeset zzp84:1782000000000-add-lifecycle-statuses
-- 说明: 引入精确生命周期控制字段

-- 1. RawVideo: 聚合状态
ALTER TABLE raw_videos ADD COLUMN aggregation_status VARCHAR(50) NOT NULL DEFAULT 'PENDING';

-- 初始化历史数据: 有 unified_video_id 的视为 BOUND，否则为 PENDING
-- 注意: 这里使用了新的关联表 raw_video_unified_video 进行判断
UPDATE raw_videos rv
SET aggregation_status = 'BOUND'
WHERE EXISTS (SELECT 1 FROM raw_video_unified_video rvuv WHERE rvuv.raw_video_id = rv.id);

CREATE INDEX idx_raw_videos_agg_status ON raw_videos (aggregation_status);


-- 2. UnifiedVideo: 元数据状态
ALTER TABLE unified_videos ADD COLUMN metadata_status VARCHAR(50) NOT NULL DEFAULT 'DIRTY';

-- 历史数据默认设为 SYNCED (避免启动时雪崩，由运维手动触发重置)
UPDATE unified_videos SET metadata_status = 'SYNCED';

CREATE INDEX idx_unified_videos_meta_status ON unified_videos (metadata_status);
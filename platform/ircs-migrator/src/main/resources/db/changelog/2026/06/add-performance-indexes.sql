-- liquibase formatted sql

-- changeset zzp84:1783000000000-add-performance-indexes
-- 说明: 优化聚合调度器的轮询性能，避免 Full Table Scan 和 File Sort

-- 复合索引: 覆盖 findPendingAggregationTasks 的查询条件和排序字段
-- 顺序: 等值查询列 -> 范围/不等值列 -> 排序列
CREATE INDEX IF NOT EXISTS idx_raw_videos_agg_poll
    ON raw_videos (aggregation_status, normalization_status, updated_at)
    WHERE aggregation_status = 'PENDING';

-- 优化元数据丰富轮询
CREATE INDEX IF NOT EXISTS idx_raw_videos_enrich_poll
    ON raw_videos (enrichment_status, normalization_status, updated_at)
    WHERE enrichment_status = 'PENDING';
-- liquibase formatted sql

-- changeset zzp84:1770300000000-refactor-interactions
-- 说明: 将收藏和历史记录从 Video 迁移到 UnifiedVideo，并支持高并发写入

-- ==========================================
-- 1. 重构收藏表 (Member Favorites)
-- ==========================================

-- 移除旧约束
ALTER TABLE member_favorites DROP CONSTRAINT IF EXISTS fk_memfav_on_video;
ALTER TABLE member_favorites DROP CONSTRAINT IF EXISTS uk_fav_member_video;

-- 添加新列
ALTER TABLE member_favorites ADD COLUMN unified_video_id UUID;

-- 数据迁移: 通过 videos 表反查 unified_video_id
UPDATE member_favorites mf
SET unified_video_id = v.unified_video_id
FROM videos v
WHERE mf.video_id = v.id;

-- 清理孤儿数据 (没有关联聚合视频的收藏视为无效)
DELETE FROM member_favorites WHERE unified_video_id IS NULL;

-- 设置非空并添加新约束
ALTER TABLE member_favorites ALTER COLUMN unified_video_id SET NOT NULL;

-- 移除旧列
ALTER TABLE member_favorites DROP COLUMN video_id;

-- 添加新外键 (级联删除) 和唯一约束
ALTER TABLE member_favorites
    ADD CONSTRAINT fk_memfav_on_unified FOREIGN KEY (unified_video_id) REFERENCES unified_videos(id) ON DELETE CASCADE;
ALTER TABLE member_favorites
    ADD CONSTRAINT uk_fav_member_unified UNIQUE (member_id, unified_video_id);


-- ==========================================
-- 2. 重构历史表 (Member Watch Histories)
-- ==========================================

-- 移除旧约束
ALTER TABLE member_watch_histories DROP CONSTRAINT IF EXISTS fk_memhist_on_video;
ALTER TABLE member_watch_histories DROP CONSTRAINT IF EXISTS uk_hist_member_video;

-- 添加新列
ALTER TABLE member_watch_histories ADD COLUMN unified_video_id UUID;
ALTER TABLE member_watch_histories ADD COLUMN episode_name VARCHAR(255);
ALTER TABLE member_watch_histories ADD COLUMN last_video_id UUID;

-- 数据迁移
UPDATE member_watch_histories mwh
SET unified_video_id = v.unified_video_id,
    last_video_id = mwh.video_id, -- 保留原有 video_id 作为 last_video_id
    episode_name = '上次观看'       -- 默认值，后续由播放器更新
FROM videos v
WHERE mwh.video_id = v.id;

-- 清理孤儿数据
DELETE FROM member_watch_histories WHERE unified_video_id IS NULL;

-- 设置非空
ALTER TABLE member_watch_histories ALTER COLUMN unified_video_id SET NOT NULL;
ALTER TABLE member_watch_histories ALTER COLUMN episode_name SET NOT NULL;

-- 移除旧列 (注意: video_id 已迁移至 last_video_id)
ALTER TABLE member_watch_histories DROP COLUMN video_id;

-- 添加新外键
ALTER TABLE member_watch_histories
    ADD CONSTRAINT fk_memhist_on_unified FOREIGN KEY (unified_video_id) REFERENCES unified_videos(id) ON DELETE CASCADE;

-- last_video_id 是弱引用，允许置空 (当源视频被删时)
ALTER TABLE member_watch_histories
    ADD CONSTRAINT fk_memhist_on_last_video FOREIGN KEY (last_video_id) REFERENCES videos(id) ON DELETE SET NULL;

-- 添加新唯一约束
ALTER TABLE member_watch_histories
    ADD CONSTRAINT uk_hist_member_unified UNIQUE (member_id, unified_video_id);

-- 更新索引
DROP INDEX IF EXISTS idx_watch_history_time;
CREATE INDEX idx_watch_history_time ON member_watch_histories (member_id, last_watched_at DESC);
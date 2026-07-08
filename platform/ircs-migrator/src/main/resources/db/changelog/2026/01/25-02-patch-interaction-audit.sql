-- liquibase formatted sql

-- changeset zzp84:1770100000000-patch-interaction-audit
-- 说明：将交互表迁移到 BaseAuditableEntity 标准 (添加 ID, version, created_at, updated_at)

-- ==========================================
-- 1. 迁移 member_favorites
-- ==========================================

-- 添加新列 (允许为空以便填充数据)
ALTER TABLE member_favorites ADD COLUMN id UUID;
ALTER TABLE member_favorites ADD COLUMN updated_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE member_favorites ADD COLUMN version BIGINT DEFAULT 0;

-- 填充数据 (依赖 pgcrypto 生成 UUID)
UPDATE member_favorites
SET id = gen_random_uuid(),
    updated_at = created_at,
    version = 0
WHERE id IS NULL;

-- 设置非空约束
ALTER TABLE member_favorites ALTER COLUMN id SET NOT NULL;
ALTER TABLE member_favorites ALTER COLUMN updated_at SET NOT NULL;

-- 重构主键与约束
ALTER TABLE member_favorites DROP CONSTRAINT pk_member_favorites;
ALTER TABLE member_favorites ADD CONSTRAINT pk_member_favorites PRIMARY KEY (id);
ALTER TABLE member_favorites ADD CONSTRAINT uk_fav_member_video UNIQUE (member_id, video_id);

-- 优化索引 (删除旧的纯时间索引，改用 member_id + created_at)
DROP INDEX IF EXISTS idx_member_favorites_created_at;
CREATE INDEX idx_fav_member_created ON member_favorites (member_id, created_at DESC);


-- ==========================================
-- 2. 迁移 member_watch_histories
-- ==========================================

-- 添加新列
ALTER TABLE member_watch_histories ADD COLUMN id UUID;
ALTER TABLE member_watch_histories ADD COLUMN created_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE member_watch_histories ADD COLUMN updated_at TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE member_watch_histories ADD COLUMN version BIGINT DEFAULT 0;

-- 填充数据
-- 注意：历史表之前没有 created_at，用 last_watched_at 近似填充
UPDATE member_watch_histories
SET id = gen_random_uuid(),
    created_at = last_watched_at,
    updated_at = last_watched_at,
    version = 0
WHERE id IS NULL;

-- 设置非空约束
ALTER TABLE member_watch_histories ALTER COLUMN id SET NOT NULL;
ALTER TABLE member_watch_histories ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE member_watch_histories ALTER COLUMN updated_at SET NOT NULL;

-- 重构主键与约束
ALTER TABLE member_watch_histories DROP CONSTRAINT pk_member_watch_histories;
ALTER TABLE member_watch_histories ADD CONSTRAINT pk_member_watch_histories PRIMARY KEY (id);
ALTER TABLE member_watch_histories ADD CONSTRAINT uk_hist_member_video UNIQUE (member_id, video_id);

-- 索引 idx_watch_history_time (member_id, last_watched_at DESC) 依然有效，保留即可
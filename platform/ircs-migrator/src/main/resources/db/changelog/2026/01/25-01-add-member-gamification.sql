-- liquibase formatted sql

-- changeset zzp84:1769300000000-add-member-gamification
ALTER TABLE members ADD COLUMN experience INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE members ADD COLUMN points INTEGER DEFAULT 0 NOT NULL;
ALTER TABLE members ADD COLUMN last_check_in_date DATE;
ALTER TABLE members ADD COLUMN check_in_streak INTEGER DEFAULT 0 NOT NULL;

-- 优化查询性能
CREATE INDEX idx_members_points ON members(points DESC);
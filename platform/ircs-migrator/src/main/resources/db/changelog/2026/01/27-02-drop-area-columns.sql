-- liquibase formatted sql

-- changeset zzp84:1770550000000-drop-area-columns
-- 说明: 移除视频表中的冗余 area 字符串字段，完全迁移至 raw_areas / standard_areas 关联表

ALTER TABLE videos DROP COLUMN area;
ALTER TABLE unified_videos DROP COLUMN area;
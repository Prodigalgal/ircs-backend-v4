-- liquibase formatted sql

-- changeset zzp84:1768000000000-add-task-timezone
-- 为采集任务增加显式时区字段，默认设为 UTC 以保证向后兼容
ALTER TABLE collection_tasks ADD COLUMN time_zone VARCHAR(50) NOT NULL DEFAULT 'UTC';

-- 数据初始化：将现有任务更新为上海时区（假设现有用户习惯）
UPDATE collection_tasks SET time_zone = 'Asia/Shanghai';
-- liquibase formatted sql

-- changeset zzp84:1784000000000-add-ab-limits
-- 说明: 为 sys_credentials 表引入精确的云存储 A/B 类调用额度限制字段

ALTER TABLE sys_credentials ADD COLUMN class_a_limit BIGINT DEFAULT 0;
ALTER TABLE sys_credentials ADD COLUMN class_b_limit BIGINT DEFAULT 0;
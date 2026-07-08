-- liquibase formatted sql

-- changeset zzp84:1771000000000-add-language-fields
-- 说明: 扩展标准语言表，支持多语言展示

ALTER TABLE standard_languages ADD COLUMN english_name VARCHAR(100);
ALTER TABLE standard_languages ADD COLUMN native_name VARCHAR(100);

-- 优化索引，Code 是核心查找键
CREATE INDEX IF NOT EXISTS idx_std_lang_code ON standard_languages (code);
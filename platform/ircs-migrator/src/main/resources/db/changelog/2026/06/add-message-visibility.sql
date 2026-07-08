-- liquibase formatted sql

-- changeset zzp84:1780000000001-add-message-visibility
ALTER TABLE user_messages ADD COLUMN is_public BOOLEAN NOT NULL DEFAULT FALSE;

-- 创建用于留言墙的高效索引
CREATE INDEX idx_user_msg_public_created ON user_messages (is_public, created_at DESC);
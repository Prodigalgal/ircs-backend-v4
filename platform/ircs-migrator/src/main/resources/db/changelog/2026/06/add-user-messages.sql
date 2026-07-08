-- liquibase formatted sql

-- changeset zzp84:1780000000000-add-user-messages
CREATE TABLE user_messages
(
    id            UUID                        NOT NULL,
    created_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at    TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version       BIGINT,

    member_id     UUID                        NOT NULL,
    content       TEXT                        NOT NULL,
    reply         TEXT,
    status        VARCHAR(50)                 NOT NULL, -- 'PENDING', 'REPLIED', 'CLOSED'
    admin_id      UUID,                                 -- 可选：记录回复的管理员ID

    CONSTRAINT pk_user_messages PRIMARY KEY (id),
    CONSTRAINT fk_usermsg_on_member FOREIGN KEY (member_id) REFERENCES members (id)
);

-- 核心索引：用于用户端查询自己的留言历史，以及每日限额统计
-- 统计当日留言数: WHERE member_id = ? AND created_at >= ?
CREATE INDEX idx_user_msg_member_created ON user_messages (member_id, created_at DESC);

-- 后台管理索引：按状态和时间筛选
CREATE INDEX idx_user_msg_status_created ON user_messages (status, created_at DESC);
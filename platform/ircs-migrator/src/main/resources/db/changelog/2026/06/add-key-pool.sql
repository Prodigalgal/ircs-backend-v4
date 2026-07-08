-- liquibase formatted sql

-- changeset zzp84:1781000000000-add-key-pool-refactored
CREATE TABLE sys_credentials
(
    id              UUID                        NOT NULL,
    created_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version         BIGINT,

    provider        VARCHAR(50)                 NOT NULL,
    name            VARCHAR(100),

    -- 核心凭证数据 (JSONB)
    payload         JSONB                       NOT NULL,

    -- 凭证指纹 (防止重复录入)
    fingerprint     VARCHAR(64),

    enabled         BOOLEAN                     NOT NULL DEFAULT TRUE,
    priority        INTEGER                     DEFAULT 0,

    rate_limit      INTEGER,
    rate_limit_unit VARCHAR(20),
    day_limit       BIGINT                      DEFAULT 0,
    month_limit     BIGINT                      DEFAULT 0,
    remark          VARCHAR(500),

    CONSTRAINT pk_sys_credentials PRIMARY KEY (id)
);

CREATE INDEX idx_sys_creds_provider ON sys_credentials (provider);
CREATE INDEX idx_sys_creds_fingerprint ON sys_credentials (fingerprint);
CREATE INDEX idx_sys_creds_status ON sys_credentials (enabled);
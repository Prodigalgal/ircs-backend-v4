--liquibase formatted sql

--changeset prodigalgal:20260619-add-admin-api-tokens
CREATE TABLE IF NOT EXISTS admin_api_tokens (
    id UUID NOT NULL,
    created_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    name VARCHAR(120) NOT NULL,
    token_prefix VARCHAR(32) NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_by VARCHAR(128),
    last_used_at TIMESTAMP WITHOUT TIME ZONE,
    revoked_at TIMESTAMP WITHOUT TIME ZONE,
    revoked_by VARCHAR(128),
    expires_at TIMESTAMP WITHOUT TIME ZONE,
    CONSTRAINT pk_admin_api_tokens PRIMARY KEY (id),
    CONSTRAINT ck_admin_api_tokens_status CHECK (status IN ('ACTIVE', 'REVOKED'))
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_admin_api_tokens_hash
    ON admin_api_tokens (token_hash);

CREATE UNIQUE INDEX IF NOT EXISTS ux_admin_api_tokens_prefix
    ON admin_api_tokens (token_prefix);

CREATE INDEX IF NOT EXISTS idx_admin_api_tokens_status_created
    ON admin_api_tokens (status, created_at DESC);

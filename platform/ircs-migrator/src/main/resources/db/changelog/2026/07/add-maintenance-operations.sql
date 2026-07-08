--liquibase formatted sql

--changeset codex:20260611-add-maintenance-operations
CREATE TABLE IF NOT EXISTS maintenance_operations (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    operation_key VARCHAR(128) NOT NULL,
    owner_service VARCHAR(128) NOT NULL,
    resource_type VARCHAR(128) NOT NULL,
    resource_scope VARCHAR(256) NOT NULL DEFAULT '*',
    mode VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    reason VARCHAR(512),
    requested_by VARCHAR(128),
    correlation_id VARCHAR(128),
    expires_at TIMESTAMPTZ NOT NULL,
    closed_at TIMESTAMPTZ,
    close_reason VARCHAR(512)
);

CREATE INDEX IF NOT EXISTS idx_maintenance_operations_active_scope
    ON maintenance_operations (owner_service, resource_type, resource_scope, status, expires_at);

CREATE INDEX IF NOT EXISTS idx_maintenance_operations_status_expires
    ON maintenance_operations (status, expires_at, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_maintenance_operations_correlation
    ON maintenance_operations (correlation_id);

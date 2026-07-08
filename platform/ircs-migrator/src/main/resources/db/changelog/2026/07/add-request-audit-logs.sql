--liquibase formatted sql

--changeset prodigalgal:20260602-add-request-audit-logs
CREATE TABLE IF NOT EXISTS request_audit_logs (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT,
    username VARCHAR(128),
    method VARCHAR(16) NOT NULL,
    path VARCHAR(1024) NOT NULL,
    query_string TEXT,
    status_code INTEGER NOT NULL,
    success BOOLEAN NOT NULL,
    duration_ms BIGINT NOT NULL,
    client_ip VARCHAR(128),
    user_agent TEXT,
    trace_id VARCHAR(128),
    error_message TEXT
);

CREATE INDEX IF NOT EXISTS idx_req_audit_created ON request_audit_logs (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_req_audit_user ON request_audit_logs (username);
CREATE INDEX IF NOT EXISTS idx_req_audit_method_path ON request_audit_logs (method, path);
CREATE INDEX IF NOT EXISTS idx_req_audit_status ON request_audit_logs (status_code);
CREATE INDEX IF NOT EXISTS idx_req_audit_ip ON request_audit_logs (client_ip);

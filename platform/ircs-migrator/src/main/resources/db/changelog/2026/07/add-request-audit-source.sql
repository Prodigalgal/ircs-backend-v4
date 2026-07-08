--liquibase formatted sql

--changeset prodigalgal:20260607-add-request-audit-source
ALTER TABLE request_audit_logs
    ADD COLUMN IF NOT EXISTS request_source VARCHAR(128);

CREATE INDEX IF NOT EXISTS idx_req_audit_source ON request_audit_logs (request_source);

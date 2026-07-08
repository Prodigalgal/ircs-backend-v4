--liquibase formatted sql

--changeset codex:20260620-add-ops-audit-read-indexes
CREATE INDEX IF NOT EXISTS idx_worker_job_audit_created_summary
    ON worker_job_audit_events (created_at DESC, status, duration_ms);

CREATE INDEX IF NOT EXISTS idx_req_audit_created_summary
    ON request_audit_logs (created_at DESC, status_code, duration_ms);

CREATE INDEX IF NOT EXISTS idx_failed_msg_created_id
    ON failed_messages (created_at DESC, id DESC);

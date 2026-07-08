--liquibase formatted sql

--changeset prodigalgal:20260701-add-ops-alert-self-healing
CREATE TABLE IF NOT EXISTS ops_alert_events (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    observed_at TIMESTAMPTZ NOT NULL,
    source VARCHAR(128) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    resource_type VARCHAR(128) NOT NULL,
    resource_name VARCHAR(256) NOT NULL,
    fingerprint VARCHAR(256) NOT NULL,
    summary VARCHAR(512) NOT NULL,
    details_json TEXT
);

CREATE TABLE IF NOT EXISTS ops_incidents (
    id UUID PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    version BIGINT NOT NULL DEFAULT 0,
    fingerprint VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    title VARCHAR(512) NOT NULL,
    source VARCHAR(128) NOT NULL,
    resource_type VARCHAR(128) NOT NULL,
    resource_name VARCHAR(256) NOT NULL,
    first_seen_at TIMESTAMPTZ NOT NULL,
    last_seen_at TIMESTAMPTZ NOT NULL,
    recovered_at TIMESTAMPTZ,
    occurrence_count BIGINT NOT NULL DEFAULT 1,
    last_reason TEXT,
    last_event_id UUID
);

CREATE TABLE IF NOT EXISTS ops_healing_actions (
    id UUID PRIMARY KEY,
    incident_id UUID NOT NULL REFERENCES ops_incidents(id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    policy_key VARCHAR(128) NOT NULL,
    playbook_key VARCHAR(128) NOT NULL,
    dry_run BOOLEAN NOT NULL DEFAULT true,
    status VARCHAR(32) NOT NULL,
    request_payload TEXT,
    result_payload TEXT,
    started_at TIMESTAMPTZ,
    finished_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_ops_alert_events_created ON ops_alert_events (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ops_alert_events_fingerprint_created ON ops_alert_events (fingerprint, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ops_alert_events_severity_created ON ops_alert_events (severity, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ops_incidents_status_last_seen ON ops_incidents (status, last_seen_at DESC);
CREATE INDEX IF NOT EXISTS idx_ops_incidents_fingerprint_status ON ops_incidents (fingerprint, status);
CREATE INDEX IF NOT EXISTS idx_ops_incidents_severity_last_seen ON ops_incidents (severity, last_seen_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS ux_ops_incidents_open_fingerprint
    ON ops_incidents (fingerprint)
    WHERE status <> 'RECOVERED';
CREATE INDEX IF NOT EXISTS idx_ops_healing_actions_incident_created ON ops_healing_actions (incident_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ops_healing_actions_status_created ON ops_healing_actions (status, created_at DESC);

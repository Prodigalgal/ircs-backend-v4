--liquibase formatted sql

--changeset ircs:2026-07-add-data-source-network-options
ALTER TABLE data_sources
    ADD COLUMN IF NOT EXISTS transport_mode varchar(20) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN IF NOT EXISTS http_protocol varchar(20) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN IF NOT EXISTS ip_version_policy varchar(20) NOT NULL DEFAULT 'AUTO',
    ADD COLUMN IF NOT EXISTS dns_resolver_type varchar(20) NOT NULL DEFAULT 'SYSTEM',
    ADD COLUMN IF NOT EXISTS dns_resolver_endpoint varchar(512),
    ADD COLUMN IF NOT EXISTS connect_timeout_ms integer NOT NULL DEFAULT 10000,
    ADD COLUMN IF NOT EXISTS read_timeout_ms integer NOT NULL DEFAULT 10000,
    ADD COLUMN IF NOT EXISTS user_agent varchar(512);

-- liquibase formatted sql

-- changeset codex:20260610-versioned-config-credential-governance
UPDATE system_configs
   SET version = 1
 WHERE version IS NULL
    OR version < 1;

ALTER TABLE system_configs
    ALTER COLUMN version SET DEFAULT 1;

ALTER TABLE system_configs
    ALTER COLUMN version SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_system_configs_key_version
    ON system_configs (config_key, version);

UPDATE sys_credentials
   SET version = 1
 WHERE version IS NULL
    OR version < 1;

ALTER TABLE sys_credentials
    ALTER COLUMN version SET DEFAULT 1;

ALTER TABLE sys_credentials
    ALTER COLUMN version SET NOT NULL;

CREATE INDEX IF NOT EXISTS idx_sys_credentials_provider_enabled_version
    ON sys_credentials (provider, enabled, version);

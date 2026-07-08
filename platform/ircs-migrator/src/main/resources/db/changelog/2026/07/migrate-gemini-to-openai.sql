-- liquibase formatted sql

-- changeset zzp84:1781000000001-migrate-gemini-to-openai
UPDATE sys_credentials
SET provider = 'OPENAI',
    payload = CASE
                  WHEN jsonb_exists(payload, 'base_url') THEN payload
                  ELSE payload || '{"base_url":"https://ai.mnnu.eu.org/v1"}'::jsonb
              END
WHERE provider = 'GEMINI';

-- changeset zzp84:1781000000002-upgrade-llm-system-configs
UPDATE system_configs
SET config_value = 'gemma-4-31b-it'
WHERE config_key = 'app.ai.llm.model'
  AND config_value IN ('gemini-2.5-flash-lite', 'gpt-4o-mini');

ALTER TABLE system_configs
    ALTER COLUMN config_value TYPE TEXT;

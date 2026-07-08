-- liquibase formatted sql

-- changeset zzp84:1781100000000-extend-search-outbox-runtime
ALTER TABLE search_sync_tasks
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS source_service VARCHAR(128),
    ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(128),
    ADD COLUMN IF NOT EXISTS locked_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS locked_until TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS last_attempt_at TIMESTAMP WITHOUT TIME ZONE,
    ADD COLUMN IF NOT EXISTS last_error TEXT;

UPDATE search_sync_tasks
   SET updated_at = COALESCE(updated_at, created_at, NOW())
 WHERE updated_at IS NULL;

CREATE INDEX IF NOT EXISTS idx_search_sync_processing_lease
    ON search_sync_tasks (status, locked_until)
    WHERE status = 'PROCESSING';

CREATE INDEX IF NOT EXISTS idx_search_sync_entity_status
    ON search_sync_tasks (entity_type, entity_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_search_sync_dead_letter_updated
    ON search_sync_tasks (updated_at)
    WHERE status = 'DEAD_LETTER';

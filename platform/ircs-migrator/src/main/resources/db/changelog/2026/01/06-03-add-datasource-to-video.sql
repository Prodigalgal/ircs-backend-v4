-- liquibase formatted sql

-- changeset zzp84:1767710000000-1
ALTER TABLE videos
    ADD data_source_id UUID;

-- changeset zzp84:1767710000000-2
ALTER TABLE videos
    ADD CONSTRAINT FK_VIDEOS_ON_DATA_SOURCE FOREIGN KEY (data_source_id) REFERENCES data_sources (id);

-- changeset zzp84:1767710000000-3
-- Data Migration: Backfill from existing categories where possible
UPDATE videos v
SET data_source_id = dsc.data_source_id
FROM data_source_categories dsc
WHERE v.data_source_category_id = dsc.id;

-- changeset zzp84:1767710000000-4
-- Data Migration: Backfill from raw_metadata (JSONB) as fallback
-- Note: This assumes raw_metadata contains "dataSourceId" key.
-- PostgreSQL JSONB syntax used here.
UPDATE videos
SET data_source_id = (raw_metadata ->> 'dataSourceId')::uuid
WHERE data_source_id IS NULL
  AND raw_metadata IS NOT NULL
  AND (raw_metadata ->> 'dataSourceId') IS NOT NULL
  -- Ensure it's a valid UUID format roughly
  AND length(raw_metadata ->> 'dataSourceId') = 36;
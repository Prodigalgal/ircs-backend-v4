-- liquibase formatted sql

-- changeset codex:20260702-add-adult-content-assessment splitStatements:false
-- comment: Add adult-restriction source metadata and explainable unified-video assessment evidence.

ALTER TABLE data_sources
    ADD COLUMN IF NOT EXISTS adult_restricted BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE unified_videos
    ADD COLUMN IF NOT EXISTS adult_restricted BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS adult_assessment JSONB NOT NULL DEFAULT '{}'::jsonb,
    ADD COLUMN IF NOT EXISTS adult_checked_at TIMESTAMP WITHOUT TIME ZONE;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
          FROM pg_constraint
         WHERE conname = 'chk_unified_videos_adult_assessment_object'
    ) THEN
        ALTER TABLE unified_videos
            ADD CONSTRAINT chk_unified_videos_adult_assessment_object
            CHECK (jsonb_typeof(adult_assessment) = 'object');
    END IF;
END $$;

UPDATE data_sources
   SET adult_restricted = TRUE,
       updated_at = now()
 WHERE adult_restricted = FALSE
   AND (
       lower(coalesce(name, '')) ~ '(adult|jav|fc2|heyzo|1pondo|caribbean|tokyo[- ]?hot|pacopacom)'
       OR coalesce(name, '') ~ '(成人|情色|伦理|无码|有码|女优|麻豆|国产自拍|东京热|加勒比|一本道)'
       OR lower(coalesce(base_url, '')) ~ '(adult|jav|fc2|heyzo|1pondo|caribbean|tokyo[- ]?hot|pacopacom)'
   );

UPDATE unified_videos
   SET adult_restricted = TRUE,
       adult_assessment = jsonb_build_object(
           'ruleVersion', 'adult-assessment-bootstrap-v1',
           'level', 'ADULT',
           'adultRestricted', TRUE,
           'confidence', 96,
           'signals', jsonb_build_array(jsonb_build_object(
               'source', 'migration',
               'field', 'categoryCode',
               'matchedValue', 'adult',
               'score', 96,
               'reason', '历史标准分类已归为成人'
           ))
       ),
       updated_at = now()
 WHERE lower(coalesce(category_code, '')) = 'adult'
   AND adult_restricted = FALSE;

CREATE INDEX IF NOT EXISTS idx_data_sources_adult_restricted
    ON data_sources (adult_restricted);

CREATE INDEX IF NOT EXISTS idx_unified_videos_adult_restricted_updated
    ON unified_videos (adult_restricted, updated_at DESC, id);

CREATE INDEX IF NOT EXISTS idx_unified_videos_adult_checked_at
    ON unified_videos (adult_checked_at, updated_at DESC, id);

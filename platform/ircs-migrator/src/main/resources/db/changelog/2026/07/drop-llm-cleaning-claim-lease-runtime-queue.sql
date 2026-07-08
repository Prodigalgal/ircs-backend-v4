-- liquibase formatted sql

-- changeset prodigalgal:20260617-drop-llm-cleaning-claim-lease-runtime-queue
DROP INDEX IF EXISTS idx_raw_genres_llm_cleaning_claim;
DROP INDEX IF EXISTS idx_raw_languages_llm_cleaning_claim;
DROP INDEX IF EXISTS idx_raw_areas_llm_cleaning_claim;
DROP INDEX IF EXISTS idx_raw_category_llm_cleaning_claim;

ALTER TABLE raw_genres
    DROP COLUMN IF EXISTS llm_cleaning_claimed_by,
    DROP COLUMN IF EXISTS llm_cleaning_claimed_until;

ALTER TABLE raw_languages
    DROP COLUMN IF EXISTS llm_cleaning_claimed_by,
    DROP COLUMN IF EXISTS llm_cleaning_claimed_until;

ALTER TABLE raw_areas
    DROP COLUMN IF EXISTS llm_cleaning_claimed_by,
    DROP COLUMN IF EXISTS llm_cleaning_claimed_until;

ALTER TABLE raw_category
    DROP COLUMN IF EXISTS llm_cleaning_claimed_by,
    DROP COLUMN IF EXISTS llm_cleaning_claimed_until;

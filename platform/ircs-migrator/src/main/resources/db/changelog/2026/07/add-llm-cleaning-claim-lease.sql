--liquibase formatted sql

--changeset prodigalgal:20260611-add-llm-cleaning-claim-lease
ALTER TABLE raw_genres
    ADD COLUMN IF NOT EXISTS llm_cleaning_claimed_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS llm_cleaning_claimed_until TIMESTAMPTZ;

ALTER TABLE raw_languages
    ADD COLUMN IF NOT EXISTS llm_cleaning_claimed_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS llm_cleaning_claimed_until TIMESTAMPTZ;

ALTER TABLE raw_areas
    ADD COLUMN IF NOT EXISTS llm_cleaning_claimed_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS llm_cleaning_claimed_until TIMESTAMPTZ;

ALTER TABLE raw_category
    ADD COLUMN IF NOT EXISTS llm_cleaning_claimed_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS llm_cleaning_claimed_until TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_raw_genres_llm_claim
    ON raw_genres (standard_genre_id, llm_cleaning_claimed_until, updated_at);

CREATE INDEX IF NOT EXISTS idx_raw_languages_llm_claim
    ON raw_languages (standard_language_id, llm_cleaning_claimed_until, updated_at);

CREATE INDEX IF NOT EXISTS idx_raw_areas_llm_claim
    ON raw_areas (standard_area_id, llm_cleaning_claimed_until, updated_at);

CREATE INDEX IF NOT EXISTS idx_raw_category_llm_claim
    ON raw_category (category_id, llm_cleaning_claimed_until, updated_at);

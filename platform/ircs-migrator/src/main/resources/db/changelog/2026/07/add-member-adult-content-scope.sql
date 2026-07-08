-- liquibase formatted sql

-- changeset codex:20260627-add-member-adult-content-scope
ALTER TABLE members
    ADD COLUMN IF NOT EXISTS adult_content_allowed BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_members_adult_content_allowed
    ON members (adult_content_allowed);

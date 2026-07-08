--liquibase formatted sql

--changeset prodigalgal:20260611-add-avatar-sync-claim-lease
ALTER TABLE members
    ADD COLUMN IF NOT EXISTS avatar_sync_claimed_by VARCHAR(128),
    ADD COLUMN IF NOT EXISTS avatar_sync_claimed_until TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_members_avatar_sync_claim
    ON members (avatar_sync_claimed_until, updated_at)
    WHERE avatar_url IS NOT NULL;

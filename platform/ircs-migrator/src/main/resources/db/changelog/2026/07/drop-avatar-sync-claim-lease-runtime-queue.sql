-- liquibase formatted sql

-- changeset prodigalgal:20260617-drop-avatar-sync-claim-lease-runtime-queue
DROP INDEX IF EXISTS idx_members_avatar_sync_claim;

ALTER TABLE members
    DROP COLUMN IF EXISTS avatar_sync_claimed_by,
    DROP COLUMN IF EXISTS avatar_sync_claimed_until;

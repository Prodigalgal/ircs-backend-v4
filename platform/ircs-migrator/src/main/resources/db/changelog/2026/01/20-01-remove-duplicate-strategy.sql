-- liquibase formatted sql

-- changeset zzp84:1768800000000-remove-duplicate-strategy
ALTER TABLE collection_tasks DROP COLUMN duplicate_strategy;
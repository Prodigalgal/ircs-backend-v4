-- liquibase formatted sql

-- changeset zzp84:1767703598357-1
ALTER TABLE videos
    ADD next_normalization_retry_time TIMESTAMP WITHOUT TIME ZONE;
ALTER TABLE videos
    ADD normalization_retry_count INTEGER;


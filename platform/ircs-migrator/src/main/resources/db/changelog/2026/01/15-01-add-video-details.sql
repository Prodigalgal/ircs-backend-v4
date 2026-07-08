-- liquibase formatted sql

-- changeset zzp84:1768460000000-add-video-details
ALTER TABLE videos ADD COLUMN sub_title VARCHAR(255);
ALTER TABLE videos ADD COLUMN total_episodes VARCHAR(50);
ALTER TABLE videos ADD COLUMN duration VARCHAR(50);
-- liquibase formatted sql

-- changeset zzp84:1769241600000-1
ALTER TABLE categories RENAME TO standard_category;

-- changeset zzp84:1769241600000-2
ALTER TABLE data_source_categories RENAME TO raw_category;

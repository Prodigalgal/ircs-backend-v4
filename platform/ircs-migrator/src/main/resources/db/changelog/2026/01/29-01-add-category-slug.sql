-- liquibase formatted sql

-- changeset zzp84:1772000000000-add-category-slug
ALTER TABLE categories ADD COLUMN slug VARCHAR(100);

-- Data Migration: Backfill slugs for known categories
UPDATE categories SET slug = 'movie' WHERE name = '电影';
UPDATE categories SET slug = 'series' WHERE name = '电视剧';
UPDATE categories SET slug = 'variety' WHERE name = '综艺';
UPDATE categories SET slug = 'anime' WHERE name = '动漫';
UPDATE categories SET slug = 'short-drama' WHERE name = '短剧';
UPDATE categories SET slug = 'documentary' WHERE name = '纪录片';
UPDATE categories SET slug = 'sports' WHERE name = '体育';
UPDATE categories SET slug = 'other' WHERE name = '其他';

-- Fallback for any unknown categories (use ID or random string to avoid unique constraint violation if any)
UPDATE categories SET slug = 'cat-' || substr(md5(random()::text), 1, 6) WHERE slug IS NULL;

-- Apply constraints
ALTER TABLE categories ALTER COLUMN slug SET NOT NULL;
ALTER TABLE categories ADD CONSTRAINT uc_categories_slug UNIQUE (slug);
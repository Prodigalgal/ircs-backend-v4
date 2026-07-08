-- liquibase formatted sql

-- changeset codex:20260623-flatten-content-metadata-code-model
-- comment: Add denormalized content metadata fields for code-based read paths while keeping legacy relation tables for rollback.

ALTER TABLE standard_genre
    ADD COLUMN IF NOT EXISTS code VARCHAR(100);

UPDATE standard_genre sg
   SET code = base.generated_code
  FROM (
        SELECT id,
               CASE
                   WHEN nullif(lower(regexp_replace(name, '[^a-zA-Z0-9]+', '-', 'g')), '') IS NOT NULL
                       THEN lower(regexp_replace(name, '[^a-zA-Z0-9]+', '-', 'g'))
                   ELSE 'genre-' || substr(md5(name), 1, 12)
               END AS generated_code
          FROM standard_genre
         WHERE code IS NULL OR trim(code) = ''
       ) base
 WHERE sg.id = base.id;

UPDATE standard_genre sg
   SET code = sg.code || '-' || substr(md5(cast(sg.id AS varchar)), 1, 8)
  FROM (
        SELECT code
          FROM standard_genre
         GROUP BY code
        HAVING count(*) > 1
       ) duplicate_codes
 WHERE sg.code = duplicate_codes.code;

ALTER TABLE standard_genre
    ALTER COLUMN code SET NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS ux_standard_genre_code
    ON standard_genre (code);

ALTER TABLE raw_videos
    ADD COLUMN IF NOT EXISTS actor_names JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS director_names JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS area_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS language_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS genre_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS category_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_category_code VARCHAR(100),
    ADD COLUMN IF NOT EXISTS source_category_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS normalization_snapshot JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE unified_videos
    ADD COLUMN IF NOT EXISTS actor_names JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS director_names JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS area_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS language_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS genre_codes JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS category_code VARCHAR(100);

UPDATE raw_videos rv
   SET actor_names = coalesce((
           SELECT jsonb_agg(name ORDER BY name)
             FROM (
                    SELECT DISTINCT a.name
                      FROM video_actors va
                      JOIN actors a ON a.id = va.actor_id
                     WHERE va.video_id = rv.id
                       AND nullif(trim(a.name), '') IS NOT NULL
                  ) names
       ), '[]'::jsonb),
       director_names = coalesce((
           SELECT jsonb_agg(name ORDER BY name)
             FROM (
                    SELECT DISTINCT d.name
                      FROM video_directors vd
                      JOIN directors d ON d.id = vd.director_id
                     WHERE vd.video_id = rv.id
                       AND nullif(trim(d.name), '') IS NOT NULL
                  ) names
       ), '[]'::jsonb),
       area_codes = coalesce((
           SELECT jsonb_agg(code ORDER BY code)
             FROM (
                    SELECT DISTINCT sa.code
                      FROM video_raw_areas vra
                      JOIN raw_areas ra ON ra.id = vra.raw_area_id
                      JOIN standard_areas sa ON sa.id = ra.standard_area_id
                     WHERE vra.video_id = rv.id
                       AND nullif(trim(sa.code), '') IS NOT NULL
                  ) codes
       ), '[]'::jsonb),
       language_codes = coalesce((
           SELECT jsonb_agg(code ORDER BY code)
             FROM (
                    SELECT DISTINCT sl.code
                      FROM video_raw_languages vrl
                      JOIN raw_languages rl ON rl.id = vrl.raw_language_id
                      JOIN standard_languages sl ON sl.id = rl.standard_language_id
                     WHERE vrl.video_id = rv.id
                       AND nullif(trim(sl.code), '') IS NOT NULL
                  ) codes
       ), '[]'::jsonb),
       genre_codes = coalesce((
           SELECT jsonb_agg(code ORDER BY code)
             FROM (
                    SELECT DISTINCT sg.code
                      FROM video_raw_genres vrg
                      JOIN raw_genres rg ON rg.id = vrg.raw_genre_id
                      JOIN standard_genre sg ON sg.id = rg.standard_genre_id
                     WHERE vrg.video_id = rv.id
                       AND nullif(trim(sg.code), '') IS NOT NULL
                  ) codes
       ), '[]'::jsonb),
       category_code = sc.slug,
       source_category_code = rc.source_code,
       source_category_name = rc.source_name,
       normalization_snapshot = jsonb_build_object(
               'legacyBackfill', true,
               'sourceCategoryCode', rc.source_code,
               'sourceCategoryName', rc.source_name
       )
  FROM raw_category rc
  LEFT JOIN standard_category sc ON sc.id = rc.category_id
 WHERE rv.data_source_category_id = rc.id;

UPDATE raw_videos rv
   SET actor_names = coalesce((
           SELECT jsonb_agg(name ORDER BY name)
             FROM (
                    SELECT DISTINCT a.name
                      FROM video_actors va
                      JOIN actors a ON a.id = va.actor_id
                     WHERE va.video_id = rv.id
                       AND nullif(trim(a.name), '') IS NOT NULL
                  ) names
       ), '[]'::jsonb),
       director_names = coalesce((
           SELECT jsonb_agg(name ORDER BY name)
             FROM (
                    SELECT DISTINCT d.name
                      FROM video_directors vd
                      JOIN directors d ON d.id = vd.director_id
                     WHERE vd.video_id = rv.id
                       AND nullif(trim(d.name), '') IS NOT NULL
                  ) names
       ), '[]'::jsonb),
       area_codes = coalesce((
           SELECT jsonb_agg(code ORDER BY code)
             FROM (
                    SELECT DISTINCT sa.code
                      FROM video_raw_areas vra
                      JOIN raw_areas ra ON ra.id = vra.raw_area_id
                      JOIN standard_areas sa ON sa.id = ra.standard_area_id
                     WHERE vra.video_id = rv.id
                       AND nullif(trim(sa.code), '') IS NOT NULL
                  ) codes
       ), '[]'::jsonb),
       language_codes = coalesce((
           SELECT jsonb_agg(code ORDER BY code)
             FROM (
                    SELECT DISTINCT sl.code
                      FROM video_raw_languages vrl
                      JOIN raw_languages rl ON rl.id = vrl.raw_language_id
                      JOIN standard_languages sl ON sl.id = rl.standard_language_id
                     WHERE vrl.video_id = rv.id
                       AND nullif(trim(sl.code), '') IS NOT NULL
                  ) codes
       ), '[]'::jsonb),
       genre_codes = coalesce((
           SELECT jsonb_agg(code ORDER BY code)
             FROM (
                    SELECT DISTINCT sg.code
                      FROM video_raw_genres vrg
                      JOIN raw_genres rg ON rg.id = vrg.raw_genre_id
                      JOIN standard_genre sg ON sg.id = rg.standard_genre_id
                     WHERE vrg.video_id = rv.id
                       AND nullif(trim(sg.code), '') IS NOT NULL
                  ) codes
       ), '[]'::jsonb)
 WHERE rv.data_source_category_id IS NULL;

UPDATE unified_videos uv
   SET actor_names = coalesce((
           SELECT jsonb_agg(name ORDER BY name)
             FROM (
                    SELECT DISTINCT a.name
                      FROM unified_video_actors uva
                      JOIN actors a ON a.id = uva.actor_id
                     WHERE uva.unified_video_id = uv.id
                       AND nullif(trim(a.name), '') IS NOT NULL
                  ) names
       ), '[]'::jsonb),
       director_names = coalesce((
           SELECT jsonb_agg(name ORDER BY name)
             FROM (
                    SELECT DISTINCT d.name
                      FROM unified_video_directors uvd
                      JOIN directors d ON d.id = uvd.director_id
                     WHERE uvd.unified_video_id = uv.id
                       AND nullif(trim(d.name), '') IS NOT NULL
                  ) names
       ), '[]'::jsonb),
       area_codes = coalesce((
           SELECT jsonb_agg(code ORDER BY code)
             FROM (
                    SELECT DISTINCT sa.code
                      FROM unified_video_standard_areas uvsa
                      JOIN standard_areas sa ON sa.id = uvsa.standard_area_id
                     WHERE uvsa.unified_video_id = uv.id
                       AND nullif(trim(sa.code), '') IS NOT NULL
                  ) codes
       ), '[]'::jsonb),
       language_codes = coalesce((
           SELECT jsonb_agg(code ORDER BY code)
             FROM (
                    SELECT DISTINCT sl.code
                      FROM unified_video_standard_languages uvsl
                      JOIN standard_languages sl ON sl.id = uvsl.standard_language_id
                     WHERE uvsl.unified_video_id = uv.id
                       AND nullif(trim(sl.code), '') IS NOT NULL
                  ) codes
       ), '[]'::jsonb),
       genre_codes = coalesce((
           SELECT jsonb_agg(code ORDER BY code)
             FROM (
                    SELECT DISTINCT sg.code
                      FROM unified_video_genres uvg
                      JOIN standard_genre sg ON sg.id = uvg.genre_id
                     WHERE uvg.unified_video_id = uv.id
                       AND nullif(trim(sg.code), '') IS NOT NULL
                  ) codes
       ), '[]'::jsonb),
       category_code = sc.slug
  FROM standard_category sc
 WHERE uv.category_id = sc.id;

ALTER TABLE raw_videos
    ADD CONSTRAINT chk_raw_videos_actor_names_array CHECK (jsonb_typeof(actor_names) = 'array'),
    ADD CONSTRAINT chk_raw_videos_director_names_array CHECK (jsonb_typeof(director_names) = 'array'),
    ADD CONSTRAINT chk_raw_videos_area_codes_array CHECK (jsonb_typeof(area_codes) = 'array'),
    ADD CONSTRAINT chk_raw_videos_language_codes_array CHECK (jsonb_typeof(language_codes) = 'array'),
    ADD CONSTRAINT chk_raw_videos_genre_codes_array CHECK (jsonb_typeof(genre_codes) = 'array'),
    ADD CONSTRAINT chk_raw_videos_normalization_snapshot_object CHECK (jsonb_typeof(normalization_snapshot) = 'object');

ALTER TABLE unified_videos
    ADD CONSTRAINT chk_unified_videos_actor_names_array CHECK (jsonb_typeof(actor_names) = 'array'),
    ADD CONSTRAINT chk_unified_videos_director_names_array CHECK (jsonb_typeof(director_names) = 'array'),
    ADD CONSTRAINT chk_unified_videos_area_codes_array CHECK (jsonb_typeof(area_codes) = 'array'),
    ADD CONSTRAINT chk_unified_videos_language_codes_array CHECK (jsonb_typeof(language_codes) = 'array'),
    ADD CONSTRAINT chk_unified_videos_genre_codes_array CHECK (jsonb_typeof(genre_codes) = 'array');

CREATE INDEX IF NOT EXISTS idx_raw_videos_category_code
    ON raw_videos (category_code);
CREATE INDEX IF NOT EXISTS idx_raw_videos_source_category_code
    ON raw_videos (source_category_code);
CREATE INDEX IF NOT EXISTS idx_unified_videos_category_code
    ON unified_videos (category_code);
CREATE INDEX IF NOT EXISTS idx_raw_videos_area_codes_gin
    ON raw_videos USING GIN (area_codes);
CREATE INDEX IF NOT EXISTS idx_raw_videos_language_codes_gin
    ON raw_videos USING GIN (language_codes);
CREATE INDEX IF NOT EXISTS idx_raw_videos_genre_codes_gin
    ON raw_videos USING GIN (genre_codes);
CREATE INDEX IF NOT EXISTS idx_unified_videos_area_codes_gin
    ON unified_videos USING GIN (area_codes);
CREATE INDEX IF NOT EXISTS idx_unified_videos_language_codes_gin
    ON unified_videos USING GIN (language_codes);
CREATE INDEX IF NOT EXISTS idx_unified_videos_genre_codes_gin
    ON unified_videos USING GIN (genre_codes);

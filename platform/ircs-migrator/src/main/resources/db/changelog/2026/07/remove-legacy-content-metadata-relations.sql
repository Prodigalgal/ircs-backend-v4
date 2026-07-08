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
       ), actor_names, '[]'::jsonb),
       director_names = coalesce((
           SELECT jsonb_agg(name ORDER BY name)
             FROM (
                    SELECT DISTINCT d.name
                      FROM video_directors vd
                      JOIN directors d ON d.id = vd.director_id
                     WHERE vd.video_id = rv.id
                       AND nullif(trim(d.name), '') IS NOT NULL
                  ) names
       ), director_names, '[]'::jsonb),
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
       ), area_codes, '[]'::jsonb),
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
       ), language_codes, '[]'::jsonb),
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
       ), genre_codes, '[]'::jsonb)
 WHERE EXISTS (SELECT 1 FROM raw_videos existing WHERE existing.id = rv.id);

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
       ), actor_names, '[]'::jsonb),
       director_names = coalesce((
           SELECT jsonb_agg(name ORDER BY name)
             FROM (
                    SELECT DISTINCT d.name
                      FROM unified_video_directors uvd
                      JOIN directors d ON d.id = uvd.director_id
                     WHERE uvd.unified_video_id = uv.id
                       AND nullif(trim(d.name), '') IS NOT NULL
                  ) names
       ), director_names, '[]'::jsonb),
       area_codes = coalesce((
           SELECT jsonb_agg(code ORDER BY code)
             FROM (
                    SELECT DISTINCT sa.code
                      FROM unified_video_standard_areas uvsa
                      JOIN standard_areas sa ON sa.id = uvsa.standard_area_id
                     WHERE uvsa.unified_video_id = uv.id
                       AND nullif(trim(sa.code), '') IS NOT NULL
                  ) codes
       ), area_codes, '[]'::jsonb),
       language_codes = coalesce((
           SELECT jsonb_agg(code ORDER BY code)
             FROM (
                    SELECT DISTINCT sl.code
                      FROM unified_video_standard_languages uvsl
                      JOIN standard_languages sl ON sl.id = uvsl.standard_language_id
                     WHERE uvsl.unified_video_id = uv.id
                       AND nullif(trim(sl.code), '') IS NOT NULL
                  ) codes
       ), language_codes, '[]'::jsonb),
       genre_codes = coalesce((
           SELECT jsonb_agg(code ORDER BY code)
             FROM (
                    SELECT DISTINCT sg.code
                      FROM unified_video_genres uvg
                      JOIN standard_genre sg ON sg.id = uvg.genre_id
                     WHERE uvg.unified_video_id = uv.id
                       AND nullif(trim(sg.code), '') IS NOT NULL
                  ) codes
       ), genre_codes, '[]'::jsonb),
       category_code = coalesce(uv.category_code, sc.slug)
  FROM standard_category sc
 WHERE uv.category_id = sc.id;

DROP TABLE IF EXISTS video_actors CASCADE;
DROP TABLE IF EXISTS video_directors CASCADE;
DROP TABLE IF EXISTS video_raw_genres CASCADE;
DROP TABLE IF EXISTS video_raw_languages CASCADE;
DROP TABLE IF EXISTS video_raw_areas CASCADE;

DROP TABLE IF EXISTS unified_video_actors CASCADE;
DROP TABLE IF EXISTS unified_video_directors CASCADE;
DROP TABLE IF EXISTS unified_video_genres CASCADE;
DROP TABLE IF EXISTS unified_video_standard_languages CASCADE;
DROP TABLE IF EXISTS unified_video_standard_areas CASCADE;

DROP TABLE IF EXISTS raw_genres CASCADE;
DROP TABLE IF EXISTS raw_languages CASCADE;
DROP TABLE IF EXISTS raw_areas CASCADE;

-- liquibase formatted sql

-- changeset codex:20260627-extend-standard-content-categories
-- comment: Expand canonical top-level content categories to twelve while keeping topic genres separate.

WITH seeds(id, name, slug) AS (
    VALUES
        ('00000000-0000-0000-0000-0000000000b1'::uuid, '电影', 'movie'),
        ('00000000-0000-0000-0000-0000000000b2'::uuid, '剧集', 'series'),
        ('00000000-0000-0000-0000-0000000000b3'::uuid, '短剧', 'short-drama'),
        ('00000000-0000-0000-0000-0000000000b4'::uuid, '动漫', 'anime'),
        ('00000000-0000-0000-0000-0000000000b5'::uuid, '综艺', 'variety'),
        ('00000000-0000-0000-0000-0000000000b6'::uuid, '纪录片', 'documentary'),
        ('00000000-0000-0000-0000-0000000000b7'::uuid, '体育赛事', 'sports'),
        ('00000000-0000-0000-0000-0000000000b8'::uuid, '新闻资讯', 'news'),
        ('00000000-0000-0000-0000-0000000000b9'::uuid, '教育知识', 'education'),
        ('00000000-0000-0000-0000-0000000000ba'::uuid, '音乐演出', 'music'),
        ('00000000-0000-0000-0000-0000000000bb'::uuid, '成人', 'adult'),
        ('00000000-0000-0000-0000-0000000000bc'::uuid, '其他', 'other')
)
UPDATE standard_category sc
   SET name = seeds.name,
       updated_at = now()
  FROM seeds
 WHERE lower(sc.slug) = lower(seeds.slug)
   AND sc.name IS DISTINCT FROM seeds.name;

WITH seeds(id, name, slug) AS (
    VALUES
        ('00000000-0000-0000-0000-0000000000b1'::uuid, '电影', 'movie'),
        ('00000000-0000-0000-0000-0000000000b2'::uuid, '剧集', 'series'),
        ('00000000-0000-0000-0000-0000000000b3'::uuid, '短剧', 'short-drama'),
        ('00000000-0000-0000-0000-0000000000b4'::uuid, '动漫', 'anime'),
        ('00000000-0000-0000-0000-0000000000b5'::uuid, '综艺', 'variety'),
        ('00000000-0000-0000-0000-0000000000b6'::uuid, '纪录片', 'documentary'),
        ('00000000-0000-0000-0000-0000000000b7'::uuid, '体育赛事', 'sports'),
        ('00000000-0000-0000-0000-0000000000b8'::uuid, '新闻资讯', 'news'),
        ('00000000-0000-0000-0000-0000000000b9'::uuid, '教育知识', 'education'),
        ('00000000-0000-0000-0000-0000000000ba'::uuid, '音乐演出', 'music'),
        ('00000000-0000-0000-0000-0000000000bb'::uuid, '成人', 'adult'),
        ('00000000-0000-0000-0000-0000000000bc'::uuid, '其他', 'other')
)
UPDATE standard_category sc
   SET slug = seeds.slug,
       updated_at = now()
  FROM seeds
 WHERE sc.name = seeds.name
   AND lower(sc.slug) <> lower(seeds.slug)
   AND NOT EXISTS (
       SELECT 1
         FROM standard_category existing
        WHERE lower(existing.slug) = lower(seeds.slug)
          AND existing.id <> sc.id
   );

WITH seeds(id, name, slug) AS (
    VALUES
        ('00000000-0000-0000-0000-0000000000b1'::uuid, '电影', 'movie'),
        ('00000000-0000-0000-0000-0000000000b2'::uuid, '剧集', 'series'),
        ('00000000-0000-0000-0000-0000000000b3'::uuid, '短剧', 'short-drama'),
        ('00000000-0000-0000-0000-0000000000b4'::uuid, '动漫', 'anime'),
        ('00000000-0000-0000-0000-0000000000b5'::uuid, '综艺', 'variety'),
        ('00000000-0000-0000-0000-0000000000b6'::uuid, '纪录片', 'documentary'),
        ('00000000-0000-0000-0000-0000000000b7'::uuid, '体育赛事', 'sports'),
        ('00000000-0000-0000-0000-0000000000b8'::uuid, '新闻资讯', 'news'),
        ('00000000-0000-0000-0000-0000000000b9'::uuid, '教育知识', 'education'),
        ('00000000-0000-0000-0000-0000000000ba'::uuid, '音乐演出', 'music'),
        ('00000000-0000-0000-0000-0000000000bb'::uuid, '成人', 'adult'),
        ('00000000-0000-0000-0000-0000000000bc'::uuid, '其他', 'other')
)
INSERT INTO standard_category (id, created_at, updated_at, version, name, slug)
SELECT seeds.id, now(), now(), 0, seeds.name, seeds.slug
  FROM seeds
 WHERE NOT EXISTS (
       SELECT 1
         FROM standard_category existing
        WHERE lower(existing.slug) = lower(seeds.slug)
           OR existing.name = seeds.name
   );

UPDATE raw_videos
   SET category_code = 'series',
       updated_at = now()
 WHERE lower(coalesce(category_code, '')) in ('tv', 'teleplay');

UPDATE unified_videos
   SET category_code = 'series',
       updated_at = now()
 WHERE lower(coalesce(category_code, '')) in ('tv', 'teleplay');

UPDATE raw_videos
   SET category_code = NULL,
       updated_at = now()
 WHERE category_code IS NOT NULL
   AND lower(category_code) NOT IN (
       'movie', 'series', 'short-drama', 'anime', 'variety', 'documentary',
       'sports', 'news', 'education', 'music', 'adult', 'other'
   );

UPDATE unified_videos
   SET category_code = NULL,
       updated_at = now()
 WHERE category_code IS NOT NULL
   AND lower(category_code) NOT IN (
       'movie', 'series', 'short-drama', 'anime', 'variety', 'documentary',
       'sports', 'news', 'education', 'music', 'adult', 'other'
   );

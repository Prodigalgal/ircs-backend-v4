--liquibase formatted sql

--changeset codex:20260623-backfill-cover-pgcrypto
CREATE EXTENSION IF NOT EXISTS pgcrypto;

--changeset codex:20260623-backfill-raw-video-cover-images splitStatements:false
--comment: Backfill raw_videos.cover_image_id and empty unified_videos.cover_image_id from raw_metadata.coverImageUrl.
DO $$
DECLARE
    raw_backfilled_count INTEGER;
    unified_backfilled_count INTEGER;
BEGIN
    WITH raw_urls AS (
        SELECT
            rv.id AS raw_video_id,
            rv.data_source_id,
            trim(rv.raw_metadata ->> 'coverImageUrl') AS cover_url
        FROM raw_videos rv
        WHERE rv.cover_image_id IS NULL
          AND rv.raw_metadata ? 'coverImageUrl'
          AND nullif(trim(rv.raw_metadata ->> 'coverImageUrl'), '') IS NOT NULL
    ),
    valid_urls AS (
        SELECT *
        FROM raw_urls
        WHERE length(cover_url) <= 2048
          AND lower(cover_url) NOT IN ('null', 'undefined')
    ),
    parsed_urls AS (
        SELECT
            matched.raw_video_id,
            matched.data_source_id,
            CASE
                WHEN matched.http_match IS NOT NULL THEN matched.http_match[1]
                ELSE 'EXTERNAL_COVER'
            END AS domain_value,
            CASE
                WHEN matched.http_match IS NOT NULL THEN
                    coalesce(nullif(matched.http_match[2], ''), '/')
                    || coalesce(matched.http_match[3], '')
                    || coalesce(matched.http_match[4], '')
                ELSE matched.cover_url
            END AS original_url
        FROM (
            SELECT
                valid_urls.*,
                regexp_match(
                    valid_urls.cover_url,
                    '^(https?://[^/?#]+)([^?#]*)(\?[^#]*)?(#.*)?$',
                    'i'
                ) AS http_match
            FROM valid_urls
        ) matched
    ),
    normalized_urls AS (
        SELECT
            raw_video_id,
            data_source_id,
            domain_value,
            original_url,
            encode(digest(domain_value, 'sha256'), 'hex') AS domain_hash
        FROM parsed_urls
        WHERE length(original_url) <= 2048
    ),
    distinct_domains AS (
        SELECT
            domain_hash,
            domain_value,
            min(data_source_id::text)::uuid AS data_source_id
        FROM normalized_urls
        GROUP BY domain_hash, domain_value
    ),
    upserted_domains AS (
        INSERT INTO source_domains (
            id,
            created_at,
            updated_at,
            version,
            domain_hash,
            domain_value,
            remark,
            data_source_id
        )
        SELECT
            gen_random_uuid(),
            now(),
            now(),
            0,
            domain_hash,
            left(domain_value, 255),
            'cover-backfill',
            data_source_id
        FROM distinct_domains
        ON CONFLICT (domain_hash) DO UPDATE SET
            domain_value = excluded.domain_value,
            data_source_id = coalesce(source_domains.data_source_id, excluded.data_source_id),
            updated_at = now()
        RETURNING id, domain_hash
    ),
    cover_inputs AS (
        SELECT
            normalized_urls.raw_video_id,
            normalized_urls.original_url,
            upserted_domains.id AS source_domain_id
        FROM normalized_urls
        JOIN upserted_domains ON upserted_domains.domain_hash = normalized_urls.domain_hash
    ),
    distinct_cover_inputs AS (
        SELECT DISTINCT original_url, source_domain_id
        FROM cover_inputs
    ),
    upserted_covers AS (
        INSERT INTO cover_images (
            id,
            created_at,
            updated_at,
            version,
            storage_type,
            original_url,
            storage_path,
            file_hash,
            file_size,
            mime_type,
            source_domain_id,
            status,
            retry_count,
            next_retry_time,
            last_error
        )
        SELECT
            gen_random_uuid(),
            now(),
            now(),
            0,
            'EXTERNAL',
            original_url,
            NULL,
            NULL,
            NULL,
            NULL,
            source_domain_id,
            'UNPROCESSED',
            0,
            now(),
            NULL
        FROM distinct_cover_inputs
        ON CONFLICT (original_url, source_domain_id) DO UPDATE
        SET updated_at = cover_images.updated_at
        RETURNING id, original_url, source_domain_id
    ),
    raw_updates AS (
        UPDATE raw_videos rv
        SET cover_image_id = upserted_covers.id,
            updated_at = now()
        FROM cover_inputs
        JOIN upserted_covers
          ON upserted_covers.original_url = cover_inputs.original_url
         AND upserted_covers.source_domain_id = cover_inputs.source_domain_id
        WHERE rv.id = cover_inputs.raw_video_id
          AND rv.cover_image_id IS NULL
        RETURNING rv.id
    ),
    unified_candidates AS (
        SELECT DISTINCT ON (rvuv.unified_video_id)
            rvuv.unified_video_id,
            rv.cover_image_id
        FROM raw_video_unified_video rvuv
        JOIN raw_videos rv ON rv.id = rvuv.raw_video_id
        JOIN unified_videos uv ON uv.id = rvuv.unified_video_id
        WHERE rv.cover_image_id IS NOT NULL
          AND uv.cover_image_id IS NULL
          AND lower(coalesce(uv.locked_fields::text, '')) NOT LIKE '%coverimageurl%'
        ORDER BY rvuv.unified_video_id, rv.created_at ASC, rv.id ASC
    ),
    unified_updates AS (
        UPDATE unified_videos uv
        SET cover_image_id = unified_candidates.cover_image_id,
            updated_at = now(),
            version = coalesce(uv.version, 0) + 1
        FROM unified_candidates
        WHERE uv.id = unified_candidates.unified_video_id
          AND uv.cover_image_id IS NULL
          AND lower(coalesce(uv.locked_fields::text, '')) NOT LIKE '%coverimageurl%'
        RETURNING uv.id
    )
    SELECT
        (SELECT count(*) FROM raw_updates),
        (SELECT count(*) FROM unified_updates)
    INTO raw_backfilled_count, unified_backfilled_count;

    RAISE NOTICE
        'Backfilled cover image references: raw_videos=%, unified_videos=%',
        raw_backfilled_count,
        unified_backfilled_count;
END $$;

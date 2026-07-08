package com.prodigalgal.ircs.content.video.infrastructure;

import static com.prodigalgal.ircs.content.video.domain.VideoAdminText.cleanText;
import static com.prodigalgal.ircs.content.video.domain.VideoAdminText.normalizeExternalId;
import static com.prodigalgal.ircs.content.video.domain.VideoAdminText.normalizeYear;
import static com.prodigalgal.ircs.content.video.domain.VideoAdminText.trimToLength;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.contracts.trend.TrendItemPayload;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
class VideoAdminTrendRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final VideoAdminCoverImageRepository coverImageRepository;

    List<UUID> updateTrendTimeByDoubanIds(Collection<String> ids, Instant now) {
        return updateTrendTimeByExternalIds("douban_id", ids, now);
    }

    List<UUID> updateTrendTimeByTmdbIds(Collection<String> ids, Instant now) {
        return updateTrendTimeByExternalIds("tmdb_id", ids, now);
    }

    Set<String> findExistingDoubanIds(Collection<String> ids) {
        return findExistingExternalIds("douban_id", ids);
    }

    Set<String> findExistingTmdbIds(Collection<String> ids) {
        return findExistingExternalIds("tmdb_id", ids);
    }

    List<String> findIdsByYearAndTitleSimilarity(List<String> years, String title, double threshold) {
        if (years == null || years.isEmpty() || !StringUtils.hasText(title)) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
                """
                select id::text
                  from unified_videos uv
                 where uv.year in (:years)
                   and similarity(uv.title, :title) >= :threshold
                 order by similarity(uv.title, :title) desc,
                          uv.updated_at desc nulls last
                 limit 5
                """,
                new MapSqlParameterSource()
                        .addValue("years", years)
                        .addValue("title", title.trim())
                        .addValue("threshold", threshold),
                String.class);
    }

    boolean updateTrendTimeById(UUID id, Instant now) {
        if (id == null) {
            return false;
        }
        int updated = jdbcTemplate.update(
                """
                update unified_videos
                   set last_trend_at = :now,
                       updated_at = :now
                 where id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("now", Timestamp.from(now)));
        return updated > 0;
    }

    UUID createTrendGhost(TrendItemPayload item, Instant trendTime) {
        UUID id = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        UUID coverImageId = coverImageRepository.getOrCreateCoverImage(item.posterUrl()).orElse(null);
        jdbcTemplate.update(
                """
                insert into unified_videos (
                    id, created_at, updated_at, version, title, alias_title, season, subtitle,
                    cover_image_id, description, year, area, score, published_at,
                    total_episodes, duration, remarks,
                    douban_id, tmdb_id, imdb_id, rotten_tomatoes_id,
                    last_trend_at, locked_fields, category_id, content_visibility, metadata_status
                ) values (
                    :id, :now, :now, 0, :title, null, null, null,
                    :coverImageId, :description, :year, null, :score, :publishedAt,
                    null, null, null,
                    :doubanId, :tmdbId, :imdbId, null,
                    :lastTrendAt, cast(:lockedFields as jsonb), null, 'PUBLIC', 'DIRTY'
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("now", now)
                        .addValue("title", trimToLength(item.title(), 255))
                        .addValue("coverImageId", coverImageId)
                        .addValue("description", cleanText(item.description(), 5000))
                        .addValue("year", normalizeYear(item.year()))
                        .addValue("score", item.score())
                        .addValue("publishedAt", item.publishedAt() == null ? null : Date.valueOf(item.publishedAt()))
                        .addValue("doubanId", normalizeExternalId(item.doubanId(), 20))
                        .addValue("tmdbId", normalizeExternalId(item.tmdbId(), 20))
                        .addValue("imdbId", normalizeExternalId(item.imdbId(), 20))
                        .addValue("lastTrendAt", Timestamp.from(trendTime))
                        .addValue("lockedFields", "[]"));
        return id;
    }

    private List<UUID> updateTrendTimeByExternalIds(String column, Collection<String> ids, Instant now) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
                """
                update unified_videos
                   set last_trend_at = :now,
                       updated_at = :now
                 where %s in (:ids)
                 returning id
                """.formatted(column),
                new MapSqlParameterSource()
                        .addValue("ids", ids)
                        .addValue("now", Timestamp.from(now)),
                UUID.class);
    }

    private Set<String> findExistingExternalIds(String column, Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Set.of();
        }
        List<String> values = jdbcTemplate.queryForList(
                """
                select %s
                  from unified_videos
                 where %s in (:ids)
                """.formatted(column, column),
                new MapSqlParameterSource("ids", ids),
                String.class);
        return new LinkedHashSet<>(values);
    }
}

package com.prodigalgal.ircs.content.video.infrastructure;


import com.prodigalgal.ircs.content.video.api.ContentApiException;
import static com.prodigalgal.ircs.content.video.domain.VideoAdminText.cleanText;
import static com.prodigalgal.ircs.content.video.domain.VideoAdminText.normalizeExternalId;
import static com.prodigalgal.ircs.content.video.domain.VideoAdminText.normalizeTags;
import static com.prodigalgal.ircs.content.video.domain.VideoAdminText.normalizeYear;
import static com.prodigalgal.ircs.content.video.domain.VideoAdminText.normalizeContentVisibility;
import static com.prodigalgal.ircs.content.video.domain.VideoAdminText.sha256;
import static com.prodigalgal.ircs.content.video.domain.VideoAdminText.trimToLength;

import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.metadata.JsonStringArrays;
import com.prodigalgal.ircs.common.metadata.MetadataNameOwnerType;
import com.prodigalgal.ircs.common.metadata.MetadataNameValkeyCache;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.RawVideoCardResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.RawVideoCreateRequest;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.RawVideoDetailResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.RawVideoUpdateRequest;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoCardResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoCreateRequest;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoDetailResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoUpdateRequest;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class JdbcVideoAdminRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<MetadataNameValkeyCache> metadataNameCacheProvider;
    private final VideoAdminCoverImageRepository coverImageRepository;
    private final VideoAdminTrendRepository trendRepository;
    private final VideoAdminQueryRepository queryRepository;

    public Page<RawVideoCardResponse> findRawVideos(
            Pageable pageable,
            String title,
            UUID categoryId,
            String enrichmentStatus,
            String normalizationStatus,
            String aggregationStatus,
            String year,
            String area,
            BigDecimal minScore,
            Boolean isMissingSlug,
            UUID dataSourceId,
            String sourceCategoryName,
            String genre,
            String language) {
        return queryRepository.findRawVideos(
                pageable,
                title,
                categoryId,
                enrichmentStatus,
                normalizationStatus,
                aggregationStatus,
                year,
                area,
                minScore,
                isMissingSlug,
                dataSourceId,
                sourceCategoryName,
                genre,
                language);
    }

    public Optional<RawVideoDetailResponse> findRawDetail(UUID id) {
        return queryRepository.findRawDetail(id);
    }

    public UUID createRawVideo(RawVideoCreateRequest request) {
        UUID id = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbcTemplate.update(
                """
                insert into raw_videos (
                    id, created_at, updated_at, version, source_vid, source_hash, data_hash,
                    title, alias_title, season, subtitle, cover_image_id, description, year, raw_language_str,
                    remarks, score, published_at, total_episodes, duration,
                    douban_id, tmdb_id, imdb_id, rotten_tomatoes_id, locked_fields,
                    data_source_id, actor_names, director_names,
                    area_codes, language_codes, genre_codes, category_code,
                    source_category_code, source_category_name,
                    enrichment_status, enrichment_retry_count,
                    normalization_status, normalization_retry_count, next_normalization_retry_time,
                    aggregation_status, playlist_retry_count, raw_metadata
                ) values (
                    :id, :now, :now, 0, :sourceVid, :sourceHash, null,
                    :title, :aliasTitle, :season, :subtitle, :coverImageId, :description, :year, :rawLanguageStr,
                    :remarks, :score, :publishedAt, :totalEpisodes, :duration,
                    :doubanId, :tmdbId, :imdbId, :rottenTomatoesId, cast(:lockedFields as jsonb),
                    :dataSourceId, cast(:actorNames as jsonb), cast(:directorNames as jsonb),
                    cast(:areaCodes as jsonb), cast(:languageCodes as jsonb), cast(:genreCodes as jsonb), :categoryCode,
                    :sourceCategoryCode, :sourceCategoryName,
                    'PENDING', 0,
                    'READY', 0, null,
                    'PENDING', 0, null
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("now", now)
                        .addValue("sourceVid", "MANUAL_" + id)
                        .addValue("sourceHash", sha256("MANUAL:" + id))
                        .addValue("title", trimToLength(request.title(), 255))
                        .addValue("aliasTitle", trimToLength(request.aliasTitle(), 255))
                        .addValue("season", request.season())
                        .addValue("subtitle", trimToLength(request.subtitle(), 255))
                        .addValue("coverImageId", coverImageRepository.getOrCreateCoverImage(request.coverImageUrl()).orElse(null))
                        .addValue("description", cleanText(request.description(), 5000))
                        .addValue("year", normalizeYear(request.year()))
                        .addValue("rawLanguageStr", request.rawLanguageStr())
                        .addValue("remarks", cleanText(request.remarks(), 255))
                        .addValue("score", request.score())
                        .addValue("publishedAt", request.publishedAt() == null ? null : Date.valueOf(request.publishedAt()))
                        .addValue("totalEpisodes", trimToLength(request.totalEpisodes(), 50))
                        .addValue("duration", trimToLength(request.duration(), 50))
                        .addValue("doubanId", normalizeExternalId(request.doubanId(), 20))
                        .addValue("tmdbId", normalizeExternalId(request.tmdbId(), 20))
                        .addValue("imdbId", normalizeExternalId(request.imdbId(), 20))
                        .addValue("rottenTomatoesId", normalizeExternalId(request.rottenTomatoesId(), 50))
                        .addValue("lockedFields", "[]")
                        .addValue("dataSourceId", request.dataSourceId())
                        .addValue("actorNames", jsonStringArray(request.actorNames()))
                        .addValue("directorNames", jsonStringArray(request.directorNames()))
                        .addValue("areaCodes", jsonStringArray(request.areaCodes()))
                        .addValue("languageCodes", jsonStringArray(request.languageCodes()))
                        .addValue("genreCodes", jsonStringArray(request.genreCodes()))
                        .addValue("categoryCode", firstText(request.categoryCode(), findCategoryCode(request.categoryId()).orElse(null)))
                        .addValue("sourceCategoryCode", trimToLength(request.sourceCategoryCode(), 100))
                        .addValue("sourceCategoryName", trimToLength(request.sourceCategoryName(), 255)));
        evictMetadataNameCache(MetadataNameOwnerType.RAW, List.of(id));
        return id;
    }

    public RawVideoSnapshot rawSnapshot(UUID id) {
        return queryRepository.rawSnapshot(id);
    }

    public void updateRawVideo(UUID id, RawVideoUpdateRequest request) {
        Map<String, Object> updates = new LinkedHashMap<>();
        putIfPresent(updates, "title", trimToLength(request.title(), 255));
        putIfPresent(updates, "alias_title", trimToLength(request.aliasTitle(), 255));
        putIfPresent(updates, "season", request.season());
        putIfPresent(updates, "subtitle", trimToLength(request.subtitle(), 255));
        putIfPresent(updates, "cover_image_id", coverImageRepository.getOrCreateCoverImage(request.coverImageUrl()).orElse(null), request.coverImageUrl() != null);
        putIfPresent(updates, "description", cleanText(request.description(), 5000));
        putIfPresent(updates, "year", normalizeYear(request.year()));
        putIfPresent(updates, "raw_language_str", request.rawLanguageStr());
        putIfPresent(updates, "remarks", cleanText(request.remarks(), 255));
        putIfPresent(updates, "score", request.score());
        putIfPresent(updates, "published_at", request.publishedAt() == null ? null : Date.valueOf(request.publishedAt()));
        putIfPresent(updates, "total_episodes", trimToLength(request.totalEpisodes(), 50));
        putIfPresent(updates, "duration", trimToLength(request.duration(), 50));
        putIfPresent(updates, "douban_id", normalizeExternalId(request.doubanId(), 20));
        putIfPresent(updates, "tmdb_id", normalizeExternalId(request.tmdbId(), 20));
        putIfPresent(updates, "imdb_id", normalizeExternalId(request.imdbId(), 20));
        putIfPresent(updates, "rotten_tomatoes_id", normalizeExternalId(request.rottenTomatoesId(), 50));
        if (request.lockedFields() != null) {
            updates.put("locked_fields", json(request.lockedFields()));
        }
        putIfPresent(updates, "data_source_id", request.dataSourceId(), request.dataSourceId() != null);
        putJsonIfPresent(updates, "actor_names", request.actorNames(), request.actorNames() != null);
        putJsonIfPresent(updates, "director_names", request.directorNames(), request.directorNames() != null);
        putJsonIfPresent(updates, "area_codes", request.areaCodes(), request.areaCodes() != null);
        putJsonIfPresent(updates, "language_codes", request.languageCodes(), request.languageCodes() != null);
        putJsonIfPresent(updates, "genre_codes", request.genreCodes(), request.genreCodes() != null);
        putIfPresent(updates, "category_code", trimToLength(request.categoryCode(), 100), request.categoryCode() != null);
        putIfPresent(updates, "source_category_code", trimToLength(request.sourceCategoryCode(), 100), request.sourceCategoryCode() != null);
        putIfPresent(updates, "source_category_name", trimToLength(request.sourceCategoryName(), 255), request.sourceCategoryName() != null);
        if (!updates.isEmpty()) {
            updateTable("raw_videos", id, updates);
        }
        if (request.actorNames() != null
                || request.directorNames() != null
                || request.areaCodes() != null
                || request.languageCodes() != null
                || request.genreCodes() != null) {
            evictMetadataNameCache(MetadataNameOwnerType.RAW, List.of(id));
        }
    }

    public void setRawAggregationStatus(UUID rawVideoId, String status) {
        jdbcTemplate.update(
                "update raw_videos set aggregation_status = :status, updated_at = now() where id = :id",
                new MapSqlParameterSource().addValue("id", rawVideoId).addValue("status", status));
    }

    public void setRawNormalizationPending(UUID rawVideoId) {
        int updated = jdbcTemplate.update(
                """
                update raw_videos
                set normalization_status = 'PENDING',
                    normalization_retry_count = 0,
                    next_normalization_retry_time = null,
                    updated_at = now()
                where id = :id
                """,
                new MapSqlParameterSource("id", rawVideoId));
        if (updated == 0) {
            throw new ContentApiException(HttpStatus.NOT_FOUND, "Raw video not found: " + rawVideoId);
        }
    }

    public void unbindRaw(UUID rawVideoId) {
        jdbcTemplate.update(
                "delete from raw_video_unified_video where raw_video_id = :id",
                new MapSqlParameterSource("id", rawVideoId));
        setRawAggregationStatus(rawVideoId, "PENDING");
    }

    public void bindRawToUnified(UUID rawVideoId, UUID unifiedVideoId) {
        ensureExists("unified_videos", unifiedVideoId, "Unified video not found: " + unifiedVideoId);
        jdbcTemplate.update(
                "delete from raw_video_unified_video where raw_video_id = :rawVideoId",
                new MapSqlParameterSource("rawVideoId", rawVideoId));
        jdbcTemplate.update(
                """
                insert into raw_video_unified_video (raw_video_id, unified_video_id)
                values (:rawVideoId, :unifiedVideoId)
                on conflict do nothing
                """,
                new MapSqlParameterSource()
                        .addValue("rawVideoId", rawVideoId)
                        .addValue("unifiedVideoId", unifiedVideoId));
        setRawAggregationStatus(rawVideoId, "BOUND");
        markUnifiedDirty(unifiedVideoId);
    }

    public List<UUID> findRawUnifiedBindings(List<UUID> rawIds) {
        if (CollectionUtils.isEmpty(rawIds)) {
            return List.of();
        }
        return jdbcTemplate.queryForList(
                """
                select distinct unified_video_id
                from raw_video_unified_video
                where raw_video_id in (:ids)
                """,
                new MapSqlParameterSource("ids", rawIds),
                UUID.class);
    }

    public void deleteRawVideos(List<UUID> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        List<UUID> unifiedIds = jdbcTemplate.queryForList(
                "select distinct unified_video_id from raw_video_unified_video where raw_video_id in (:ids)",
                params,
                UUID.class);
        jdbcTemplate.update("delete from episodes where playlist_id in (select id from playlists where video_id in (:ids))", params);
        jdbcTemplate.update("delete from playlists where video_id in (:ids)", params);
        jdbcTemplate.update("delete from raw_video_unified_video where raw_video_id in (:ids)", params);
        jdbcTemplate.update("delete from raw_videos where id in (:ids)", params);
        evictMetadataNameCache(MetadataNameOwnerType.RAW, ids);
        evictMetadataNameCache(MetadataNameOwnerType.UNIFIED, unifiedIds);
    }

    public Page<UnifiedVideoCardResponse> findUnifiedVideos(
            Pageable pageable,
            String title,
            UUID categoryId,
            String year,
            String area,
            BigDecimal minScore,
            Boolean hasDoubanId,
            Boolean hasTmdbId,
            String contentVisibility,
            String metadataStatus,
            String genre,
            String language,
            String actor,
            String director) {
        return queryRepository.findUnifiedVideos(
                pageable,
                title,
                categoryId,
                year,
                area,
                minScore,
                hasDoubanId,
                hasTmdbId,
                contentVisibility,
                metadataStatus,
                genre,
                language,
                actor,
                director);
    }

    public Optional<UnifiedVideoDetailResponse> findUnifiedDetail(UUID id) {
        return queryRepository.findUnifiedDetail(id);
    }

    public UUID createUnifiedVideo(UnifiedVideoCreateRequest request) {
        UUID id = IrcsUuidGenerators.nextId();
        Timestamp now = Timestamp.from(Instant.now());
        UUID coverImageId = coverImageRepository.getOrCreateCoverImage(request.coverImageUrl()).orElse(null);
        jdbcTemplate.update(
                """
                insert into unified_videos (
                    id, created_at, updated_at, version, title, alias_title, season, subtitle,
                    cover_image_id, description, year, area, score, published_at,
                    total_episodes, duration, remarks,
                    douban_id, tmdb_id, imdb_id, rotten_tomatoes_id,
                    locked_fields, category_id, category_code, actor_names, director_names,
                    area_codes, language_codes, genre_codes, content_visibility, metadata_status
                ) values (
                    :id, :now, :now, 0, :title, :aliasTitle, :season, :subtitle,
                    :coverImageId, :description, :year, null, :score, :publishedAt,
                    :totalEpisodes, :duration, :remarks,
                    :doubanId, :tmdbId, :imdbId, :rottenTomatoesId,
                    cast(:lockedFields as jsonb), :categoryId, :categoryCode, cast(:actorNames as jsonb), cast(:directorNames as jsonb),
                    cast(:areaCodes as jsonb), cast(:languageCodes as jsonb), cast(:genreCodes as jsonb), :contentVisibility, 'DIRTY'
                )
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("now", now)
                        .addValue("title", trimToLength(request.title(), 255))
                        .addValue("aliasTitle", trimToLength(request.aliasTitle(), 255))
                        .addValue("season", request.season())
                        .addValue("subtitle", trimToLength(request.subtitle(), 255))
                        .addValue("coverImageId", coverImageId)
                        .addValue("description", cleanText(request.description(), 5000))
                        .addValue("year", normalizeYear(request.year()))
                        .addValue("score", request.score())
                        .addValue("publishedAt", request.publishedAt() == null ? null : Date.valueOf(request.publishedAt()))
                        .addValue("totalEpisodes", trimToLength(request.totalEpisodes(), 50))
                        .addValue("duration", trimToLength(request.duration(), 50))
                        .addValue("remarks", cleanText(request.remarks(), 255))
                        .addValue("doubanId", normalizeExternalId(request.doubanId(), 20))
                        .addValue("tmdbId", normalizeExternalId(request.tmdbId(), 20))
                        .addValue("imdbId", normalizeExternalId(request.imdbId(), 20))
                        .addValue("rottenTomatoesId", normalizeExternalId(request.rottenTomatoesId(), 50))
                        .addValue("lockedFields", jsonOrEmpty(request.lockedFields()))
                        .addValue("categoryId", request.categoryId())
                        .addValue("categoryCode", firstText(request.categoryCode(), findCategoryCode(request.categoryId()).orElse(null)))
                        .addValue("actorNames", jsonStringArray(request.actorNames()))
                        .addValue("directorNames", jsonStringArray(request.directorNames()))
                        .addValue("areaCodes", jsonStringArray(request.areaCodes()))
                        .addValue("languageCodes", jsonStringArray(request.languageCodes()))
                        .addValue("genreCodes", jsonStringArray(request.genreCodes()))
                        .addValue("contentVisibility", normalizeContentVisibility(request.contentVisibility())));
        replaceUnifiedTags(id, request.tags());
        bindRawVideosToUnified(id, request.bindVideoIds());
        evictMetadataNameCache(MetadataNameOwnerType.UNIFIED, List.of(id));
        return id;
    }

    public List<UUID> updateTrendTimeByDoubanIds(Collection<String> ids, Instant now) {
        return trendRepository.updateTrendTimeByDoubanIds(ids, now);
    }

    public List<UUID> updateTrendTimeByTmdbIds(Collection<String> ids, Instant now) {
        return trendRepository.updateTrendTimeByTmdbIds(ids, now);
    }

    public Set<String> findExistingDoubanIds(Collection<String> ids) {
        return trendRepository.findExistingDoubanIds(ids);
    }

    public Set<String> findExistingTmdbIds(Collection<String> ids) {
        return trendRepository.findExistingTmdbIds(ids);
    }

    public List<String> findIdsByYearAndTitleSimilarity(List<String> years, String title, double threshold) {
        return trendRepository.findIdsByYearAndTitleSimilarity(years, title, threshold);
    }

    public boolean updateTrendTimeById(UUID id, Instant now) {
        return trendRepository.updateTrendTimeById(id, now);
    }

    public UUID createTrendGhost(com.prodigalgal.ircs.contracts.trend.TrendItemPayload item, Instant trendTime) {
        return trendRepository.createTrendGhost(item, trendTime);
    }

    public void updateUnifiedVideo(UUID id, UnifiedVideoUpdateRequest request) {
        Map<String, Object> updates = new LinkedHashMap<>();
        putIfPresent(updates, "title", trimToLength(request.title(), 255));
        putIfPresent(updates, "alias_title", trimToLength(request.aliasTitle(), 255));
        putIfPresent(updates, "season", request.season());
        putIfPresent(updates, "subtitle", trimToLength(request.subtitle(), 255));
        putIfPresent(updates, "cover_image_id", coverImageRepository.getOrCreateCoverImage(request.coverImageUrl()).orElse(null), request.coverImageUrl() != null);
        putIfPresent(updates, "description", cleanText(request.description(), 5000));
        putIfPresent(updates, "year", normalizeYear(request.year()));
        putIfPresent(updates, "score", request.score());
        putIfPresent(updates, "published_at", request.publishedAt() == null ? null : Date.valueOf(request.publishedAt()));
        putIfPresent(updates, "total_episodes", trimToLength(request.totalEpisodes(), 50));
        putIfPresent(updates, "duration", trimToLength(request.duration(), 50));
        putIfPresent(updates, "remarks", cleanText(request.remarks(), 255));
        putIfPresent(updates, "douban_id", normalizeExternalId(request.doubanId(), 20));
        putIfPresent(updates, "tmdb_id", normalizeExternalId(request.tmdbId(), 20));
        putIfPresent(updates, "imdb_id", normalizeExternalId(request.imdbId(), 20));
        putIfPresent(updates, "rotten_tomatoes_id", normalizeExternalId(request.rottenTomatoesId(), 50));
        putIfPresent(updates, "category_id", request.categoryId());
        putIfPresent(
                updates,
                "category_code",
                firstText(request.categoryCode(), findCategoryCode(request.categoryId()).orElse(null)),
                request.categoryCode() != null || request.categoryId() != null);
        putJsonIfPresent(updates, "actor_names", request.actorNames(), request.actorNames() != null);
        putJsonIfPresent(updates, "director_names", request.directorNames(), request.directorNames() != null);
        putJsonIfPresent(updates, "area_codes", request.areaCodes(), request.areaCodes() != null);
        putJsonIfPresent(updates, "language_codes", request.languageCodes(), request.languageCodes() != null);
        putJsonIfPresent(updates, "genre_codes", request.genreCodes(), request.genreCodes() != null);
        putIfPresent(
                updates,
                "content_visibility",
                normalizeContentVisibility(request.contentVisibility()),
                request.contentVisibility() != null);
        if (request.lockedFields() != null) {
            updates.put("locked_fields", json(request.lockedFields()));
        }
        updates.put("metadata_status", "DIRTY");
        updateTable("unified_videos", id, updates);
        if (request.tags() != null) {
            replaceUnifiedTags(id, request.tags());
        }
        unbindRawVideos(request.unbindVideoIds());
        bindRawVideosToUnified(id, request.bindVideoIds());
        if (request.areaCodes() != null
                || request.actorNames() != null
                || request.directorNames() != null
                || request.genreCodes() != null
                || request.languageCodes() != null) {
            evictMetadataNameCache(MetadataNameOwnerType.UNIFIED, List.of(id));
        }
    }

    public void bindRawVideosToUnified(UUID unifiedVideoId, Set<UUID> rawIds) {
        if (rawIds == null) {
            return;
        }
        for (UUID rawId : rawIds) {
            bindRawToUnified(rawId, unifiedVideoId);
        }
    }

    public void unbindRawVideos(Set<UUID> rawIds) {
        if (rawIds == null) {
            return;
        }
        for (UUID rawId : rawIds) {
            unbindRaw(rawId);
        }
    }

    public List<UUID> findRawIdsForUnified(UUID unifiedVideoId) {
        return jdbcTemplate.queryForList(
                "select raw_video_id from raw_video_unified_video where unified_video_id = :id",
                new MapSqlParameterSource("id", unifiedVideoId),
                UUID.class);
    }

    public List<UUID> recalculateUnifiedFromSources(UUID unifiedVideoId) {
        ensureExists("unified_videos", unifiedVideoId, "Unified video not found: " + unifiedVideoId);
        List<UUID> rawIds = findRawIdsForUnified(unifiedVideoId);
        for (UUID rawId : rawIds) {
            jdbcTemplate.update(
                    """
                    update unified_videos uv
                    set title = coalesce(nullif(uv.title, ''), rv.title),
                        alias_title = coalesce(nullif(uv.alias_title, ''), rv.alias_title),
                        description = coalesce(nullif(uv.description, ''), rv.description),
                        year = coalesce(nullif(uv.year, ''), rv.year),
                        score = coalesce(uv.score, rv.score),
                        published_at = coalesce(uv.published_at, rv.published_at),
                        total_episodes = coalesce(nullif(uv.total_episodes, ''), rv.total_episodes),
                        duration = coalesce(nullif(uv.duration, ''), rv.duration),
                        remarks = coalesce(nullif(uv.remarks, ''), rv.remarks),
                        douban_id = coalesce(nullif(uv.douban_id, ''), rv.douban_id),
                        tmdb_id = coalesce(nullif(uv.tmdb_id, ''), rv.tmdb_id),
                        imdb_id = coalesce(nullif(uv.imdb_id, ''), rv.imdb_id),
                        rotten_tomatoes_id = coalesce(nullif(uv.rotten_tomatoes_id, ''), rv.rotten_tomatoes_id),
                        metadata_status = 'SYNCED',
                        updated_at = now()
                    from raw_videos rv
                    where uv.id = :unifiedVideoId
                      and rv.id = :rawVideoId
                    """,
                    new MapSqlParameterSource()
                            .addValue("unifiedVideoId", unifiedVideoId)
                            .addValue("rawVideoId", rawId));
            jdbcTemplate.update(
                    "update raw_videos set aggregation_status = 'BOUND', updated_at = now() where id = :rawVideoId",
                    new MapSqlParameterSource("rawVideoId", rawId));
        }
        if (rawIds.isEmpty()) {
            jdbcTemplate.update(
                    "update unified_videos set metadata_status = 'SYNCED', updated_at = now() where id = :id",
                    new MapSqlParameterSource("id", unifiedVideoId));
        }
        return rawIds;
    }

    public void markUnifiedDirty(UUID unifiedVideoId) {
        jdbcTemplate.update(
                "update unified_videos set metadata_status = 'DIRTY', updated_at = now() where id = :id",
                new MapSqlParameterSource("id", unifiedVideoId));
    }

    public void deleteUnifiedVideos(List<UUID> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return;
        }
        List<UUID> rawIds = jdbcTemplate.queryForList(
                "select raw_video_id from raw_video_unified_video where unified_video_id in (:ids)",
                new MapSqlParameterSource("ids", ids),
                UUID.class);
        if (!rawIds.isEmpty()) {
            jdbcTemplate.update(
                    "update raw_videos set aggregation_status = 'PENDING', updated_at = now() where id in (:rawIds)",
                    new MapSqlParameterSource("rawIds", rawIds));
        }
        MapSqlParameterSource params = new MapSqlParameterSource("ids", ids);
        jdbcTemplate.update("delete from raw_video_unified_video where unified_video_id in (:ids)", params);
        jdbcTemplate.update("delete from unified_videos where id in (:ids)", params);
        evictMetadataNameCache(MetadataNameOwnerType.UNIFIED, ids);
    }

    private void evictMetadataNameCache(MetadataNameOwnerType ownerType, Collection<UUID> ownerIds) {
        MetadataNameValkeyCache metadataNameCache = metadataNameCacheProvider == null
                ? null
                : metadataNameCacheProvider.getIfAvailable();
        if (metadataNameCache != null) {
            metadataNameCache.evictOwner(ownerType, ownerIds);
        }
    }

    private void replaceUnifiedTags(UUID unifiedVideoId, Set<String> tags) {
        jdbcTemplate.update(
                "delete from unified_video_tags where unified_video_id = :id",
                new MapSqlParameterSource("id", unifiedVideoId));
        Set<String> safeTags = normalizeTags(tags);
        if (safeTags.isEmpty()) {
            return;
        }
        List<MapSqlParameterSource> batch = safeTags.stream()
                .map(tag -> new MapSqlParameterSource()
                        .addValue("id", unifiedVideoId)
                        .addValue("tag", tag))
                .toList();
        jdbcTemplate.batchUpdate(
                """
                insert into unified_video_tags (unified_video_id, tag)
                values (:id, :tag)
                on conflict do nothing
                """,
                batch.toArray(MapSqlParameterSource[]::new));
    }

    private void updateTable(String table, UUID id, Map<String, Object> updates) {
        if (updates.isEmpty()) {
            return;
        }
        List<String> assignments = new ArrayList<>();
        MapSqlParameterSource params = new MapSqlParameterSource("id", id);
        int i = 0;
        for (Map.Entry<String, Object> entry : updates.entrySet()) {
            String param = "p" + i++;
            if (isJsonbColumn(entry.getKey())) {
                assignments.add(entry.getKey() + " = cast(:" + param + " as jsonb)");
            } else {
                assignments.add(entry.getKey() + " = :" + param);
            }
            params.addValue(param, entry.getValue());
        }
        assignments.add("updated_at = now()");
        int updated = jdbcTemplate.update(
                "update %s set %s where id = :id".formatted(table, String.join(", ", assignments)),
                params);
        if (updated == 0) {
            throw new ContentApiException(HttpStatus.NOT_FOUND, table + " not found: " + id);
        }
    }

    private void ensureExists(String table, UUID id, String message) {
        Boolean exists = jdbcTemplate.queryForObject(
                "select exists(select 1 from %s where id = :id)".formatted(table),
                new MapSqlParameterSource("id", id),
                Boolean.class);
        if (!Boolean.TRUE.equals(exists)) {
            throw new ContentApiException(HttpStatus.NOT_FOUND, message);
        }
    }

    private void putIfPresent(Map<String, Object> updates, String column, Object value) {
        putIfPresent(updates, column, value, value != null);
    }

    private void putIfPresent(Map<String, Object> updates, String column, Object value, boolean present) {
        if (present) {
            updates.put(column, value);
        }
    }

    private void putJsonIfPresent(
            Map<String, Object> updates,
            String column,
            Collection<String> values,
            boolean present) {
        if (present) {
            updates.put(column, jsonStringArray(values));
        }
    }

    private boolean isJsonbColumn(String column) {
        return Set.of(
                "locked_fields",
                "actor_names",
                "director_names",
                "area_codes",
                "language_codes",
                "genre_codes").contains(column);
    }

    private String jsonOrEmpty(Set<String> values) {
        return json(values == null ? Collections.emptySet() : values);
    }

    private String jsonStringArray(Collection<String> values) {
        return JsonStringArrays.write(objectMapper, values);
    }

    private String firstText(String preferred, String fallback) {
        return firstText(preferred, fallback, 100);
    }

    private String firstText(String preferred, String fallback, int maxLength) {
        if (StringUtils.hasText(preferred)) {
            return trimToLength(preferred, maxLength);
        }
        return StringUtils.hasText(fallback) ? trimToLength(fallback, maxLength) : null;
    }

    private Optional<String> findCategoryCode(UUID... categoryIds) {
        List<UUID> ids = java.util.Arrays.stream(categoryIds)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        List<String> codes = jdbcTemplate.queryForList(
                """
                select slug
                  from standard_category
                 where id in (:ids)
                   and nullif(trim(slug), '') is not null
                 order by name
                 limit 1
                """,
                new MapSqlParameterSource("ids", ids),
                String.class);
        return codes.isEmpty() ? Optional.empty() : Optional.of(codes.getFirst());
    }

    private String json(Set<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? Collections.emptySet() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize jsonb field", e);
        }
    }

    public record RawVideoSnapshot(
            UUID id,
            String title,
            String aliasTitle,
            String year,
            Integer season,
            String dataHash,
            UUID unifiedVideoId) {
    }

}

package com.prodigalgal.ircs.content.video.infrastructure;

import static com.prodigalgal.ircs.content.video.domain.VideoAdminText.normalizeContentVisibility;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.metadata.JsonStringArrays;
import com.prodigalgal.ircs.content.config.ContentCoverImageUrlResolver;
import com.prodigalgal.ircs.content.video.api.ContentApiException;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.RawVideoCardResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.RawVideoDetailResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.StandardAreaRef;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoCardResponse;
import com.prodigalgal.ircs.content.video.dto.VideoAdminDtos.UnifiedVideoDetailResponse;
import com.prodigalgal.ircs.content.video.infrastructure.JdbcVideoAdminRepository.RawVideoSnapshot;
import com.prodigalgal.ircs.contracts.search.AdminVideoSearchRequest;
import com.prodigalgal.ircs.contracts.search.AdminVideoSearchResult;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
class VideoAdminQueryRepository {

    private static final int MAX_PAGE_SIZE = 100;
    private static final int FILTERED_EXACT_COUNT_PAGE_LIMIT = 3;
    private static final TypeReference<Set<String>> STRING_SET = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ContentCoverImageUrlResolver coverImageUrlResolver;
    private final AdminVideoSearchClient adminVideoSearchClient;

    Page<RawVideoCardResponse> findRawVideos(
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
        Pageable safe = sanitize(pageable);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", safe.getPageSize())
                .addValue("offset", safe.getOffset());
        String where = rawWhere(params, title, categoryId, enrichmentStatus, normalizationStatus, aggregationStatus, year, area,
                minScore, isMissingSlug, dataSourceId, sourceCategoryName, genre, language);
        Optional<Page<RawVideoCardResponse>> searchPage = rawSearchPage(
                safe,
                params,
                where,
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
        if (searchPage.isPresent()) {
            return searchPage.get();
        }
        List<RawVideoCardResponse> content = jdbcTemplate.query(
                rawCardSql(where) + orderBy(safe, "rv"),
                params,
                this::mapRawCard);
        long total = totalElements("raw_videos", rawCountSql(where), params, where, safe, content.size());
        return new PageImpl<>(content, safe, total);
    }

    Optional<RawVideoDetailResponse> findRawDetail(UUID id) {
        try {
            RawVideoDetailBase base = jdbcTemplate.queryForObject(
                    """
                    select rv.*, sc.id as resolved_category_id, sc.name as category_name,
                           rv.source_category_name as source_name,
                           ds.name as data_source_name, rv.data_source_id as resolved_data_source_id,
                           cast(rv.actor_names as varchar) as flat_actor_names,
                           cast(rv.director_names as varchar) as flat_director_names,
                           cast(rv.area_codes as varchar) as flat_area_codes,
                           cast(rv.language_codes as varchar) as flat_language_codes,
                           cast(rv.genre_codes as varchar) as flat_genre_codes,
                           ci.original_url as cover_original_url, ci.storage_path as cover_storage_path,
                           ci.storage_type as cover_storage_type, ci.status as cover_status,
                           sd.domain_value as cover_domain,
                           (
                               select rvu.unified_video_id
                               from raw_video_unified_video rvu
                               where rvu.raw_video_id = rv.id
                               limit 1
                           ) as unified_video_id
                    from raw_videos rv
                    left join standard_category sc on sc.slug = rv.category_code
                    left join data_sources ds on rv.data_source_id = ds.id
                    left join cover_images ci on rv.cover_image_id = ci.id
                    left join source_domains sd on ci.source_domain_id = sd.id
                    where rv.id = :id
                    """,
                    new MapSqlParameterSource("id", id),
                    this::mapRawDetailBase);
            return Optional.of(new RawVideoDetailResponse(
                    base.id(),
                    base.sourceVid(),
                    base.title(),
                    base.aliasTitle(),
                    base.season(),
                    base.subtitle(),
                    base.description(),
                    resolveCoverUrl(
                            base.coverStorageType(),
                            base.coverOriginalUrl(),
                            base.coverStoragePath(),
                            base.coverDomain()),
                    base.coverOriginalUrl(),
                    base.coverStoragePath(),
                    base.coverStorageType(),
                    base.coverStatus(),
                    base.year(),
                    resolveLanguageNames(base.languageCodesJson()),
                    base.rawLanguageStr(),
                    base.remarks(),
                    base.score(),
                    base.publishedAt(),
                    base.totalEpisodes(),
                    base.duration(),
                    base.doubanId(),
                    base.tmdbId(),
                    base.imdbId(),
                    base.rottenTomatoesId(),
                    base.categoryId(),
                    base.categoryName(),
                    base.sourceCategoryName(),
                    base.dataSourceName(),
                    base.dataSourceId(),
                    null,
                    base.unifiedVideoId(),
                    base.enrichmentStatus(),
                    base.normalizationStatus(),
                    base.createdAt(),
                    base.updatedAt(),
                    readStringSet(base.lockedFieldsJson()),
                    base.rawMetadata(),
                    Set.of(),
                    base.categoryCode(),
                    base.sourceCategoryCode(),
                    readJsonStringSet(base.actorNamesJson()),
                    readJsonStringSet(base.directorNamesJson()),
                    readJsonStringSet(base.areaCodesJson()),
                    readJsonStringSet(base.languageCodesJson()),
                    readJsonStringSet(base.genreCodesJson())));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    RawVideoSnapshot rawSnapshot(UUID id) {
        try {
            return jdbcTemplate.queryForObject(
                    """
                    select rv.id, rv.title, rv.alias_title, rv.year, rv.season, rv.data_hash,
                           (
                               select rvu.unified_video_id
                               from raw_video_unified_video rvu
                               where rvu.raw_video_id = rv.id
                               limit 1
                           ) as unified_video_id
                    from raw_videos rv
                    where rv.id = :id
                    """,
                    new MapSqlParameterSource("id", id),
                    (rs, rowNum) -> new RawVideoSnapshot(
                            rs.getObject("id", UUID.class),
                            rs.getString("title"),
                            rs.getString("alias_title"),
                            rs.getString("year"),
                            (Integer) rs.getObject("season"),
                            rs.getString("data_hash"),
                            rs.getObject("unified_video_id", UUID.class)));
        } catch (EmptyResultDataAccessException ex) {
            throw new ContentApiException(org.springframework.http.HttpStatus.NOT_FOUND, "Raw video not found: " + id);
        }
    }

    Page<UnifiedVideoCardResponse> findUnifiedVideos(
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
        Pageable safe = sanitize(pageable);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("limit", safe.getPageSize())
                .addValue("offset", safe.getOffset());
        String where = unifiedWhere(
                params,
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
        Optional<Page<UnifiedVideoCardResponse>> searchPage = unifiedSearchPage(
                safe,
                params,
                where,
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
        if (searchPage.isPresent()) {
            return searchPage.get();
        }
        List<UnifiedVideoCardResponse> page = jdbcTemplate.query(
                unifiedCardSql(where) + orderBy(safe, "uv"),
                params,
                this::mapUnifiedCard);
        List<UnifiedVideoCardResponse> content = withSourceCounts(page);
        long total = totalElements("unified_videos", unifiedCountSql(where), params, where, safe, content.size());
        return new PageImpl<>(content, safe, total);
    }

    Optional<UnifiedVideoDetailResponse> findUnifiedDetail(UUID id) {
                try {
            UnifiedVideoDetailBase base = jdbcTemplate.queryForObject(
                    """
                    select uv.*, sc.id as resolved_category_id, sc.name as category_name,
                           cast(uv.actor_names as varchar) as flat_actor_names,
                           cast(uv.director_names as varchar) as flat_director_names,
                           cast(uv.area_codes as varchar) as flat_area_codes,
                           cast(uv.language_codes as varchar) as flat_language_codes,
                           cast(uv.genre_codes as varchar) as flat_genre_codes,
                           cast(uv.adult_assessment as varchar) as adult_assessment_json,
                           ci.original_url as cover_original_url, ci.storage_path as cover_storage_path,
                           ci.storage_type as cover_storage_type, sd.domain_value as cover_domain
                    from unified_videos uv
                    left join standard_category sc on sc.slug = uv.category_code
                    left join cover_images ci on uv.cover_image_id = ci.id
                    left join source_domains sd on ci.source_domain_id = sd.id
                    where uv.id = :id
                    """,
                    new MapSqlParameterSource("id", id),
                    this::mapUnifiedDetailBase);
            return Optional.of(new UnifiedVideoDetailResponse(
                    base.id(),
                    base.title(),
                    base.aliasTitle(),
                    resolveCoverUrl(
                            base.coverStorageType(),
                            base.coverOriginalUrl(),
                            base.coverStoragePath(),
                            base.coverDomain()),
                    base.description(),
                    base.year(),
                    findStandardAreas(base.areaCodesJson()),
                    resolveLanguageNames(base.languageCodesJson()),
                    base.score(),
                    base.publishedAt(),
                    base.totalEpisodes(),
                    base.duration(),
                    base.remarks(),
                    base.season(),
                    base.subtitle(),
                    base.doubanId(),
                    base.tmdbId(),
                    base.imdbId(),
                    base.rottenTomatoesId(),
                    base.lastTrendAt(),
                     base.categoryId(),
                     base.categoryName(),
                     base.contentVisibility(),
                     base.metadataStatus(),
                     base.adultRestricted(),
                     readJsonObject(base.adultAssessmentJson()),
                     base.adultCheckedAt(),
                     readJsonStringSet(base.actorNamesJson()),
                    readJsonStringSet(base.directorNamesJson()),
                    resolveGenreNames(base.genreCodesJson()),
                    findUnifiedTags(id),
                    readStringSet(base.lockedFieldsJson()),
                    findRawCardsForUnified(id),
                    base.createdAt(),
                    base.updatedAt(),
                    base.categoryCode(),
                    readJsonStringSet(base.actorNamesJson()),
                    readJsonStringSet(base.directorNamesJson()),
                    readJsonStringSet(base.areaCodesJson()),
                    readJsonStringSet(base.languageCodesJson()),
                    readJsonStringSet(base.genreCodesJson())));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private String rawWhere(
            MapSqlParameterSource params,
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
        List<String> clauses = new ArrayList<>();
        if (StringUtils.hasText(title)) {
            clauses.add("rv.title ilike :title");
            params.addValue("title", "%" + title.trim() + "%");
        }
        if (categoryId != null) {
            clauses.add("rv.category_code = (select sc.slug from standard_category sc where sc.id = :categoryId)");
            params.addValue("categoryId", categoryId);
        }
        if (StringUtils.hasText(enrichmentStatus)) {
            clauses.add("rv.enrichment_status = :enrichmentStatus");
            params.addValue("enrichmentStatus", enrichmentStatus);
        }
        if (StringUtils.hasText(normalizationStatus)) {
            clauses.add("rv.normalization_status = :normalizationStatus");
            params.addValue("normalizationStatus", normalizationStatus);
        }
        if (StringUtils.hasText(aggregationStatus)) {
            clauses.add("rv.aggregation_status = :aggregationStatus");
            params.addValue("aggregationStatus", aggregationStatus);
        }
        if (StringUtils.hasText(year)) {
            clauses.add("rv.year = :year");
            params.addValue("year", year.trim());
        }
        if (minScore != null) {
            clauses.add("rv.score >= :minScore");
            params.addValue("minScore", minScore);
        }
        if (dataSourceId != null) {
            clauses.add("rv.data_source_id = :dataSourceId");
            params.addValue("dataSourceId", dataSourceId);
        }
        if (StringUtils.hasText(sourceCategoryName)) {
            clauses.add("coalesce(rv.source_category_name, rv.source_category_code) ilike :sourceCategoryName");
            params.addValue("sourceCategoryName", "%" + sourceCategoryName.trim() + "%");
        }
        if (StringUtils.hasText(area)) {
            clauses.add("""
                    exists (
                        select 1
                          from jsonb_array_elements_text(coalesce(rv.area_codes, '[]'::jsonb)) ac(code)
                          left join standard_areas sa on sa.code = ac.code
                         where lower(ac.code) like lower(:area)
                            or lower(coalesce(sa.name, '')) like lower(:area)
                    )
                    """);
            params.addValue("area", "%" + area.trim() + "%");
        }
        if (StringUtils.hasText(genre)) {
            clauses.add("""
                    exists (
                        select 1
                          from jsonb_array_elements_text(coalesce(rv.genre_codes, '[]'::jsonb)) gc(code)
                          left join standard_genre sg on sg.code = gc.code
                         where lower(gc.code) like lower(:genre)
                            or lower(coalesce(sg.name, '')) like lower(:genre)
                    )
                    """);
            params.addValue("genre", "%" + genre.trim() + "%");
        }
        if (StringUtils.hasText(language)) {
            clauses.add("""
                    (lower(rv.raw_language_str) like lower(:language)
                     or exists (
                        select 1
                          from jsonb_array_elements_text(coalesce(rv.language_codes, '[]'::jsonb)) lc(code)
                          left join standard_languages sl on sl.code = lc.code
                         where lower(lc.code) like lower(:language)
                            or lower(coalesce(sl.name, '')) like lower(:language)
                     ))
                    """);
            params.addValue("language", "%" + language.trim() + "%");
        }
        if (isMissingSlug != null) {
            clauses.add(Boolean.TRUE.equals(isMissingSlug)
                    ? "(rv.category_code is null or rv.category_code = '')"
                    : "(rv.category_code is not null and rv.category_code <> '')");
        }
        return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    private Optional<Page<RawVideoCardResponse>> rawSearchPage(
            Pageable pageable,
            MapSqlParameterSource params,
            String where,
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
        if (!rawSearchSupported(title, area, sourceCategoryName, genre, language)) {
            return Optional.empty();
        }
        if (!hasRawExactSearchCriteria(
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
                language)) {
            return Optional.empty();
        }
        Optional<Page<RawVideoCardResponse>> emptyStatusPage = rawStatusOnlyEmptyPage(
                pageable,
                where,
                params,
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
        if (emptyStatusPage.isPresent()) {
            return emptyStatusPage;
        }
        AdminVideoSearchRequest request = new AdminVideoSearchRequest(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                primarySort(pageable),
                primaryDirection(pageable),
                trimToNull(title),
                categoryId,
                null,
                enrichmentStatus,
                normalizationStatus,
                aggregationStatus,
                null,
                year,
                trimToNull(area),
                minScore,
                isMissingSlug,
                null,
                null,
                null,
                dataSourceId,
                trimToNull(sourceCategoryName),
                trimToNull(genre),
                trimToNull(language),
                null,
                null);
        return adminVideoSearchClient.searchRawIds(request)
                .flatMap(result -> rawSearchResultPage(result, pageable, where, params));
    }

    private Optional<Page<RawVideoCardResponse>> rawStatusOnlyEmptyPage(
            Pageable pageable,
            String where,
            MapSqlParameterSource params,
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
        if (!isRawStatusOnlyFilter(
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
                language)) {
            return Optional.empty();
        }
        return hasAnyDatabaseRow("raw_videos rv", where, params)
                ? Optional.empty()
                : Optional.of(Page.empty(pageable));
    }

    private boolean isRawStatusOnlyFilter(
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
        boolean hasStatus = StringUtils.hasText(enrichmentStatus)
                || StringUtils.hasText(normalizationStatus)
                || StringUtils.hasText(aggregationStatus);
        return hasStatus
                && !StringUtils.hasText(title)
                && categoryId == null
                && !StringUtils.hasText(year)
                && !StringUtils.hasText(area)
                && minScore == null
                && isMissingSlug == null
                && dataSourceId == null
                && !StringUtils.hasText(sourceCategoryName)
                && !StringUtils.hasText(genre)
                && !StringUtils.hasText(language);
    }

    private boolean rawSearchSupported(
            String title,
            String area,
            String sourceCategoryName,
            String genre,
            String language) {
        return exactCodeFilter(area)
                && exactCodeFilter(genre)
                && exactCodeFilter(language);
    }

    private boolean hasRawExactSearchCriteria(
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
        return categoryId != null
                || StringUtils.hasText(title)
                || StringUtils.hasText(enrichmentStatus)
                || StringUtils.hasText(normalizationStatus)
                || StringUtils.hasText(aggregationStatus)
                || StringUtils.hasText(year)
                || StringUtils.hasText(area)
                || StringUtils.hasText(genre)
                || StringUtils.hasText(language)
                || minScore != null
                || isMissingSlug != null
                || dataSourceId != null
                || StringUtils.hasText(sourceCategoryName);
    }

    private Optional<Page<RawVideoCardResponse>> rawSearchResultPage(
            AdminVideoSearchResult result,
            Pageable pageable,
            String where,
            MapSqlParameterSource params) {
        if (result.ids().isEmpty() && hasAnyDatabaseRow("raw_videos rv", where, params)) {
            return Optional.empty();
        }
        return Optional.of(new PageImpl<>(
                findRawCardsByIds(result.ids()),
                pageable,
                Math.max(pageable.getOffset() + result.ids().size(), result.total())));
    }

    private String rawCountSql(String where) {
        return """
                select count(*)
                from raw_videos rv
                """ + where;
    }

    private String rawCardSql(String where) {
        return """
                select rv.id, rv.source_vid, rv.title, rv.alias_title, rv.season, rv.subtitle,
                       rv.year, rv.score, rv.total_episodes, rv.duration,
                       sc.name as category_name, rv.category_code, rv.source_category_code,
                       cast(rv.area_codes as varchar) as area_codes,
                       cast(rv.language_codes as varchar) as language_codes,
                       cast(rv.genre_codes as varchar) as genre_codes,
                       coalesce(rv.source_category_name, rv.source_category_code) as source_name, ds.name as data_source_name,
                       rv.enrichment_status, rv.normalization_status, rv.aggregation_status, rv.created_at, rv.updated_at,
                       ci.original_url as cover_original_url, ci.storage_path as cover_storage_path,
                       ci.storage_type as cover_storage_type, sd.domain_value as cover_domain,
                       (
                           select string_agg(coalesce(sa.name, ac.code), ', ' order by coalesce(sa.name, ac.code))
                           from jsonb_array_elements_text(coalesce(rv.area_codes, '[]'::jsonb)) ac(code)
                           left join standard_areas sa on sa.code = ac.code
                       ) as area
                from raw_videos rv
                left join standard_category sc on sc.slug = rv.category_code
                left join data_sources ds on rv.data_source_id = ds.id
                left join cover_images ci on rv.cover_image_id = ci.id
                left join source_domains sd on ci.source_domain_id = sd.id
                """ + where;
    }

    private String unifiedWhere(
            MapSqlParameterSource params,
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
        List<String> clauses = new ArrayList<>();
        if (StringUtils.hasText(title)) {
            clauses.add("uv.title ilike :title");
            params.addValue("title", "%" + title.trim() + "%");
        }
        if (categoryId != null) {
            clauses.add("uv.category_code = (select sc.slug from standard_category sc where sc.id = :categoryId)");
            params.addValue("categoryId", categoryId);
        }
        if (StringUtils.hasText(year)) {
            clauses.add("uv.year = :year");
            params.addValue("year", year.trim());
        }
        if (minScore != null) {
            clauses.add("uv.score >= :minScore");
            params.addValue("minScore", minScore);
        }
        if (StringUtils.hasText(area)) {
            clauses.add("""
                    exists (
                        select 1
                          from jsonb_array_elements_text(coalesce(uv.area_codes, '[]'::jsonb)) ac(code)
                          left join standard_areas sa on sa.code = ac.code
                         where lower(ac.code) like lower(:area)
                            or lower(coalesce(sa.name, '')) like lower(:area)
                    )
                    """);
            params.addValue("area", "%" + area.trim() + "%");
        }
        if (hasDoubanId != null) {
            clauses.add(Boolean.TRUE.equals(hasDoubanId)
                    ? "(uv.douban_id is not null and uv.douban_id <> '')"
                    : "(uv.douban_id is null or uv.douban_id = '')");
        }
        if (hasTmdbId != null) {
            clauses.add(Boolean.TRUE.equals(hasTmdbId)
                    ? "(uv.tmdb_id is not null and uv.tmdb_id <> '')"
                    : "(uv.tmdb_id is null or uv.tmdb_id = '')");
        }
        if (StringUtils.hasText(contentVisibility)) {
            clauses.add("uv.content_visibility = :contentVisibility");
            params.addValue("contentVisibility", normalizeContentVisibility(contentVisibility));
        }
        if (StringUtils.hasText(metadataStatus)) {
            clauses.add("uv.metadata_status = :metadataStatus");
            params.addValue("metadataStatus", metadataStatus.trim().toUpperCase(java.util.Locale.ROOT));
        }
        if (StringUtils.hasText(genre)) {
            clauses.add("""
                    exists (
                        select 1
                          from jsonb_array_elements_text(coalesce(uv.genre_codes, '[]'::jsonb)) gc(code)
                          left join standard_genre sg on sg.code = gc.code
                         where lower(gc.code) like lower(:genre)
                            or lower(coalesce(sg.name, '')) like lower(:genre)
                    )
                    """);
            params.addValue("genre", "%" + genre.trim() + "%");
        }
        if (StringUtils.hasText(language)) {
            clauses.add("""
                    exists (
                        select 1
                          from jsonb_array_elements_text(coalesce(uv.language_codes, '[]'::jsonb)) lc(code)
                          left join standard_languages sl on sl.code = lc.code
                         where lower(lc.code) like lower(:language)
                            or lower(coalesce(sl.name, '')) like lower(:language)
                    )
                    """);
            params.addValue("language", "%" + language.trim() + "%");
        }
        if (StringUtils.hasText(actor)) {
            clauses.add("""
                    exists (
                        select 1
                          from jsonb_array_elements_text(coalesce(uv.actor_names, '[]'::jsonb)) a(name)
                         where lower(a.name) like lower(:actor)
                    )
                    """);
            params.addValue("actor", "%" + actor.trim() + "%");
        }
        if (StringUtils.hasText(director)) {
            clauses.add("""
                    exists (
                        select 1
                          from jsonb_array_elements_text(coalesce(uv.director_names, '[]'::jsonb)) d(name)
                         where lower(d.name) like lower(:director)
                    )
                    """);
            params.addValue("director", "%" + director.trim() + "%");
        }
        return clauses.isEmpty() ? "" : " where " + String.join(" and ", clauses);
    }

    private Optional<Page<UnifiedVideoCardResponse>> unifiedSearchPage(
            Pageable pageable,
            MapSqlParameterSource params,
            String where,
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
        if (!unifiedSearchSupported(title, area, genre, language, actor, director)) {
            return Optional.empty();
        }
        if (StringUtils.hasText(year) && !isNumericYear(year)) {
            return Optional.empty();
        }
        if (!hasUnifiedExactSearchCriteria(
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
                director)) {
            return Optional.empty();
        }
        String normalizedVisibility = StringUtils.hasText(contentVisibility)
                ? normalizeContentVisibility(contentVisibility)
                : null;
        String normalizedMetadataStatus = StringUtils.hasText(metadataStatus)
                ? metadataStatus.trim().toUpperCase(java.util.Locale.ROOT)
                : null;
        AdminVideoSearchRequest request = new AdminVideoSearchRequest(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                primarySort(pageable),
                primaryDirection(pageable),
                trimToNull(title),
                categoryId,
                null,
                null,
                null,
                null,
                normalizedMetadataStatus,
                year,
                trimToNull(area),
                minScore,
                null,
                hasDoubanId,
                hasTmdbId,
                normalizedVisibility,
                null,
                null,
                trimToNull(genre),
                trimToNull(language),
                trimToNull(actor),
                trimToNull(director));
        return adminVideoSearchClient.searchUnifiedIds(request)
                .flatMap(result -> unifiedSearchResultPage(result, pageable, where, params));
    }

    private boolean unifiedSearchSupported(
            String title,
            String area,
            String genre,
            String language,
            String actor,
            String director) {
        return exactCodeFilter(area)
                && exactCodeFilter(genre)
                && exactCodeFilter(language);
    }

    private boolean hasUnifiedExactSearchCriteria(
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
        return categoryId != null
                || StringUtils.hasText(title)
                || StringUtils.hasText(year)
                || StringUtils.hasText(area)
                || minScore != null
                || hasDoubanId != null
                || hasTmdbId != null
                || StringUtils.hasText(contentVisibility)
                || StringUtils.hasText(metadataStatus)
                || StringUtils.hasText(genre)
                || StringUtils.hasText(language)
                || StringUtils.hasText(actor)
                || StringUtils.hasText(director);
    }

    private Optional<Page<UnifiedVideoCardResponse>> unifiedSearchResultPage(
            AdminVideoSearchResult result,
            Pageable pageable,
            String where,
            MapSqlParameterSource params) {
        if (result.ids().isEmpty() && hasAnyDatabaseRow("unified_videos uv", where, params)) {
            return Optional.empty();
        }
        return Optional.of(new PageImpl<>(
                withSourceCounts(findUnifiedCardsByIds(result.ids())),
                pageable,
                Math.max(pageable.getOffset() + result.ids().size(), result.total())));
    }

    private boolean isNumericYear(String year) {
        return year != null && year.trim().matches("\\d+");
    }

    private boolean exactCodeFilter(String value) {
        return !StringUtils.hasText(value) || value.trim().matches("[A-Za-z0-9_.:-]{1,64}");
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String unifiedCountSql(String where) {
        return """
                select count(*)
                from unified_videos uv
                """ + where;
    }

    private String unifiedCardSql(String where) {
        return """
                select uv.id, uv.title, uv.alias_title, uv.year, uv.score, uv.remarks,
                       uv.season, uv.subtitle, uv.last_trend_at, uv.created_at, uv.updated_at,
                       uv.douban_id, uv.tmdb_id, uv.imdb_id, uv.content_visibility, uv.metadata_status,
                       coalesce(uv.adult_restricted, false) as adult_restricted,
                       uv.adult_checked_at,
                       sc.name as category_name, uv.category_code, cast(uv.area_codes as varchar) as area_codes,
                       ci.original_url as cover_original_url, ci.storage_path as cover_storage_path,
                       ci.storage_type as cover_storage_type, sd.domain_value as cover_domain,
                       (
                           select string_agg(coalesce(sa.name, ac.code), ', ' order by coalesce(sa.name, ac.code))
                           from jsonb_array_elements_text(coalesce(uv.area_codes, '[]'::jsonb)) ac(code)
                           left join standard_areas sa on sa.code = ac.code
                       ) as area
                from unified_videos uv
                left join standard_category sc on sc.slug = uv.category_code
                left join cover_images ci on uv.cover_image_id = ci.id
                left join source_domains sd on ci.source_domain_id = sd.id
                """ + where;
    }

    private List<RawVideoCardResponse> findRawCardsByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<RawVideoCardResponse> rows = jdbcTemplate.query(
                rawCardSql(" where rv.id in (:ids)"),
                new MapSqlParameterSource("ids", ids),
                this::mapRawCard);
        return orderByIds(rows, ids, RawVideoCardResponse::id);
    }

    private List<UnifiedVideoCardResponse> findUnifiedCardsByIds(List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<UnifiedVideoCardResponse> rows = jdbcTemplate.query(
                unifiedCardSql(" where uv.id in (:ids)"),
                new MapSqlParameterSource("ids", ids),
                this::mapUnifiedCard);
        return orderByIds(rows, ids, UnifiedVideoCardResponse::id);
    }

    private <T> List<T> orderByIds(List<T> rows, List<UUID> ids, java.util.function.Function<T, UUID> idExtractor) {
        Map<UUID, T> byId = new LinkedHashMap<>();
        rows.forEach(row -> byId.put(idExtractor.apply(row), row));
        List<T> ordered = new ArrayList<>();
        ids.forEach(id -> {
            T row = byId.get(id);
            if (row != null) {
                ordered.add(row);
            }
        });
        return ordered;
    }

    private String orderBy(Pageable pageable, String alias) {
        if (pageable.getSort().isUnsorted()) {
            return " order by " + alias + ".updated_at desc limit :limit offset :offset";
        }
        List<String> allowed = List.of("createdAt", "updatedAt", "title", "year", "score");
        List<String> orders = pageable.getSort().stream()
                .filter(order -> allowed.contains(order.getProperty()))
                .map(order -> alias + "." + toColumn(order.getProperty()) + " " + order.getDirection().name())
                .toList();
        if (orders.isEmpty()) {
            return " order by " + alias + ".updated_at desc limit :limit offset :offset";
        }
        return " order by " + String.join(", ", orders) + " limit :limit offset :offset";
    }

    private String primarySort(Pageable pageable) {
        return pageable.getSort().stream()
                .findFirst()
                .map(org.springframework.data.domain.Sort.Order::getProperty)
                .orElse("updatedAt");
    }

    private String primaryDirection(Pageable pageable) {
        return pageable.getSort().stream()
                .findFirst()
                .map(order -> order.getDirection().name())
                .orElse("DESC");
    }

    private String toColumn(String property) {
        return switch (property) {
            case "createdAt" -> "created_at";
            case "updatedAt" -> "updated_at";
            default -> property;
        };
    }

    private RawVideoCardResponse mapRawCard(ResultSet rs, int rowNum) throws SQLException {
        return new RawVideoCardResponse(
                rs.getObject("id", UUID.class),
                rs.getString("source_vid"),
                rs.getString("title"),
                rs.getString("alias_title"),
                (Integer) rs.getObject("season"),
                rs.getString("subtitle"),
                resolveCoverUrl(
                        rs.getString("cover_storage_type"),
                        rs.getString("cover_original_url"),
                        rs.getString("cover_storage_path"),
                        rs.getString("cover_domain")),
                rs.getString("year"),
                rs.getString("area"),
                rs.getBigDecimal("score"),
                rs.getString("total_episodes"),
                rs.getString("duration"),
                rs.getString("category_name"),
                rs.getString("source_name"),
                rs.getString("data_source_name"),
                rs.getString("enrichment_status"),
                rs.getString("normalization_status"),
                rs.getString("aggregation_status"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getString("category_code"),
                rs.getString("source_category_code"),
                readJsonStringSet(rs.getString("area_codes")),
                readJsonStringSet(rs.getString("language_codes")),
                readJsonStringSet(rs.getString("genre_codes")));
    }

    private UnifiedVideoCardResponse mapUnifiedCard(ResultSet rs, int rowNum) throws SQLException {
        return new UnifiedVideoCardResponse(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("alias_title"),
                resolveCoverUrl(
                        rs.getString("cover_storage_type"),
                        rs.getString("cover_original_url"),
                        rs.getString("cover_storage_path"),
                        rs.getString("cover_domain")),
                rs.getString("year"),
                rs.getString("area"),
                rs.getBigDecimal("score"),
                rs.getString("category_name"),
                rs.getString("remarks"),
                (Integer) rs.getObject("season"),
                 rs.getString("subtitle"),
                 rs.getString("content_visibility"),
                 rs.getString("metadata_status"),
                 rs.getBoolean("adult_restricted"),
                 instant(rs, "adult_checked_at"),
                 instant(rs, "last_trend_at"),
                0,
                StringUtils.hasText(rs.getString("imdb_id")),
                StringUtils.hasText(rs.getString("douban_id")),
                StringUtils.hasText(rs.getString("tmdb_id")),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getString("category_code"),
                readJsonStringSet(rs.getString("area_codes")));
    }

    private List<UnifiedVideoCardResponse> withSourceCounts(List<UnifiedVideoCardResponse> page) {
        if (page.isEmpty()) {
            return page;
        }
        List<UUID> ids = page.stream().map(UnifiedVideoCardResponse::id).toList();
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                select unified_video_id, cast(count(*) as integer) as source_count
                  from raw_video_unified_video
                 where unified_video_id in (:ids)
                 group by unified_video_id
                """,
                new MapSqlParameterSource("ids", ids),
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> counts.put(rs.getObject("unified_video_id", UUID.class), rs.getInt("source_count")));
        return page.stream()
                .map(video -> withSourceCount(video, counts.getOrDefault(video.id(), 0)))
                .toList();
    }

    private UnifiedVideoCardResponse withSourceCount(UnifiedVideoCardResponse video, int sourceCount) {
        return new UnifiedVideoCardResponse(
                video.id(),
                video.title(),
                video.aliasTitle(),
                video.coverImageUrl(),
                video.year(),
                video.area(),
                video.score(),
                video.categoryName(),
                video.remarks(),
                 video.season(),
                 video.subtitle(),
                 video.contentVisibility(),
                 video.metadataStatus(),
                 video.adultRestricted(),
                 video.adultCheckedAt(),
                 video.lastTrendAt(),
                sourceCount,
                video.hasImdb(),
                video.hasDouban(),
                video.hasTmdb(),
                video.createdAt(),
                video.updatedAt(),
                video.categoryCode(),
                video.areaCodes());
    }

    private RawVideoDetailBase mapRawDetailBase(ResultSet rs, int rowNum) throws SQLException {
        return new RawVideoDetailBase(
                rs.getObject("id", UUID.class),
                rs.getString("source_vid"),
                rs.getString("title"),
                rs.getString("alias_title"),
                (Integer) rs.getObject("season"),
                rs.getString("subtitle"),
                rs.getString("description"),
                rs.getString("cover_original_url"),
                rs.getString("cover_storage_path"),
                rs.getString("cover_storage_type"),
                rs.getString("cover_status"),
                rs.getString("cover_domain"),
                rs.getString("year"),
                rs.getString("raw_language_str"),
                rs.getString("remarks"),
                rs.getBigDecimal("score"),
                rs.getDate("published_at") == null ? null : rs.getDate("published_at").toLocalDate(),
                rs.getString("total_episodes"),
                rs.getString("duration"),
                rs.getString("douban_id"),
                rs.getString("tmdb_id"),
                rs.getString("imdb_id"),
                rs.getString("rotten_tomatoes_id"),
                rs.getObject("resolved_category_id", UUID.class),
                rs.getString("category_name"),
                rs.getString("source_name"),
                rs.getString("category_code"),
                rs.getString("source_category_code"),
                rs.getString("data_source_name"),
                rs.getObject("resolved_data_source_id", UUID.class),
                rs.getObject("unified_video_id", UUID.class),
                rs.getString("enrichment_status"),
                rs.getString("normalization_status"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getString("locked_fields"),
                rs.getString("raw_metadata"),
                rs.getString("flat_actor_names"),
                rs.getString("flat_director_names"),
                rs.getString("flat_area_codes"),
                rs.getString("flat_language_codes"),
                rs.getString("flat_genre_codes"));
    }

    private UnifiedVideoDetailBase mapUnifiedDetailBase(ResultSet rs, int rowNum) throws SQLException {
        return new UnifiedVideoDetailBase(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("alias_title"),
                rs.getString("cover_original_url"),
                rs.getString("cover_storage_path"),
                rs.getString("cover_storage_type"),
                rs.getString("cover_domain"),
                rs.getString("description"),
                rs.getString("year"),
                rs.getBigDecimal("score"),
                rs.getDate("published_at") == null ? null : rs.getDate("published_at").toLocalDate(),
                rs.getString("total_episodes"),
                rs.getString("duration"),
                rs.getString("remarks"),
                (Integer) rs.getObject("season"),
                rs.getString("subtitle"),
                rs.getString("douban_id"),
                rs.getString("tmdb_id"),
                rs.getString("imdb_id"),
                rs.getString("rotten_tomatoes_id"),
                instant(rs, "last_trend_at"),
                rs.getObject("category_id", UUID.class),
                rs.getString("category_name"),
                 rs.getString("category_code"),
                 rs.getString("content_visibility"),
                rs.getString("metadata_status"),
                rs.getBoolean("adult_restricted"),
                rs.getString("adult_assessment_json"),
                instant(rs, "adult_checked_at"),
                rs.getString("locked_fields"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getString("flat_actor_names"),
                rs.getString("flat_director_names"),
                rs.getString("flat_area_codes"),
                rs.getString("flat_language_codes"),
                rs.getString("flat_genre_codes"));
    }

    private List<RawVideoCardResponse> findRawCardsForUnified(UUID unifiedVideoId) {
        return jdbcTemplate.query(
                rawCardSql(" where exists (select 1 from raw_video_unified_video rvu where rvu.raw_video_id = rv.id and rvu.unified_video_id = :id)")
                        + " order by rv.updated_at desc",
                new MapSqlParameterSource("id", unifiedVideoId),
                this::mapRawCard);
    }

    private Set<String> findUnifiedTags(UUID unifiedVideoId) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                """
                select tag
                  from unified_video_tags
                 where unified_video_id = :id
                 order by tag
                """,
                new MapSqlParameterSource("id", unifiedVideoId),
                String.class));
    }

    private Set<String> resolveGenreNames(String genreCodesJson) {
        return resolveNamesByCodes("standard_genre", "code", "name", readJsonStringSet(genreCodesJson));
    }

    private Set<String> resolveLanguageNames(String languageCodesJson) {
        return resolveNamesByCodes("standard_languages", "code", "name", readJsonStringSet(languageCodesJson));
    }

    private Set<String> resolveNamesByCodes(String table, String codeColumn, String nameColumn, Set<String> codes) {
        Set<String> normalizedCodes = JsonStringArrays.normalize(codes);
        if (normalizedCodes.isEmpty()) {
            return Set.of();
        }
        Map<String, String> namesByCode = new LinkedHashMap<>();
        jdbcTemplate.query(
                "select %s as code, %s as name from %s where %s in (:codes)"
                        .formatted(codeColumn, nameColumn, table, codeColumn),
                new MapSqlParameterSource("codes", normalizedCodes),
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> namesByCode.put(rs.getString("code"), rs.getString("name")));
        Set<String> resolved = new LinkedHashSet<>();
        for (String code : normalizedCodes) {
            String name = namesByCode.get(code);
            resolved.add(StringUtils.hasText(name) ? name : code);
        }
        return resolved;
    }

    private Set<StandardAreaRef> findStandardAreas(String areaCodesJson) {
        Set<String> areaCodes = JsonStringArrays.normalize(readJsonStringSet(areaCodesJson));
        if (areaCodes.isEmpty()) {
            return Set.of();
        }
        Map<String, StandardAreaRef> refsByCode = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                select id, name, code, region, created_at, updated_at
                  from standard_areas
                 where code in (:codes)
                """,
                new MapSqlParameterSource("codes", areaCodes),
                (org.springframework.jdbc.core.RowCallbackHandler)
                        rs -> refsByCode.put(
                                rs.getString("code"),
                                new StandardAreaRef(
                                        rs.getObject("id", UUID.class),
                                        rs.getString("name"),
                                        rs.getString("code"),
                                        rs.getString("region"),
                                        instant(rs, "created_at"),
                                        instant(rs, "updated_at"))));
        Set<StandardAreaRef> resolved = new LinkedHashSet<>();
        for (String code : areaCodes) {
            resolved.add(refsByCode.getOrDefault(code, new StandardAreaRef(null, code, code, null, null, null)));
        }
        return resolved;
    }

    private Pageable sanitize(Pageable pageable) {
        int page = Math.max(0, pageable.getPageNumber());
        int size = Math.min(Math.max(1, pageable.getPageSize()), MAX_PAGE_SIZE);
        return PageRequest.of(page, size, pageable.getSort());
    }

    private long totalElements(
            String tableName,
            String exactCountSql,
            MapSqlParameterSource params,
            String where,
            Pageable pageable,
            int contentSize) {
        long observed = pageable.getOffset() + contentSize;
        if (StringUtils.hasText(where)) {
            if (shouldUseExactFilteredCount(pageable, contentSize)) {
                return Math.max(observed, exactCount(exactCountSql, params));
            }
            return estimatedTotal(pageable, contentSize);
        }
        try {
            Long approximate = jdbcTemplate.queryForObject(
                    """
                    select coalesce(max(n_live_tup), 0)
                      from pg_stat_user_tables
                     where relname = :tableName
                    """,
                    new MapSqlParameterSource("tableName", tableName),
                    Long.class);
            return Math.max(observed, approximate == null ? 0 : approximate);
        } catch (DataAccessException ignored) {
            return Math.max(observed, exactCount(exactCountSql, params));
        }
    }

    private long exactCount(String sql, MapSqlParameterSource params) {
        Long total = jdbcTemplate.queryForObject(sql, params, Long.class);
        return total == null ? 0 : total;
    }

    private boolean hasAnyDatabaseRow(String fromClause, String where, MapSqlParameterSource params) {
        try {
            Integer found = jdbcTemplate.queryForObject(
                    "select 1 from " + fromClause + where + " limit 1",
                    params,
                    Integer.class);
            return found != null;
        } catch (EmptyResultDataAccessException ignored) {
            return false;
        } catch (DataAccessException ignored) {
            return true;
        }
    }

    private boolean shouldUseExactFilteredCount(Pageable pageable, int contentSize) {
        return pageable.getPageNumber() < FILTERED_EXACT_COUNT_PAGE_LIMIT
                && contentSize < pageable.getPageSize();
    }

    private long estimatedTotal(Pageable pageable, int contentSize) {
        long observed = pageable.getOffset() + contentSize;
        return contentSize >= pageable.getPageSize() ? observed + 1 : observed;
    }

    private Set<String> readStringSet(String json) {
        if (!StringUtils.hasText(json)) {
            return Set.of();
        }
        try {
            return objectMapper.readValue(json, STRING_SET);
        } catch (Exception ignored) {
            return Set.of();
        }
    }

    private Set<String> readJsonStringSet(String json) {
        return JsonStringArrays.readSet(objectMapper, json);
    }

    private JsonNode readJsonObject(String json) {
        try {
            return objectMapper.readTree(StringUtils.hasText(json) ? json : "{}");
        } catch (Exception ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String resolveCoverUrl(String storageType, String originalUrl, String storagePath, String domain) {
        return coverImageUrlResolver.resolve(storageType, originalUrl, storagePath, domain);
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private record RawVideoDetailBase(
            UUID id,
            String sourceVid,
            String title,
            String aliasTitle,
            Integer season,
            String subtitle,
            String description,
            String coverOriginalUrl,
            String coverStoragePath,
            String coverStorageType,
            String coverStatus,
            String coverDomain,
            String year,
            String rawLanguageStr,
            String remarks,
            BigDecimal score,
            java.time.LocalDate publishedAt,
            String totalEpisodes,
            String duration,
            String doubanId,
            String tmdbId,
            String imdbId,
            String rottenTomatoesId,
            UUID categoryId,
            String categoryName,
            String sourceCategoryName,
            String categoryCode,
            String sourceCategoryCode,
            String dataSourceName,
            UUID dataSourceId,
            UUID unifiedVideoId,
            String enrichmentStatus,
            String normalizationStatus,
            Instant createdAt,
            Instant updatedAt,
            String lockedFieldsJson,
            String rawMetadata,
            String actorNamesJson,
            String directorNamesJson,
            String areaCodesJson,
            String languageCodesJson,
            String genreCodesJson) {
    }

    private record UnifiedVideoDetailBase(
            UUID id,
            String title,
            String aliasTitle,
            String coverOriginalUrl,
            String coverStoragePath,
            String coverStorageType,
            String coverDomain,
            String description,
            String year,
            BigDecimal score,
            java.time.LocalDate publishedAt,
            String totalEpisodes,
            String duration,
            String remarks,
            Integer season,
            String subtitle,
            String doubanId,
            String tmdbId,
            String imdbId,
            String rottenTomatoesId,
            Instant lastTrendAt,
            UUID categoryId,
             String categoryName,
             String categoryCode,
             String contentVisibility,
             String metadataStatus,
             boolean adultRestricted,
             String adultAssessmentJson,
             Instant adultCheckedAt,
             String lockedFieldsJson,
            Instant createdAt,
            Instant updatedAt,
            String actorNamesJson,
            String directorNamesJson,
            String areaCodesJson,
            String languageCodesJson,
            String genreCodesJson) {
    }
}

package com.prodigalgal.ircs.search.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.metadata.JsonStringArrays;
import com.prodigalgal.ircs.search.document.RawVideoSearchDocument;
import com.prodigalgal.ircs.search.document.UnifiedVideoSearchDocument;
import com.prodigalgal.ircs.search.support.CoverImageUrlResolver;
import com.prodigalgal.ircs.search.support.SearchTextHelper;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
@RequiredArgsConstructor
public class SearchDocumentJdbcRepository {

    private static final String RAW_VIDEO_SQL = """
            SELECT rv.id,
                   rv.title,
                   rv.alias_title,
                   rv.description,
                   rv.source_vid,
                   rv.year,
                   rv.score,
                   rv.total_episodes,
                   rv.duration,
                   rv.season,
                   rv.subtitle,
                   rv.douban_id,
                   rv.tmdb_id,
                   rv.imdb_id,
                   rv.rotten_tomatoes_id,
                   rv.raw_language_str,
                   rv.enrichment_status,
                   rv.normalization_status,
                   rv.created_at,
                   rv.updated_at,
                   cast(rv.actor_names as varchar) AS actor_names,
                   cast(rv.director_names as varchar) AS director_names,
                   cast(rv.area_codes as varchar) AS area_codes,
                   cast(rv.language_codes as varchar) AS language_codes,
                   cast(rv.genre_codes as varchar) AS genre_codes,
                   ds.name AS data_source_name,
                   ds.id AS data_source_id,
                   rv.category_code,
                   rv.source_category_code,
                   COALESCE(rv.source_category_name, rv.source_category_code) AS source_category_name,
                   sc.name AS category_name,
                   sc.id AS category_id,
                   rv.aggregation_status,
                   ci.storage_type AS cover_storage_type,
                   ci.status AS cover_status,
                   ci.original_url AS cover_original_url,
                   ci.storage_path AS cover_storage_path,
                   sd.domain_value AS cover_source_domain
              FROM raw_videos rv
              LEFT JOIN data_sources ds ON rv.data_source_id = ds.id
              LEFT JOIN standard_category sc ON sc.slug = rv.category_code
              LEFT JOIN cover_images ci ON rv.cover_image_id = ci.id
              LEFT JOIN source_domains sd ON ci.source_domain_id = sd.id
             WHERE rv.id = :id
            """;

    private static final String UNIFIED_VIDEO_SQL = """
            SELECT uv.id,
                   uv.title,
                   uv.alias_title,
                   uv.description,
                   uv.year,
                   uv.score,
                   uv.published_at,
                   uv.total_episodes,
                   uv.duration,
                   uv.remarks,
                   uv.season,
                   uv.subtitle,
                   uv.douban_id,
                   uv.tmdb_id,
                   uv.imdb_id,
                   uv.rotten_tomatoes_id,
                   uv.last_trend_at,
                   uv.created_at,
                   uv.updated_at,
                   cast(uv.actor_names as varchar) AS actor_names,
                   cast(uv.director_names as varchar) AS director_names,
                   cast(uv.area_codes as varchar) AS area_codes,
                   cast(uv.language_codes as varchar) AS language_codes,
                   cast(uv.genre_codes as varchar) AS genre_codes,
                   uv.category_code AS category_slug,
                   sc.name AS category_name,
                   sc.id AS category_id,
                   uv.content_visibility,
                   uv.metadata_status,
                   coalesce(uv.adult_restricted, false) AS adult_restricted,
                   coalesce((uv.adult_assessment ->> 'adultRestricted')::boolean, false) AS adult_assessment_restricted,
                   upper(coalesce(nullif(uv.adult_assessment ->> 'level', ''), 'SAFE')) AS adult_assessment_level,
                   uv.adult_checked_at,
                   ci.storage_type AS cover_storage_type,
                   ci.original_url AS cover_original_url,
                   ci.storage_path AS cover_storage_path,
                   sd.domain_value AS cover_source_domain
              FROM unified_videos uv
              LEFT JOIN standard_category sc ON sc.slug = uv.category_code
              LEFT JOIN cover_images ci ON uv.cover_image_id = ci.id
              LEFT JOIN source_domains sd ON ci.source_domain_id = sd.id
             WHERE uv.id = :id
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CoverImageUrlResolver coverImageUrlResolver;
    private final ObjectMapper objectMapper;

    @Value("${app.search.sync.jdbc-query-timeout-seconds:30}")
    private int queryTimeoutSeconds;

    @PostConstruct
    void configureQueryTimeout() {
        jdbcTemplate.getJdbcTemplate().setQueryTimeout(Math.max(1, queryTimeoutSeconds));
    }

    public List<UUID> findNextRawVideoIds(UUID afterId, int limit) {
        return findNextIds("raw_videos", afterId, limit);
    }

    public List<UUID> findNextUnifiedVideoIds(UUID afterId, int limit) {
        return findNextIds("unified_videos", afterId, limit);
    }

    public Optional<RawVideoSearchDocument> findRawVideo(UUID id) {
        RawVideoSearchDocument doc = DataAccessUtils.singleResult(
                jdbcTemplate.query(RAW_VIDEO_SQL, Map.of("id", id), new RawVideoRowMapper()));
        if (doc == null) {
            return Optional.empty();
        }

        StringBuilder metadata = new StringBuilder();
        append(metadata, doc.getTitle());
        append(metadata, doc.getAliasTitle());
        append(metadata, doc.getDescriptionForMetadata());
        appendAll(metadata, doc.getActors());
        appendAll(metadata, doc.getDirectors());
        append(metadata, doc.getSubtitle());
        doc.setMetadata(metadata.toString().trim());
        return Optional.of(doc);
    }

    private List<UUID> findNextIds(String tableName, UUID afterId, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("limit", limit);
        String predicate = "";
        if (afterId != null) {
            predicate = " WHERE id > :afterId";
            params.put("afterId", afterId);
        }
        return jdbcTemplate.query(
                "SELECT id FROM " + tableName + predicate + " ORDER BY id ASC LIMIT :limit",
                params,
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    public Optional<UnifiedVideoSearchDocument> findUnifiedVideo(UUID id) {
        UnifiedVideoSearchDocument doc = DataAccessUtils.singleResult(
                jdbcTemplate.query(UNIFIED_VIDEO_SQL, Map.of("id", id), new UnifiedVideoRowMapper()));
        if (doc == null) {
            return Optional.empty();
        }

        doc.setTags(findNames("""
                SELECT tag
                  FROM unified_video_tags
                 WHERE unified_video_id = :id
                 ORDER BY tag
                """, id));
        doc.setSourceCount(countSources(id));
        doc.setSuggestion(new org.springframework.data.elasticsearch.core.suggest.Completion(
                buildSuggestionInputs(doc)));
        return Optional.of(doc);
    }

    private List<String> findNames(String sql, UUID id) {
        return jdbcTemplate.query(sql, Map.of("id", id), (rs, rowNum) -> rs.getString(1))
                .stream()
                .filter(StringUtils::hasText)
                .toList();
    }

    private int countSources(UUID id) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM raw_video_unified_video
                 WHERE unified_video_id = :id
                """, Map.of("id", id), Integer.class);
        return count == null ? 0 : count;
    }

    private String[] buildSuggestionInputs(UnifiedVideoSearchDocument doc) {
        List<String> inputs = new ArrayList<>();
        addSuggestionInput(inputs, doc.getTitle());
        addSuggestionInput(inputs, doc.getAliasTitle());
        addSuggestionInput(inputs, doc.getNormalizedTitle());
        addSuggestionInput(inputs, doc.getNormalizedAliasTitle());
        if (doc.getTitleVariants() != null) {
            doc.getTitleVariants().forEach(input -> addSuggestionInput(inputs, input));
        }
        return inputs.toArray(String[]::new);
    }

    private void addSuggestionInput(Collection<String> inputs, String input) {
        if (StringUtils.hasText(input)) {
            inputs.add(input.trim());
        }
    }

    private void append(StringBuilder builder, String value) {
        if (StringUtils.hasText(value)) {
            builder.append(value).append(' ');
        }
    }

    private void appendAll(StringBuilder builder, List<String> values) {
        if (values != null) {
            values.forEach(value -> append(builder, value));
        }
    }

    private List<String> readJsonStringList(ResultSet rs, String column) throws SQLException {
        return List.copyOf(JsonStringArrays.readSet(objectMapper, rs.getString(column)));
    }

    private UUID getUuid(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value instanceof UUID uuid ? uuid : null;
    }

    private Instant getInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private List<String> splitCsv(String value) {
        if (!StringUtils.hasText(value)) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private class RawVideoRowMapper implements RowMapper<RawVideoSearchDocument> {
        @Override
        public RawVideoSearchDocument mapRow(ResultSet rs, int rowNum) throws SQLException {
            RawVideoSearchDocument doc = new RawVideoSearchDocument();
            doc.setId(getUuid(rs, "id"));
            doc.setTitle(rs.getString("title"));
            doc.setAliasTitle(rs.getString("alias_title"));
            doc.setNormalizedTitle(SearchTextHelper.normalizeTitleForSearch(doc.getTitle()));
            doc.setNormalizedAliasTitle(SearchTextHelper.normalizeTitleForSearch(doc.getAliasTitle()));
            doc.setTitleVariants(SearchTextHelper.buildTitleVariants(
                    doc.getTitle(),
                    doc.getAliasTitle(),
                    rs.getString("subtitle"),
                    rs.getString("year"),
                    (Integer) rs.getObject("season")));
            doc.setExternalIds(SearchTextHelper.collectExternalIds(
                    rs.getString("douban_id"),
                    rs.getString("tmdb_id"),
                    rs.getString("imdb_id"),
                    rs.getString("rotten_tomatoes_id")));
            doc.setSourceVid(rs.getString("source_vid"));
            doc.setYear(rs.getString("year"));
            doc.setScore(rs.getBigDecimal("score") == null ? null : rs.getBigDecimal("score").doubleValue());
            doc.setTotalEpisodes(rs.getString("total_episodes"));
            doc.setDuration(rs.getString("duration"));
            doc.setSeason((Integer) rs.getObject("season"));
            doc.setSubtitle(rs.getString("subtitle"));
            doc.setDataSourceName(rs.getString("data_source_name"));
            doc.setDataSourceId(getUuid(rs, "data_source_id"));
            doc.setCategoryName(rs.getString("category_name"));
            doc.setCategoryId(getUuid(rs, "category_id"));
            doc.setCategoryCode(rs.getString("category_code"));
            doc.setSourceCategoryName(rs.getString("source_category_name"));
            doc.setSourceCategoryCode(rs.getString("source_category_code"));
            String rawLanguageCsv = rs.getString("raw_language_str");
            doc.setRawGenres(readJsonStringList(rs, "genre_codes"));
            doc.setRawAreas(readJsonStringList(rs, "area_codes"));
            List<String> languageCodes = readJsonStringList(rs, "language_codes");
            doc.setRawLanguages(languageCodes.isEmpty() ? splitCsv(rawLanguageCsv) : languageCodes);
            doc.setRawLanguagesAsCsv(rawLanguageCsv);
            doc.setActors(readJsonStringList(rs, "actor_names"));
            doc.setDirectors(readJsonStringList(rs, "director_names"));
            doc.setEnrichmentStatus(rs.getString("enrichment_status"));
            doc.setNormalizationStatus(rs.getString("normalization_status"));
            doc.setAggregationStatus(rs.getString("aggregation_status"));
            doc.setHasDoubanId(StringUtils.hasText(rs.getString("douban_id")));
            doc.setHasTmdbId(StringUtils.hasText(rs.getString("tmdb_id")));
            doc.setMissingSlug(!doc.isHasDoubanId() && !doc.isHasTmdbId());
            doc.setCoverStorageType(rs.getString("cover_storage_type"));
            doc.setCoverStatus(rs.getString("cover_status"));
            doc.setCoverImageUrl(coverImageUrlResolver.resolve(
                    doc.getCoverStorageType(),
                    rs.getString("cover_original_url"),
                    rs.getString("cover_storage_path"),
                    rs.getString("cover_source_domain")));
            doc.setCreatedAt(getInstant(rs, "created_at"));
            doc.setUpdatedAt(getInstant(rs, "updated_at"));
            doc.setDescriptionForMetadata(rs.getString("description"));
            return doc;
        }
    }

    private class UnifiedVideoRowMapper implements RowMapper<UnifiedVideoSearchDocument> {
        @Override
        public UnifiedVideoSearchDocument mapRow(ResultSet rs, int rowNum) throws SQLException {
            UnifiedVideoSearchDocument doc = new UnifiedVideoSearchDocument();
            doc.setId(getUuid(rs, "id"));
            doc.setTitle(rs.getString("title"));
            doc.setAliasTitle(rs.getString("alias_title"));
            doc.setNormalizedTitle(SearchTextHelper.normalizeTitleForSearch(doc.getTitle()));
            doc.setNormalizedAliasTitle(SearchTextHelper.normalizeTitleForSearch(doc.getAliasTitle()));
            doc.setTitleVariants(SearchTextHelper.buildTitleVariants(
                    doc.getTitle(),
                    doc.getAliasTitle(),
                    rs.getString("subtitle"),
                    rs.getString("year"),
                    (Integer) rs.getObject("season")));
            doc.setExternalIds(SearchTextHelper.collectExternalIds(
                    rs.getString("douban_id"),
                    rs.getString("tmdb_id"),
                    rs.getString("imdb_id"),
                    rs.getString("rotten_tomatoes_id")));
            String description = rs.getString("description");
            doc.setDescription(description != null && description.length() > 200
                    ? description.substring(0, 200)
                    : description);
            doc.setScore(rs.getBigDecimal("score") == null ? 0.0 : rs.getBigDecimal("score").doubleValue());
            doc.setYear(parseYear(rs.getString("year")));
            doc.setRemarks(rs.getString("remarks"));
            doc.setTotalEpisodes(rs.getString("total_episodes"));
            doc.setDuration(rs.getString("duration"));
            doc.setSeason((Integer) rs.getObject("season"));
            doc.setSubtitle(rs.getString("subtitle"));
            doc.setCategorySlug(rs.getString("category_slug"));
            doc.setCategoryName(rs.getString("category_name"));
            doc.setCategoryId(getUuid(rs, "category_id"));
            List<String> genreCodes = readJsonStringList(rs, "genre_codes");
            List<String> areaCodes = readJsonStringList(rs, "area_codes");
            List<String> languageCodes = readJsonStringList(rs, "language_codes");
            doc.setGenres(genreCodes);
            doc.setGenreCodes(genreCodes);
            doc.setAreas(areaCodes);
            doc.setAreaCodes(areaCodes);
            doc.setLanguages(languageCodes);
            doc.setLanguageCodes(languageCodes);
            doc.setActors(readJsonStringList(rs, "actor_names"));
            doc.setDirectors(readJsonStringList(rs, "director_names"));
            doc.setContentVisibility(rs.getString("content_visibility"));
            doc.setMetadataStatus(rs.getString("metadata_status"));
            doc.setAdultRestricted(rs.getBoolean("adult_restricted"));
            doc.setAdultAssessmentRestricted(rs.getBoolean("adult_assessment_restricted"));
            doc.setAdultAssessmentLevel(rs.getString("adult_assessment_level"));
            doc.setAdultCheckedAt(getInstant(rs, "adult_checked_at"));
            doc.setHasDouban(StringUtils.hasText(rs.getString("douban_id")));
            doc.setHasTmdb(StringUtils.hasText(rs.getString("tmdb_id")));
            doc.setHasImdb(StringUtils.hasText(rs.getString("imdb_id")));
            doc.setLastTrendAt(getInstant(rs, "last_trend_at"));
            Date publishedAt = rs.getDate("published_at");
            if (publishedAt != null) {
                doc.setPublishedAt(publishedAt.toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant());
            }
            doc.setCoverImageUrl(coverImageUrlResolver.resolve(
                    rs.getString("cover_storage_type"),
                    rs.getString("cover_original_url"),
                    rs.getString("cover_storage_path"),
                    rs.getString("cover_source_domain")));
            doc.setCreatedAt(getInstant(rs, "created_at"));
            doc.setUpdatedAt(getInstant(rs, "updated_at"));
            return doc;
        }

        private Integer parseYear(String value) {
            if (!StringUtils.hasText(value)) {
                return null;
            }
            try {
                String digits = value.replaceAll("\\D", "");
                return StringUtils.hasText(digits) ? Integer.parseInt(digits) : null;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }
}

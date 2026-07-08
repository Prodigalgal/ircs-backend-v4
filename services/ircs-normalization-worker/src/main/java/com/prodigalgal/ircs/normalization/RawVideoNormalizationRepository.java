package com.prodigalgal.ircs.normalization;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.metadata.JsonStringArrays;
import com.prodigalgal.ircs.common.metadata.MetadataNameOwnerType;
import com.prodigalgal.ircs.common.metadata.MetadataNameValkeyCache;
import com.prodigalgal.ircs.common.normalization.StandardContentCategoryClassifier;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class RawVideoNormalizationRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final ObjectProvider<MetadataNameValkeyCache> metadataNameCacheProvider;
    private final ObjectMapper objectMapper;
    private final List<String> allowedCategoryCodes = StandardContentCategoryClassifier.stableCategoryCodes();

    public RawVideoNormalizationRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            ObjectProvider<MetadataNameValkeyCache> metadataNameCacheProvider,
            ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.metadataNameCacheProvider = metadataNameCacheProvider;
        this.objectMapper = objectMapper;
    }

    static RawVideoNormalizationRepository forTest(NamedParameterJdbcTemplate jdbcTemplate) {
        return new RawVideoNormalizationRepository(jdbcTemplate, null, JsonMapper.builder().build());
    }

    public Optional<RawVideoRecord> findById(UUID id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select id, normalization_status, normalization_retry_count, raw_metadata::text, locked_fields::text,
                           title, alias_title, season, subtitle, description, year, area, raw_language_str, remarks, score,
                           total_episodes, duration, douban_id, tmdb_id, imdb_id, rotten_tomatoes_id,
                           data_source_id, data_hash
                    from raw_videos
                    where id = :id
                    """,
                    Map.of("id", id),
                    (rs, rowNum) -> new RawVideoRecord(
                            rs.getObject("id", UUID.class),
                            rs.getString("normalization_status"),
                            (Integer) rs.getObject("normalization_retry_count"),
                            rs.getString("raw_metadata"),
                            rs.getString("locked_fields"),
                            rs.getString("title"),
                            rs.getString("alias_title"),
                            (Integer) rs.getObject("season"),
                            rs.getString("subtitle"),
                            rs.getString("description"),
                            rs.getString("year"),
                            rs.getString("area"),
                            rs.getString("raw_language_str"),
                            rs.getString("remarks"),
                            rs.getBigDecimal("score"),
                            rs.getString("total_episodes"),
                            rs.getString("duration"),
                            rs.getString("douban_id"),
                            rs.getString("tmdb_id"),
                            rs.getString("imdb_id"),
                            rs.getString("rotten_tomatoes_id"),
                            rs.getObject("data_source_id", UUID.class),
                            rs.getString("data_hash"))));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public MarkReadyResult markReady(UUID id, RawVideoPatch patch) {
        jdbcTemplate.update(
                """
                update raw_videos
                set title = :title,
                    alias_title = :aliasTitle,
                    season = :season,
                    subtitle = :subtitle,
                    description = :description,
                    year = :year,
                    area = :area,
                    raw_language_str = :rawLanguageStr,
                    remarks = :remarks,
                    score = :score,
                    total_episodes = :totalEpisodes,
                    duration = :duration,
                    douban_id = :doubanId,
                    tmdb_id = :tmdbId,
                    imdb_id = :imdbId,
                    rotten_tomatoes_id = :rottenTomatoesId,
                    normalization_status = 'READY',
                    normalization_retry_count = 0,
                    next_normalization_retry_time = null,
                    updated_at = now()
                where id = :id
                """,
                params(id, patch));
        Optional<CategoryBinding> categoryBinding = applySourceCategoryHints(
                id,
                patch.rawCategoryDataSourceId(),
                patch.rawCategorySourceCode(),
                patch.rawCategorySourceName());
        Set<String> genreCodes = resolveStandardCodes("GENRE", patch.rawGenreValues());
        Set<String> languageCodes = resolveStandardCodes("LANGUAGE", patch.rawLanguageValues());
        Set<String> areaCodes = resolveStandardCodes("AREA", patch.rawAreaValues());
        updateFlatMetadata(
                id,
                patch,
                genreCodes,
                languageCodes,
                areaCodes,
                categoryBinding.map(CategoryBinding::categoryCode).orElse(null));
        evictMetadataNameCache(id);
        return new MarkReadyResult();
    }

    public void markFailure(UUID id, String status, int retryCount, Instant nextRetryTime) {
        jdbcTemplate.update(
                """
                update raw_videos
                set normalization_status = :status,
                    normalization_retry_count = :retryCount,
                    next_normalization_retry_time = :nextRetryTime,
                    updated_at = now()
                where id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", status)
                        .addValue("retryCount", retryCount)
                        .addValue("nextRetryTime", nextRetryTime == null ? null : Timestamp.from(nextRetryTime)));
    }

    public List<UUID> sampleRawVideoIds(int limit) {
        return jdbcTemplate.queryForList(
                """
                select id
                  from raw_videos
                 order by updated_at asc nulls first, id asc
                 limit :limit
                """,
                new MapSqlParameterSource("limit", Math.max(1, limit)),
                UUID.class);
    }

    public long countRawVideos() {
        Long value = jdbcTemplate.queryForObject(
                "select count(*) from raw_videos",
                new MapSqlParameterSource(),
                Long.class);
        return value == null ? 0 : value;
    }

    public int resetAllNormalizationPending() {
        return jdbcTemplate.update(
                """
                update raw_videos
                   set normalization_status = 'PENDING',
                       normalization_retry_count = 0,
                       next_normalization_retry_time = null,
                       updated_at = now()
                """,
                new MapSqlParameterSource());
    }

    public List<RawVideoQueueItem> findNormalizationQueueItems(UUID afterId, int limit) {
        String sql = afterId == null
                ? """
                select id, data_hash
                  from raw_videos
                 order by id asc
                 limit :limit
                """
                : """
                select id, data_hash
                  from raw_videos
                 where id > :afterId
                 order by id asc
                 limit :limit
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("afterId", afterId)
                        .addValue("limit", Math.max(1, limit)),
                (rs, rowNum) -> new RawVideoQueueItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("data_hash")));
    }

    public List<RawVideoQueueItem> findPendingNormalizationQueueItems(Instant updatedBefore, int limit) {
        return jdbcTemplate.query(
                """
                select id, data_hash
                  from raw_videos
                 where normalization_status = 'PENDING'
                   and (updated_at is null or updated_at <= :updatedBefore)
                 order by updated_at asc nulls first, id asc
                 limit :limit
                """,
                new MapSqlParameterSource()
                        .addValue("updatedBefore", Timestamp.from(updatedBefore == null ? Instant.now() : updatedBefore))
                        .addValue("limit", Math.max(1, limit)),
                (rs, rowNum) -> new RawVideoQueueItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("data_hash")));
    }

    private MapSqlParameterSource params(UUID id, RawVideoPatch patch) {
        return new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("title", patch.title())
                .addValue("aliasTitle", patch.aliasTitle())
                .addValue("season", patch.season())
                .addValue("subtitle", patch.subtitle())
                .addValue("description", patch.description())
                .addValue("year", patch.year())
                .addValue("area", patch.area())
                .addValue("rawLanguageStr", patch.rawLanguageStr())
                .addValue("remarks", patch.remarks())
                .addValue("score", patch.score())
                .addValue("totalEpisodes", patch.totalEpisodes())
                .addValue("duration", patch.duration())
                .addValue("doubanId", patch.doubanId())
                .addValue("tmdbId", patch.tmdbId())
                .addValue("imdbId", patch.imdbId())
                .addValue("rottenTomatoesId", patch.rottenTomatoesId());
    }

    private Optional<CategoryBinding> applySourceCategoryHints(
            UUID rawVideoId,
            UUID dataSourceId,
            String sourceCode,
            String sourceName) {
        if (!StringUtils.hasText(sourceCode) && !StringUtils.hasText(sourceName)) {
            return Optional.empty();
        }
        String safeSourceCode = trimToLength(sourceCode, 100);
        String safeSourceName = StringUtils.hasText(sourceName) ? trimToLength(sourceName, 255) : null;
        String inferredCategoryCode = StandardContentCategoryClassifier
                .inferCode(safeSourceCode, safeSourceName)
                .orElse(null);
        String fallbackCategoryCode = StandardContentCategoryClassifier.OTHER;
        String categoryCode = jdbcTemplate.queryForObject(
                """
                select slug
                  from standard_category
                 where lower(slug) = lower(coalesce(
                       cast(:inferredCategoryCode as text),
                       cast(:fallbackCategoryCode as text)
                 ))
                 limit 1
                """,
                new MapSqlParameterSource()
                        .addValue("inferredCategoryCode", inferredCategoryCode)
                        .addValue("fallbackCategoryCode", fallbackCategoryCode),
                String.class);
        if (!StringUtils.hasText(categoryCode)) {
            return Optional.empty();
        }
        jdbcTemplate.update(
                """
                update raw_videos
                set data_source_id = coalesce(data_source_id, :dataSourceId),
                    source_category_code = coalesce(nullif(source_category_code, ''), :sourceCode),
                    source_category_name = coalesce(nullif(source_category_name, ''), :sourceName),
                    category_code = coalesce(nullif(category_code, ''), :categoryCode),
                    updated_at = now()
                where id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", rawVideoId)
                        .addValue("dataSourceId", dataSourceId)
                        .addValue("sourceCode", safeSourceCode)
                        .addValue("sourceName", safeSourceName)
                        .addValue("categoryCode", categoryCode));
        return Optional.of(new CategoryBinding(categoryCode));
    }

    private Optional<CategoryBinding> resolveCategoryBinding(UUID dataSourceId, String sourceCode) {
        if (!StringUtils.hasText(sourceCode)) {
            return Optional.empty();
        }
        return StandardContentCategoryClassifier
                .inferCode(sourceCode, null)
                .map(CategoryBinding::new);
    }

    private Set<String> resolveStandardCodes(String kind, Collection<String> values) {
        List<String> lookupValues = safeValues(values).stream()
                .map(value -> value.toLowerCase(java.util.Locale.ROOT))
                .toList();
        if (lookupValues.isEmpty()) {
            return Set.of();
        }
        String sql = switch (kind) {
            case "GENRE" -> """
                    select distinct sg.code
                      from standard_genre sg
                     where nullif(trim(sg.code), '') is not null
                       and (
                           lower(sg.name) in (:values)
                           or lower(sg.code) in (:values)
                       )
                     order by sg.code
                    """;
            case "LANGUAGE" -> """
                    select distinct sl.code
                      from standard_languages sl
                     where nullif(trim(sl.code), '') is not null
                       and (
                           lower(sl.name) in (:values)
                           or lower(sl.code) in (:values)
                           or lower(coalesce(sl.english_name, '')) in (:values)
                           or lower(coalesce(sl.native_name, '')) in (:values)
                       )
                     order by sl.code
                    """;
            case "AREA" -> """
                    select distinct sa.code
                      from standard_areas sa
                     where nullif(trim(sa.code), '') is not null
                       and (
                           lower(sa.name) in (:values)
                           or lower(sa.code) in (:values)
                       )
                     order by sa.code
                    """;
            default -> throw new IllegalArgumentException("Unsupported standard code kind: " + kind);
        };
        List<String> codes = jdbcTemplate.queryForList(
                sql,
                new MapSqlParameterSource("values", lookupValues),
                String.class);
        return JsonStringArrays.normalize(codes);
    }

    private void updateFlatMetadata(
            UUID rawVideoId,
            RawVideoPatch patch,
            Set<String> genreCodes,
            Set<String> languageCodes,
            Set<String> areaCodes,
            String categoryCode) {
        jdbcTemplate.update(
                """
                update raw_videos
                   set actor_names = case when :hasActorNames then cast(:actorNames as jsonb) else actor_names end,
                       director_names = case when :hasDirectorNames then cast(:directorNames as jsonb) else director_names end,
                       area_codes = case when :hasAreaCodes then cast(:areaCodes as jsonb) else area_codes end,
                       language_codes = case when :hasLanguageCodes then cast(:languageCodes as jsonb) else language_codes end,
                       genre_codes = case when :hasGenreCodes then cast(:genreCodes as jsonb) else genre_codes end,
                       category_code = case
                           when :categoryCode is not null then :categoryCode
                           when lower(coalesce(category_code, '')) not in (:allowedCategoryCodes) then null
                           else category_code
                       end,
                       source_category_code = coalesce(:sourceCategoryCode, source_category_code),
                       source_category_name = coalesce(:sourceCategoryName, source_category_name),
                       normalization_snapshot = cast(:normalizationSnapshot as jsonb),
                       updated_at = now()
                 where id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", rawVideoId)
                        .addValue("hasActorNames", !safeValues(patch.actorValues()).isEmpty())
                        .addValue("actorNames", jsonArray(patch.actorValues()))
                        .addValue("hasDirectorNames", !safeValues(patch.directorValues()).isEmpty())
                        .addValue("directorNames", jsonArray(patch.directorValues()))
                        .addValue("hasAreaCodes", !areaCodes.isEmpty())
                        .addValue("areaCodes", jsonArray(areaCodes))
                        .addValue("hasLanguageCodes", !languageCodes.isEmpty())
                        .addValue("languageCodes", jsonArray(languageCodes))
                        .addValue("hasGenreCodes", !genreCodes.isEmpty())
                        .addValue("genreCodes", jsonArray(genreCodes))
                        .addValue("categoryCode", categoryCode)
                        .addValue("allowedCategoryCodes", allowedCategoryCodes)
                        .addValue("sourceCategoryCode", trimNullable(patch.rawCategorySourceCode(), 100))
                        .addValue("sourceCategoryName", trimNullable(patch.rawCategorySourceName(), 255))
                        .addValue("normalizationSnapshot", normalizationSnapshot(patch)));
    }

    private String trimToLength(String value, int maxLength) {
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private String trimNullable(String value, int maxLength) {
        return StringUtils.hasText(value) ? trimToLength(value, maxLength) : null;
    }

    private List<String> safeValues(Collection<String> values) {
        return values == null
                ? List.of()
                : values.stream()
                        .filter(StringUtils::hasText)
                        .map(String::trim)
                        .distinct()
                        .toList();
    }

    private String jsonArray(Collection<String> values) {
        return JsonStringArrays.write(objectMapper, values);
    }

    private String normalizationSnapshot(RawVideoPatch patch) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("version", 1);
        snapshot.put("rawGenreValues", List.copyOf(JsonStringArrays.normalize(patch.rawGenreValues())));
        snapshot.put("rawLanguageValues", List.copyOf(JsonStringArrays.normalize(patch.rawLanguageValues())));
        snapshot.put("rawAreaValues", List.copyOf(JsonStringArrays.normalize(patch.rawAreaValues())));
        snapshot.put("actorNames", List.copyOf(JsonStringArrays.normalize(patch.actorValues())));
        snapshot.put("directorNames", List.copyOf(JsonStringArrays.normalize(patch.directorValues())));
        snapshot.put("sourceCategoryCode", trimNullable(patch.rawCategorySourceCode(), 100));
        snapshot.put("sourceCategoryName", trimNullable(patch.rawCategorySourceName(), 255));
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize normalization snapshot", ex);
        }
    }

    private void evictMetadataNameCache(UUID rawVideoId) {
        MetadataNameValkeyCache metadataNameCache = metadataNameCacheProvider == null
                ? null
                : metadataNameCacheProvider.getIfAvailable();
        if (metadataNameCache != null) {
            metadataNameCache.evictOwner(MetadataNameOwnerType.RAW, List.of(rawVideoId));
        }
    }

    public record MarkReadyResult() {
    }

    public record RawVideoQueueItem(UUID id, String dataHash) {
    }

    private record CategoryBinding(String categoryCode) {
    }

}

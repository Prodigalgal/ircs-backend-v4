package com.prodigalgal.ircs.aggregation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.adult.AdultAssessment;
import com.prodigalgal.ircs.common.adult.AdultAssessmentInput;
import com.prodigalgal.ircs.common.adult.AdultContentAssessor;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.common.metadata.JsonStringArrays;
import com.prodigalgal.ircs.common.metadata.MetadataNameOwnerType;
import com.prodigalgal.ircs.common.metadata.MetadataNameValkeyCache;
import com.prodigalgal.ircs.common.normalization.StandardContentCategoryClassifier;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JdbcAggregationRepository {

    private static final Pattern LOCKED_FIELD_PATTERN = Pattern.compile("\"([^\"]+)\"");
    private static final List<String> ALLOWED_CATEGORY_CODES = StandardContentCategoryClassifier.stableCategoryCodes();

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final AggregationCandidateRepository candidateRepository;
    private final AggregationMetadataNameRepository metadataNameRepository;
    private final AdultAssessmentEvaluator adultAssessmentEvaluator;
    private final ObjectMapper objectMapper;

    public JdbcAggregationRepository(
            NamedParameterJdbcTemplate jdbcTemplate,
            AggregationMatchingStrategy matchingStrategy,
            AggregationContextSearchClient contextSearchClient,
            ObjectProvider<MetadataNameValkeyCache> metadataNameCacheProvider,
            ObjectMapper objectMapper,
            AdultAssessmentEvaluator adultAssessmentEvaluator,
            @Value("${app.aggregation.jdbc.query-timeout-seconds:30}") int queryTimeoutSeconds) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.adultAssessmentEvaluator = adultAssessmentEvaluator == null
                ? AdultAssessmentEvaluator.local()
                : adultAssessmentEvaluator;
        this.metadataNameRepository = new AggregationMetadataNameRepository(
                jdbcTemplate,
                metadataNameCacheProvider,
                objectMapper);
        this.candidateRepository = new AggregationCandidateRepository(
                jdbcTemplate,
                matchingStrategy,
                contextSearchClient,
                metadataNameRepository);
        if (queryTimeoutSeconds > 0 && this.jdbcTemplate.getJdbcTemplate() != null) {
            this.jdbcTemplate.getJdbcTemplate().setQueryTimeout(queryTimeoutSeconds);
        }
    }

    static JdbcAggregationRepository forTest(
            NamedParameterJdbcTemplate jdbcTemplate,
            AggregationMatchingStrategy matchingStrategy) {
        return new JdbcAggregationRepository(
                jdbcTemplate,
                matchingStrategy,
                (title, year) -> AggregationContextSearchClient.ContextSearchResult.notAttempted(),
                null,
                JsonMapper.builder().build(),
                AdultAssessmentEvaluator.local(),
                30);
    }

    static JdbcAggregationRepository forTest(
            NamedParameterJdbcTemplate jdbcTemplate,
            AggregationMatchingStrategy matchingStrategy,
            AggregationContextSearchClient contextSearchClient) {
        return new JdbcAggregationRepository(
                jdbcTemplate,
                matchingStrategy,
                contextSearchClient,
                null,
                JsonMapper.builder().build(),
                AdultAssessmentEvaluator.local(),
                30);
    }

    public Optional<RawVideoAggregationRecord> findRawVideo(UUID rawVideoId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select id, title, alias_title, description, year, score, published_at,
                           total_episodes, duration, remarks, subtitle, season,
                           category_name, category_code,
                           douban_id, tmdb_id, imdb_id, rotten_tomatoes_id,
                           normalization_status, enrichment_status, aggregation_status
                    from (
                    select rv.*,
                           sc.name as category_name
                    from raw_videos rv
                    left join standard_category sc on sc.slug = rv.category_code
                    where rv.id = :id
                    ) raw_candidate
                    """,
                    new MapSqlParameterSource("id", rawVideoId),
                    this::mapRawVideo))
                    .map(metadataNameRepository::populateRawMetadata);
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<RawVideoAggregationRecord> findRawVideos(Collection<UUID> rawVideoIds) {
        if (rawVideoIds == null || rawVideoIds.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = rawVideoIds.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<RawVideoAggregationRecord> rawVideos = jdbcTemplate.query(
                """
                select id, title, alias_title, description, year, score, published_at,
                       total_episodes, duration, remarks, subtitle, season,
                       category_name, category_code,
                       douban_id, tmdb_id, imdb_id, rotten_tomatoes_id,
                       normalization_status, enrichment_status, aggregation_status
                from (
                    select rv.*,
                           sc.name as category_name
                    from raw_videos rv
                    left join standard_category sc on sc.slug = rv.category_code
                    where rv.id in (:ids)
                ) raw_candidate
                """,
                new MapSqlParameterSource("ids", ids),
                this::mapRawVideo);
        return metadataNameRepository.populateRawMetadata(rawVideos);
    }

    public Optional<UUID> findExistingBinding(UUID rawVideoId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select unified_video_id
                    from raw_video_unified_video
                    where raw_video_id = :rawVideoId
                    limit 1
                    """,
                    new MapSqlParameterSource("rawVideoId", rawVideoId),
                    UUID.class));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    public List<UUID> findRawIdsForUnified(UUID unifiedVideoId) {
        return jdbcTemplate.queryForList(
                """
                select raw_video_id
                from raw_video_unified_video
                where unified_video_id = :unifiedVideoId
                order by raw_video_id
                """,
                new MapSqlParameterSource("unifiedVideoId", unifiedVideoId),
                UUID.class);
    }

    public List<UUID> findDirtyUnifiedIds(int limit) {
        return jdbcTemplate.queryForList(
                """
                select id
                  from unified_videos
                 where metadata_status = 'DIRTY'
                 order by updated_at asc nulls first, id
                 limit :limit
                 for update skip locked
                """,
                new MapSqlParameterSource("limit", Math.max(1, limit)),
                UUID.class);
    }

    public List<UUID> findPendingRawIdsWithoutRuntimeProgress(int limit) {
        return jdbcTemplate.queryForList(
                """
                select rv.id
                from raw_videos rv
                where rv.aggregation_status = 'PENDING'
                order by rv.aggregation_status_updated_at asc nulls first, rv.updated_at asc nulls first, rv.id
                limit :limit
                """,
                new MapSqlParameterSource("limit", Math.max(1, limit)),
                UUID.class);
    }

    public List<UUID> findUnifiedIdsMissingCoverFromRaw(int limit) {
        return jdbcTemplate.queryForList(
                """
                select distinct uv.id
                from unified_videos uv
                join raw_video_unified_video rvuv on rvuv.unified_video_id = uv.id
                join raw_videos rv on rv.id = rvuv.raw_video_id
                where uv.cover_image_id is null
                  and rv.cover_image_id is not null
                order by uv.id
                limit :limit
                """,
                new MapSqlParameterSource("limit", Math.max(1, limit)),
                UUID.class);
    }

    public List<UnifiedCoverBackfillResult> backfillUnifiedCoverImagesFromRaw(int limit) {
        List<UnifiedCoverBackfillResult> candidates = jdbcTemplate.query(
                """
                select unified_video_id, cover_image_id, storage_type, status
                from (
                    select rvuv.unified_video_id,
                           rv.cover_image_id,
                           ci.storage_type,
                           ci.status,
                           row_number() over (
                               partition by rvuv.unified_video_id
                               order by rv.created_at asc, rv.id asc
                           ) as rn
                    from (
                        select distinct uv.id
                        from unified_videos uv
                        join raw_video_unified_video rvuv on rvuv.unified_video_id = uv.id
                        join raw_videos rv on rv.id = rvuv.raw_video_id
                        where uv.cover_image_id is null
                          and rv.cover_image_id is not null
                        order by uv.id
                        limit :limit
                    ) cu
                    join raw_video_unified_video rvuv on rvuv.unified_video_id = cu.id
                    join raw_videos rv on rv.id = rvuv.raw_video_id
                    join cover_images ci on ci.id = rv.cover_image_id
                    where rv.cover_image_id is not null
                )
                where rn = 1
                order by unified_video_id
                """,
                new MapSqlParameterSource("limit", Math.max(1, limit)),
                (rs, rowNum) -> new UnifiedCoverBackfillResult(
                        rs.getObject("unified_video_id", UUID.class),
                        rs.getObject("cover_image_id", UUID.class),
                        rs.getString("storage_type"),
                        rs.getString("status")));
        if (candidates.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource[] batch = candidates.stream()
                .map(result -> new MapSqlParameterSource()
                        .addValue("unifiedVideoId", result.unifiedVideoId())
                        .addValue("coverImageId", result.coverImageId()))
                .toArray(MapSqlParameterSource[]::new);
        int[] updates = jdbcTemplate.batchUpdate(
                """
                update unified_videos
                set cover_image_id = :coverImageId,
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where id = :unifiedVideoId
                  and cover_image_id is null
                """,
                batch);
        List<UnifiedCoverBackfillResult> backfilled = new ArrayList<>();
        for (int i = 0; i < updates.length; i++) {
            if (updates[i] > 0) {
                backfilled.add(candidates.get(i));
            }
        }
        return backfilled;
    }

    public List<UUID> findAdultAssessmentBackfillIds(int limit) {
        return jdbcTemplate.query(
                """
                select id
                  from unified_videos
                 where adult_checked_at is null
                    or coalesce(adult_assessment ->> 'ruleVersion', '') <> :ruleVersion
                 order by adult_checked_at asc nulls first,
                          updated_at desc nulls last,
                          id asc
                 limit :limit
                """,
                new MapSqlParameterSource()
                        .addValue("ruleVersion", AdultContentAssessor.RULE_VERSION)
                        .addValue("limit", Math.max(1, limit)),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    public Optional<UUID> findMatchingUnifiedVideo(RawVideoAggregationRecord rawVideo) {
        return candidateRepository.findMatchingUnifiedVideo(rawVideo);
    }

    public AggregationMatchPlan findMatchPlan(RawVideoAggregationRecord rawVideo) {
        return candidateRepository.findMatchPlan(rawVideo);
    }

    public List<UnifiedVideoAggregationCandidate> findContextUnifiedCandidates(List<RawVideoAggregationRecord> rawVideos) {
        return candidateRepository.findContextUnifiedCandidates(rawVideos);
    }

    public UUID createUnifiedVideo(RawVideoAggregationRecord rawVideo) {
        UUID unifiedVideoId = IrcsUuidGenerators.nextId();
        jdbcTemplate.update(
                """
                insert into unified_videos (
                    id, created_at, updated_at, version,
                    title, alias_title, description, year, score, published_at,
                    total_episodes, duration, remarks, subtitle, season,
                    douban_id, tmdb_id, imdb_id, rotten_tomatoes_id,
                    category_id, category_code, locked_fields, metadata_status
                ) values (
                    :id, now(), now(), 0,
                    :title, :aliasTitle, :description, :year, :score, :publishedAt,
                    :totalEpisodes, :duration, :remarks, :subtitle, :season,
                    :doubanId, :tmdbId, :imdbId, :rottenTomatoesId,
                    (select id from standard_category where slug = :categoryCode limit 1), :categoryCode,
                    '[]'::jsonb, 'SYNCED'
                )
                """,
                paramsForUnified(unifiedVideoId, rawVideo));
        return unifiedVideoId;
    }

    public int upsertUnifiedFields(UUID unifiedVideoId, RawVideoAggregationRecord rawVideo) {
        return jdbcTemplate.update(
                """
                update unified_videos
                set title = coalesce(nullif(title, ''), :title),
                    alias_title = coalesce(nullif(alias_title, ''), :aliasTitle),
                    description = coalesce(nullif(description, ''), :description),
                    year = coalesce(nullif(year, ''), :year),
                    score = coalesce(score, :score),
                    published_at = coalesce(published_at, :publishedAt),
                    total_episodes = coalesce(nullif(total_episodes, ''), :totalEpisodes),
                    duration = coalesce(nullif(duration, ''), :duration),
                    remarks = coalesce(nullif(remarks, ''), :remarks),
                    subtitle = coalesce(nullif(subtitle, ''), :subtitle),
                    season = coalesce(season, :season),
                    douban_id = coalesce(nullif(douban_id, ''), :doubanId),
                    tmdb_id = coalesce(nullif(tmdb_id, ''), :tmdbId),
                    imdb_id = coalesce(nullif(imdb_id, ''), :imdbId),
                    rotten_tomatoes_id = coalesce(nullif(rotten_tomatoes_id, ''), :rottenTomatoesId),
                    category_id = case
                        when :categoryCode is not null and (
                            category_code is null
                            or nullif(trim(category_code), '') is null
                            or lower(category_code) not in (:allowedCategoryCodes)
                        ) then (select id from standard_category where slug = :categoryCode limit 1)
                        else category_id
                    end,
                    category_code = case
                        when :categoryCode is not null and (
                            category_code is null
                            or nullif(trim(category_code), '') is null
                            or lower(category_code) not in (:allowedCategoryCodes)
                        ) then :categoryCode
                        else category_code
                    end,
                    metadata_status = 'SYNCED',
                    updated_at = now()
                where id = :id
                """,
                paramsForUnified(unifiedVideoId, rawVideo));
    }

    public int bindRawToUnified(UUID rawVideoId, UUID unifiedVideoId) {
        return jdbcTemplate.update(
                """
                insert into raw_video_unified_video (raw_video_id, unified_video_id)
                values (:rawVideoId, :unifiedVideoId)
                on conflict do nothing
                """,
                new MapSqlParameterSource()
                        .addValue("rawVideoId", rawVideoId)
                        .addValue("unifiedVideoId", unifiedVideoId));
    }

    public void rebuildUnifiedPipelineRelations(UUID unifiedVideoId) {
        rebuildUnifiedPipelineMetadataRelations(unifiedVideoId);
        rebuildUnifiedPipelineCategory(unifiedVideoId);
    }

    public void rebuildUnifiedPipelineMetadataRelations(UUID unifiedVideoId) {
        Set<String> lockedFields = findLockedFields(unifiedVideoId);
        rebuildUnifiedFlatMetadata(unifiedVideoId, lockedFields);
        evictMetadataNameCache(MetadataNameOwnerType.UNIFIED, List.of(unifiedVideoId));
    }

    public void rebuildUnifiedPipelineScalars(UUID unifiedVideoId) {
        rebuildUnifiedPipelineBasicAttributes(unifiedVideoId);
        rebuildUnifiedPipelineExternalIds(unifiedVideoId);
        rebuildUnifiedPipelineDynamicFields(unifiedVideoId);
    }

    public void rebuildUnifiedPipelineBasicAttributes(UUID unifiedVideoId) {
        Set<String> lockedFields = findLockedFields(unifiedVideoId);
        MapSqlParameterSource params = new MapSqlParameterSource("unifiedVideoId", unifiedVideoId);
        if (!isLocked(lockedFields, "title")) {
            updateUnifiedStringByVote("title", "title", params);
        }
        if (!isLocked(lockedFields, "year")) {
            updateUnifiedStringByVote("year", "year", params);
        }
        if (!isLocked(lockedFields, "score")) {
            updateUnifiedScoreMax(params);
        }
        if (!isLocked(lockedFields, "publishedAt")) {
            updateUnifiedFirstDate("published_at", "published_at", params);
        }
    }

    public void rebuildUnifiedPipelineExternalIds(UUID unifiedVideoId) {
        MapSqlParameterSource params = new MapSqlParameterSource("unifiedVideoId", unifiedVideoId);
        fillUnifiedExternalId("douban_id", params);
        fillUnifiedExternalId("tmdb_id", params);
        fillUnifiedExternalId("imdb_id", params);
        fillUnifiedExternalId("rotten_tomatoes_id", params);
    }

    public void rebuildUnifiedPipelineDynamicFields(UUID unifiedVideoId) {
        Set<String> lockedFields = findLockedFields(unifiedVideoId);
        MapSqlParameterSource params = new MapSqlParameterSource("unifiedVideoId", unifiedVideoId);
        if (!isLocked(lockedFields, "aliasTitle")) {
            updateUnifiedLongestString("alias_title", "alias_title", params);
        }
        if (!isLocked(lockedFields, "description")) {
            updateUnifiedLongestString("description", "description", params);
        }
        if (!isLocked(lockedFields, "remarks")) {
            updateUnifiedLatestRemarks(params);
        }
        if (!isLocked(lockedFields, "totalEpisodes")) {
            updateUnifiedFirstString("total_episodes", "total_episodes", params);
        }
        if (!isLocked(lockedFields, "duration")) {
            updateUnifiedFirstString("duration", "duration", params);
        }
        if (!isLocked(lockedFields, "season")) {
            updateUnifiedFirstInteger("season", "season", params);
        }
        if (!isLocked(lockedFields, "subtitle")) {
            updateUnifiedLongestString("subtitle", "subtitle", params);
        }
    }

    public void rebuildUnifiedPipelineCategory(UUID unifiedVideoId) {
        Set<String> lockedFields = findLockedFields(unifiedVideoId);
        if (!isLocked(lockedFields, "category")) {
            MapSqlParameterSource params = new MapSqlParameterSource("unifiedVideoId", unifiedVideoId);
            voteUnifiedCategory(params);
            voteUnifiedCategoryCode(params);
        }
    }

    public void rebuildUnifiedAdultAssessment(UUID unifiedVideoId) {
        Optional<UnifiedAdultAssessmentBase> base = findUnifiedAdultAssessmentBase(unifiedVideoId);
        if (base.isEmpty()) {
            return;
        }
        AdultAssessment assessment = adultAssessmentEvaluator.assess(unifiedVideoId, new AdultAssessmentInput(
                base.get().title(),
                base.get().aliasTitle(),
                base.get().description(),
                base.get().remarks(),
                base.get().subtitle(),
                base.get().categoryCode(),
                base.get().categoryName(),
                JsonStringArrays.readSet(objectMapper, base.get().actorNamesJson()),
                JsonStringArrays.readSet(objectMapper, base.get().directorNamesJson()),
                JsonStringArrays.readSet(objectMapper, base.get().genreCodesJson()),
                findAdultAssessmentSources(unifiedVideoId)));
        writeUnifiedAdultAssessment(unifiedVideoId, assessment);
    }

    public List<UUID> rebuildUnifiedAdultAssessments(List<UUID> unifiedVideoIds) {
        List<UUID> orderedIds = unifiedVideoIds == null
                ? List.of()
                : unifiedVideoIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
        if (orderedIds.isEmpty()) {
            return List.of();
        }
        Map<UUID, UnifiedAdultAssessmentBase> bases = findUnifiedAdultAssessmentBases(orderedIds);
        if (bases.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<AdultAssessmentInput.SourceEvidence>> sourcesByUnifiedId =
                findAdultAssessmentSources(orderedIds);
        Map<UUID, AdultAssessmentInput> inputs = new LinkedHashMap<>();
        for (UUID unifiedVideoId : orderedIds) {
            UnifiedAdultAssessmentBase base = bases.get(unifiedVideoId);
            if (base == null) {
                continue;
            }
            inputs.put(unifiedVideoId, new AdultAssessmentInput(
                    base.title(),
                    base.aliasTitle(),
                    base.description(),
                    base.remarks(),
                    base.subtitle(),
                    base.categoryCode(),
                    base.categoryName(),
                    JsonStringArrays.readSet(objectMapper, base.actorNamesJson()),
                    JsonStringArrays.readSet(objectMapper, base.directorNamesJson()),
                    JsonStringArrays.readSet(objectMapper, base.genreCodesJson()),
                    sourcesByUnifiedId.getOrDefault(unifiedVideoId, List.of())));
        }
        Map<UUID, AdultAssessment> assessments = adultAssessmentEvaluator.assessAll(inputs);
        List<AdultAssessmentWrite> writes = new ArrayList<>(assessments.size());
        for (UUID unifiedVideoId : orderedIds) {
            AdultAssessment assessment = assessments.get(unifiedVideoId);
            if (assessment == null) {
                continue;
            }
            writes.add(new AdultAssessmentWrite(unifiedVideoId, assessment));
        }
        return writeUnifiedAdultAssessments(writes);
    }

    public List<UUID> rebuildUnifiedPipelineCoverImage(UUID unifiedVideoId) {
        List<CoverImageChoice> choices = findCoverImageChoices(unifiedVideoId);
        Optional<CoverImageChoice> currentCover = findCurrentUnifiedCover(unifiedVideoId);
        if (currentCover.isEmpty() && choices.isEmpty()) {
            return List.of();
        }

        CoverImageChoice selected = currentCover.orElseGet(() -> choices.get(0));
        List<UUID> promoteCandidates = new ArrayList<>();
        if (currentCover.isEmpty() && selected.isLocalStored()) {
            promoteCandidates.add(selected.id());
        }
        int startIndex = currentCover.isPresent() ? 0 : 1;
        for (int i = startIndex; i < choices.size(); i++) {
            CoverImageChoice source = choices.get(i);
            if (shouldReplaceCover(selected, source)) {
                selected = source;
                if (source.isLocalStored()) {
                    promoteCandidates.add(source.id());
                }
            }
        }
        updateUnifiedCoverImage(unifiedVideoId, selected.id());
        return promoteCandidates;
    }

    public void mergeDuplicateUnifiedVideos(UUID rootUnifiedVideoId, List<UUID> victimUnifiedVideoIds) {
        if (victimUnifiedVideoIds == null || victimUnifiedVideoIds.isEmpty()) {
            return;
        }
        List<UUID> victims = victimUnifiedVideoIds.stream()
                .filter(victimId -> !rootUnifiedVideoId.equals(victimId))
                .distinct()
                .toList();
        if (victims.isEmpty()) {
            return;
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("rootId", rootUnifiedVideoId)
                .addValue("victimIds", victims);
        migrateRawBindingsToRoot(params);
        migrateFavoritesToRoot(params);
        migrateWatchHistoriesToRoot(params);
        mergeVictimScalarsToRoot(rootUnifiedVideoId, victims);
        mergeVictimFlatMetadataToRoot(rootUnifiedVideoId, victims);
        deleteVictimUnifiedRows(params);
        List<UUID> changedIds = new ArrayList<>(victims.size() + 1);
        changedIds.add(rootUnifiedVideoId);
        changedIds.addAll(victims);
        evictMetadataNameCache(MetadataNameOwnerType.UNIFIED, changedIds);
    }

    public int markBound(UUID rawVideoId) {
        return updateAggregationStatus(rawVideoId, "BOUND");
    }

    public int markUnifiedSynced(UUID unifiedVideoId) {
        return jdbcTemplate.update(
                """
                update unified_videos
                set metadata_status = 'SYNCED',
                    updated_at = now()
                where id = :unifiedVideoId
                """,
                new MapSqlParameterSource("unifiedVideoId", unifiedVideoId));
    }

    public int markUnifiedDirty(UUID unifiedVideoId) {
        return jdbcTemplate.update(
                """
                update unified_videos
                set metadata_status = 'DIRTY',
                    updated_at = now()
                where id = :unifiedVideoId
                """,
                new MapSqlParameterSource("unifiedVideoId", unifiedVideoId));
    }

    public int markPending(UUID rawVideoId) {
        return updateAggregationStatus(rawVideoId, "PENDING");
    }

    public int resetStuckProcessing(int timeoutMinutes, int limit) {
        return jdbcTemplate.update(
                """
                with stuck as (
                    select id
                  from raw_videos
                  where aggregation_status = 'PROCESSING'
                    and aggregation_status_updated_at < now() - (:timeoutMinutes * interval '1 minute')
                  order by aggregation_status_updated_at asc
                  limit :limit
                  for update skip locked
                )
              update raw_videos rv
              set aggregation_status = 'PENDING',
                  aggregation_status_updated_at = now()
                from stuck
                where rv.id = stuck.id
                """,
                new MapSqlParameterSource()
                        .addValue("timeoutMinutes", timeoutMinutes)
                        .addValue("limit", limit));
    }

    public List<UUID> sampleRawVideoIds(int limit) {
        return jdbcTemplate.queryForList(
                """
                select id
                  from raw_videos
                 order by aggregation_status_updated_at asc, id asc
                 limit :limit
                """,
                new MapSqlParameterSource("limit", Math.max(1, limit)),
                UUID.class);
    }

    public long countRawVideos() {
        return count("raw_videos");
    }

    public long countUnifiedVideos() {
        return count("unified_videos");
    }

    public long countRawUnifiedBindings() {
        return count("raw_video_unified_video");
    }

    public int deleteAllAggregationResetOwnedRows() {
        int changedRows = 0;
        changedRows += updateIfTableExists("""
                delete from magnet_provider_runs
                 where job_id in (
                       select id
                         from magnet_search_jobs
                        where unified_video_id in (select id from unified_videos)
                 )
                """);
        changedRows += updateIfTableExists("""
                delete from magnet_search_jobs
                 where unified_video_id in (select id from unified_videos)
                """);
        changedRows += updateIfTableExists("""
                delete from magnet_links
                 where unified_video_id in (select id from unified_videos)
                """);
        changedRows += updateIfTableExists("""
                delete from member_favorites
                 where unified_video_id in (select id from unified_videos)
                """);
        changedRows += updateIfTableExists("""
                delete from member_watch_histories
                 where unified_video_id in (select id from unified_videos)
                """);
        changedRows += updateIfTableExists("delete from raw_video_unified_video");
        changedRows += updateIfTableExists("delete from unified_video_tags");
        changedRows += jdbcTemplate.update("delete from unified_videos", new MapSqlParameterSource());
        evictAllMetadataNameCache();
        return changedRows;
    }

    public int markAllRawAggregationPending() {
        return jdbcTemplate.update(
                """
                update raw_videos
                   set aggregation_status = 'PENDING',
                       aggregation_status_updated_at = now()
                """,
                new MapSqlParameterSource());
    }

    private int updateAggregationStatus(UUID rawVideoId, String status) {
        return jdbcTemplate.update(
                """
            update raw_videos
            set aggregation_status = :status,
                aggregation_status_updated_at = now()
            where id = :rawVideoId
                """,
                new MapSqlParameterSource()
                        .addValue("rawVideoId", rawVideoId)
                        .addValue("status", status));
    }

    private long count(String tableName) {
        Long value = jdbcTemplate.queryForObject("select count(*) from " + tableName, new MapSqlParameterSource(), Long.class);
        return value == null ? 0 : value;
    }

    private int updateIfTableExists(String sql) {
        try {
            return jdbcTemplate.update(sql, new MapSqlParameterSource());
        } catch (org.springframework.jdbc.BadSqlGrammarException ex) {
            if (isMissingTable(ex)) {
                return 0;
            }
            throw ex;
        }
    }

    private boolean isMissingTable(org.springframework.jdbc.BadSqlGrammarException ex) {
        Throwable current = ex;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                String normalized = message.toLowerCase(Locale.ROOT);
                if (normalized.contains("does not exist") || normalized.contains("not found")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    private void migrateRawBindingsToRoot(MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                insert into raw_video_unified_video (raw_video_id, unified_video_id)
                select raw_video_id, :rootId
                from raw_video_unified_video
                where unified_video_id in (:victimIds)
                on conflict do nothing
                """,
                params);
        jdbcTemplate.update(
                """
                delete from raw_video_unified_video
                where unified_video_id in (:victimIds)
                """,
                params);
    }

    private void migrateFavoritesToRoot(MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                delete from member_favorites
                where unified_video_id in (:victimIds)
                  and exists (
                      select 1
                      from member_favorites root
                      where root.member_id = member_favorites.member_id
                        and root.unified_video_id = :rootId
                  )
                """,
                params);
        jdbcTemplate.update(
                """
                update member_favorites
                set unified_video_id = :rootId,
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where unified_video_id in (:victimIds)
                """,
                params);
    }

    private void migrateWatchHistoriesToRoot(MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                delete from member_watch_histories
                where unified_video_id in (:victimIds)
                  and exists (
                      select 1
                      from member_watch_histories root
                      where root.member_id = member_watch_histories.member_id
                        and root.unified_video_id = :rootId
                  )
                """,
                params);
        jdbcTemplate.update(
                """
                update member_watch_histories
                set unified_video_id = :rootId,
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where unified_video_id in (:victimIds)
                """,
                params);
    }

    private Set<String> findLockedFields(UUID unifiedVideoId) {
        try {
            String lockedFields = jdbcTemplate.queryForObject(
                    """
                    select cast(locked_fields as varchar)
                    from unified_videos
                    where id = :unifiedVideoId
                    """,
                    new MapSqlParameterSource("unifiedVideoId", unifiedVideoId),
                    String.class);
            return parseLockedFields(lockedFields);
        } catch (EmptyResultDataAccessException ignored) {
            return Set.of();
        }
    }

    private Set<String> parseLockedFields(String value) {
        if (!StringUtils.hasText(value)) {
            return Set.of();
        }
        String text = value.trim()
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
                .toLowerCase(Locale.ROOT);
        java.util.LinkedHashSet<String> fields = new java.util.LinkedHashSet<>();
        Matcher matcher = LOCKED_FIELD_PATTERN.matcher(text);
        while (matcher.find()) {
            String field = matcher.group(1);
            if (StringUtils.hasText(field)) {
                fields.add(field.trim().toLowerCase(Locale.ROOT));
            }
        }
        String normalized = text
                .replace("[", "")
                .replace("]", "")
                .replace("{", "")
                .replace("}", "")
                .replace("\"", "");
        for (String part : normalized.split(",")) {
            if (StringUtils.hasText(part)) {
                fields.add(part.trim().toLowerCase(Locale.ROOT));
            }
        }
        addKnownLockedFieldIfPresent(fields, text, "category");
        addKnownLockedFieldIfPresent(fields, text, "actors");
        addKnownLockedFieldIfPresent(fields, text, "directors");
        addKnownLockedFieldIfPresent(fields, text, "genres");
        addKnownLockedFieldIfPresent(fields, text, "area");
        addKnownLockedFieldIfPresent(fields, text, "areas");
        addKnownLockedFieldIfPresent(fields, text, "language");
        addKnownLockedFieldIfPresent(fields, text, "languages");
        addKnownLockedFieldIfPresent(fields, text, "aliastitle");
        addKnownLockedFieldIfPresent(fields, text, "publishedat");
        addKnownLockedFieldIfPresent(fields, text, "totalepisodes");
        addKnownLockedFieldIfPresent(fields, text, "coverimageurl");
        return Set.copyOf(fields);
    }

    private void addKnownLockedFieldIfPresent(Set<String> fields, String text, String field) {
        if (text.contains("\"" + field + "\"") || text.contains(field)) {
            fields.add(field);
        }
    }

    private boolean isLocked(Set<String> lockedFields, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (lockedFields.contains(fieldName.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void updateUnifiedStringByVote(String unifiedColumn, String rawColumn, MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                update unified_videos
                set %s = (
                        select selected_value
                        from (
                            select trim(%s) as selected_value, count(*) as votes
                            from raw_video_unified_video rvuv
                            join raw_videos rv on rv.id = rvuv.raw_video_id
                            where rvuv.unified_video_id = :unifiedVideoId
                              and nullif(trim(%s), '') is not null
                            group by trim(%s)
                            order by votes desc, selected_value asc
                            limit 1
                        ) voted
                    ),
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where id = :unifiedVideoId
                  and exists (
                      select 1
                      from raw_video_unified_video rvuv
                      join raw_videos rv on rv.id = rvuv.raw_video_id
                      where rvuv.unified_video_id = :unifiedVideoId
                        and nullif(trim(%s), '') is not null
                  )
                """.formatted(unifiedColumn, rawColumn, rawColumn, rawColumn, rawColumn),
                params);
    }

    private void updateUnifiedScoreMax(MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                update unified_videos
                set score = (
                        select max(rv.score)
                        from raw_video_unified_video rvuv
                        join raw_videos rv on rv.id = rvuv.raw_video_id
                        where rvuv.unified_video_id = :unifiedVideoId
                          and rv.score is not null
                    ),
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where id = :unifiedVideoId
                  and exists (
                      select 1
                      from raw_video_unified_video rvuv
                      join raw_videos rv on rv.id = rvuv.raw_video_id
                      where rvuv.unified_video_id = :unifiedVideoId
                        and rv.score is not null
                  )
                """,
                params);
    }

    private void updateUnifiedFirstDate(String unifiedColumn, String rawColumn, MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                update unified_videos
                set %s = (
                        select %s
                        from raw_video_unified_video rvuv
                        join raw_videos rv on rv.id = rvuv.raw_video_id
                        where rvuv.unified_video_id = :unifiedVideoId
                          and %s is not null
                        order by rv.created_at asc, rv.id asc
                        limit 1
                    ),
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where id = :unifiedVideoId
                  and exists (
                      select 1
                      from raw_video_unified_video rvuv
                      join raw_videos rv on rv.id = rvuv.raw_video_id
                      where rvuv.unified_video_id = :unifiedVideoId
                        and %s is not null
                  )
                """.formatted(unifiedColumn, rawColumn, rawColumn, rawColumn),
                params);
    }

    private void fillUnifiedExternalId(String column, MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                update unified_videos
                set %s = (
                        select trim(%s)
                        from raw_video_unified_video rvuv
                        join raw_videos rv on rv.id = rvuv.raw_video_id
                        where rvuv.unified_video_id = :unifiedVideoId
                          and %s
                        order by rv.created_at asc, rv.id asc
                        limit 1
                    ),
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where id = :unifiedVideoId
                  and not %s
                  and exists (
                      select 1
                      from raw_video_unified_video rvuv
                      join raw_videos rv on rv.id = rvuv.raw_video_id
                      where rvuv.unified_video_id = :unifiedVideoId
                        and %s
                  )
                """.formatted(
                        column,
                        column,
                        validExternalIdExpression("rv." + column),
                        validExternalIdExpression(column),
                        validExternalIdExpression("rv." + column)),
                params);
    }

    private void updateUnifiedLongestString(String unifiedColumn, String rawColumn, MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                update unified_videos
                set %s = (
                        select selected_value
                        from (
                            select trim(%s) as selected_value, rv.created_at, rv.id
                            from raw_video_unified_video rvuv
                            join raw_videos rv on rv.id = rvuv.raw_video_id
                            where rvuv.unified_video_id = :unifiedVideoId
                              and nullif(trim(%s), '') is not null
                        ) values_by_length
                        order by length(selected_value) desc, created_at asc, id asc
                        limit 1
                    ),
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where id = :unifiedVideoId
                  and exists (
                      select 1
                      from raw_video_unified_video rvuv
                      join raw_videos rv on rv.id = rvuv.raw_video_id
                      where rvuv.unified_video_id = :unifiedVideoId
                        and nullif(trim(%s), '') is not null
                  )
                """.formatted(unifiedColumn, rawColumn, rawColumn, rawColumn),
                params);
    }

    private void updateUnifiedLatestRemarks(MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                update unified_videos
                set remarks = (
                        select trim(rv.remarks)
                        from raw_video_unified_video rvuv
                        join raw_videos rv on rv.id = rvuv.raw_video_id
                        where rvuv.unified_video_id = :unifiedVideoId
                          and nullif(trim(rv.remarks), '') is not null
                        order by rv.updated_at desc, rv.id asc
                        limit 1
                    ),
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where id = :unifiedVideoId
                  and exists (
                      select 1
                      from raw_video_unified_video rvuv
                      join raw_videos rv on rv.id = rvuv.raw_video_id
                      where rvuv.unified_video_id = :unifiedVideoId
                        and nullif(trim(rv.remarks), '') is not null
                  )
                """,
                params);
    }

    private void updateUnifiedFirstString(String unifiedColumn, String rawColumn, MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                update unified_videos
                set %s = (
                        select trim(%s)
                        from raw_video_unified_video rvuv
                        join raw_videos rv on rv.id = rvuv.raw_video_id
                        where rvuv.unified_video_id = :unifiedVideoId
                          and nullif(trim(%s), '') is not null
                        order by rv.created_at asc, rv.id asc
                        limit 1
                    ),
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where id = :unifiedVideoId
                  and exists (
                      select 1
                      from raw_video_unified_video rvuv
                      join raw_videos rv on rv.id = rvuv.raw_video_id
                      where rvuv.unified_video_id = :unifiedVideoId
                        and nullif(trim(%s), '') is not null
                  )
                """.formatted(unifiedColumn, rawColumn, rawColumn, rawColumn),
                params);
    }

    private void updateUnifiedFirstInteger(String unifiedColumn, String rawColumn, MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                update unified_videos
                set %s = (
                        select %s
                        from raw_video_unified_video rvuv
                        join raw_videos rv on rv.id = rvuv.raw_video_id
                        where rvuv.unified_video_id = :unifiedVideoId
                          and %s is not null
                        order by rv.created_at asc, rv.id asc
                        limit 1
                    ),
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where id = :unifiedVideoId
                  and exists (
                      select 1
                      from raw_video_unified_video rvuv
                      join raw_videos rv on rv.id = rvuv.raw_video_id
                      where rvuv.unified_video_id = :unifiedVideoId
                        and %s is not null
                  )
                """.formatted(unifiedColumn, rawColumn, rawColumn, rawColumn),
                params);
    }

    private String validExternalIdExpression(String column) {
        return "nullif(trim(%s), '') is not null and trim(%s) <> '0'".formatted(column, column);
    }

    private void rebuildUnifiedFlatMetadata(UUID unifiedVideoId, Set<String> lockedFields) {
        List<RawFlatMetadataRow> rows = findRawFlatMetadataRows(unifiedVideoId);
        if (rows.isEmpty()) {
            return;
        }
        MapSqlParameterSource params = new MapSqlParameterSource("unifiedVideoId", unifiedVideoId);
        appendFlatJsonUpdate(params, "actorNames", collectFlatValues(rows, RawFlatMetadataRow::actorNames),
                !isLocked(lockedFields, "actors"));
        appendFlatJsonUpdate(params, "directorNames", collectFlatValues(rows, RawFlatMetadataRow::directorNames),
                !isLocked(lockedFields, "directors"));
        appendFlatJsonUpdate(params, "areaCodes", collectFlatValues(rows, RawFlatMetadataRow::areaCodes),
                !isLocked(lockedFields, "area", "areas"));
        appendFlatJsonUpdate(params, "languageCodes", collectFlatValues(rows, RawFlatMetadataRow::languageCodes),
                !isLocked(lockedFields, "language", "languages"));
        appendFlatJsonUpdate(params, "genreCodes", collectFlatValues(rows, RawFlatMetadataRow::genreCodes),
                !isLocked(lockedFields, "genres"));
        if (params.getValues().size() == 1) {
            return;
        }
        String setSql = params.getValues().keySet().stream()
                .filter(name -> !"unifiedVideoId".equals(name))
                .map(this::flatJsonSetSql)
                .reduce((left, right) -> left + ",\n                       " + right)
                .orElse("");
        jdbcTemplate.update(
                """
                update unified_videos
                   set %s,
                       updated_at = now(),
                       version = coalesce(version, 0) + 1
                 where id = :unifiedVideoId
                """.formatted(setSql),
                params);
    }

    private List<RawFlatMetadataRow> findRawFlatMetadataRows(UUID unifiedVideoId) {
        return jdbcTemplate.query(
                """
                select cast(rv.actor_names as varchar) as actor_names,
                       cast(rv.director_names as varchar) as director_names,
                       cast(rv.area_codes as varchar) as area_codes,
                       cast(rv.language_codes as varchar) as language_codes,
                       cast(rv.genre_codes as varchar) as genre_codes,
                       rv.category_code
                  from raw_video_unified_video rvuv
                  join raw_videos rv on rv.id = rvuv.raw_video_id
                 where rvuv.unified_video_id = :unifiedVideoId
                """,
                new MapSqlParameterSource("unifiedVideoId", unifiedVideoId),
                (rs, rowNum) -> new RawFlatMetadataRow(
                        JsonStringArrays.readSet(objectMapper, rs.getString("actor_names")),
                        JsonStringArrays.readSet(objectMapper, rs.getString("director_names")),
                        JsonStringArrays.readSet(objectMapper, rs.getString("area_codes")),
                        JsonStringArrays.readSet(objectMapper, rs.getString("language_codes")),
                        JsonStringArrays.readSet(objectMapper, rs.getString("genre_codes")),
                        rs.getString("category_code")));
    }

    private Set<String> collectFlatValues(
            List<RawFlatMetadataRow> rows,
            java.util.function.Function<RawFlatMetadataRow, Set<String>> extractor) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (RawFlatMetadataRow row : rows) {
            values.addAll(extractor.apply(row));
        }
        return JsonStringArrays.normalize(values);
    }

    private void appendFlatJsonUpdate(
            MapSqlParameterSource params,
            String paramName,
            Set<String> values,
            boolean updateAllowed) {
        if (updateAllowed && !values.isEmpty()) {
            params.addValue(paramName, JsonStringArrays.write(objectMapper, values));
        }
    }

    private String flatJsonSetSql(String paramName) {
        String columnName = switch (paramName) {
            case "actorNames" -> "actor_names";
            case "directorNames" -> "director_names";
            case "areaCodes" -> "area_codes";
            case "languageCodes" -> "language_codes";
            case "genreCodes" -> "genre_codes";
            default -> throw new IllegalArgumentException("Unsupported flat metadata parameter: " + paramName);
        };
        return "%s = cast(:%s as jsonb)".formatted(columnName, paramName);
    }

    private List<CoverImageChoice> findCoverImageChoices(UUID unifiedVideoId) {
        return jdbcTemplate.query(
                """
                select ci.id, ci.storage_type, ci.status, ci.file_size
                from raw_video_unified_video rvuv
                join raw_videos rv on rv.id = rvuv.raw_video_id
                join cover_images ci on ci.id = rv.cover_image_id
                where rvuv.unified_video_id = :unifiedVideoId
                order by rv.created_at asc, rv.id asc
                """,
                new MapSqlParameterSource("unifiedVideoId", unifiedVideoId),
                (rs, rowNum) -> new CoverImageChoice(
                        rs.getObject("id", UUID.class),
                        rs.getString("storage_type"),
                        rs.getString("status"),
                        rs.getLong("file_size"),
                        rs.wasNull()));
    }

    private Optional<CoverImageChoice> findCurrentUnifiedCover(UUID unifiedVideoId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select ci.id, ci.storage_type, ci.status, ci.file_size
                    from unified_videos uv
                    join cover_images ci on ci.id = uv.cover_image_id
                    where uv.id = :unifiedVideoId
                    """,
                    new MapSqlParameterSource("unifiedVideoId", unifiedVideoId),
                    (rs, rowNum) -> new CoverImageChoice(
                            rs.getObject("id", UUID.class),
                            rs.getString("storage_type"),
                            rs.getString("status"),
                            rs.getLong("file_size"),
                            rs.wasNull())));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private boolean shouldReplaceCover(CoverImageChoice current, CoverImageChoice source) {
        boolean sourceIsManaged = source.isManaged();
        boolean currentIsManaged = current.isManaged();
        if (sourceIsManaged && !currentIsManaged) {
            return true;
        }
        if (sourceIsManaged == currentIsManaged) {
            return source.fileSizeOrZero() > current.fileSizeOrZero() * 1.1;
        }
        return false;
    }

    private void updateUnifiedCoverImage(UUID unifiedVideoId, UUID coverImageId) {
        jdbcTemplate.update(
                """
                update unified_videos
                set cover_image_id = :coverImageId,
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where id = :unifiedVideoId
                  and (cover_image_id is null or cover_image_id <> :coverImageId)
                """,
                new MapSqlParameterSource()
                        .addValue("unifiedVideoId", unifiedVideoId)
                        .addValue("coverImageId", coverImageId));
    }

    private void voteUnifiedCategory(MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                update unified_videos
                set category_id = (
                        select category_id
                        from (
                            select sc.id as category_id, count(*) as votes
                            from raw_video_unified_video rvuv
                            join raw_videos rv on rv.id = rvuv.raw_video_id
                            join standard_category sc on sc.slug = rv.category_code
                            where rvuv.unified_video_id = :unifiedVideoId
                              and nullif(trim(rv.category_code), '') is not null
                            group by sc.id
                            order by votes desc, cast(sc.id as varchar) asc
                            limit 1
                        ) voted
                    ),
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where id = :unifiedVideoId
                  and exists (
                      select 1
                      from raw_video_unified_video rvuv
                      join raw_videos rv on rv.id = rvuv.raw_video_id
                      join standard_category sc on sc.slug = rv.category_code
                      where rvuv.unified_video_id = :unifiedVideoId
                        and nullif(trim(rv.category_code), '') is not null
                  )
                """,
                params);
    }

    private void voteUnifiedCategoryCode(MapSqlParameterSource params) {
        jdbcTemplate.update(
                """
                update unified_videos
                set category_code = (
                        select category_code
                        from (
                            select rv.category_code, count(*) as votes
                            from raw_video_unified_video rvuv
                            join raw_videos rv on rv.id = rvuv.raw_video_id
                            where rvuv.unified_video_id = :unifiedVideoId
                              and nullif(trim(rv.category_code), '') is not null
                            group by rv.category_code
                            order by votes desc, rv.category_code asc
                            limit 1
                        ) voted
                    ),
                    updated_at = now(),
                    version = coalesce(version, 0) + 1
                where id = :unifiedVideoId
                  and exists (
                      select 1
                      from raw_video_unified_video rvuv
                      join raw_videos rv on rv.id = rvuv.raw_video_id
                      where rvuv.unified_video_id = :unifiedVideoId
                        and nullif(trim(rv.category_code), '') is not null
                  )
                """,
                params);
    }

    private Optional<UnifiedAdultAssessmentBase> findUnifiedAdultAssessmentBase(UUID unifiedVideoId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    """
                    select uv.id,
                           uv.title,
                           uv.alias_title,
                           uv.description,
                           uv.remarks,
                           uv.subtitle,
                           uv.category_code,
                           sc.name as category_name,
                           cast(uv.actor_names as varchar) as actor_names,
                           cast(uv.director_names as varchar) as director_names,
                           cast(uv.genre_codes as varchar) as genre_codes
                      from unified_videos uv
                      left join standard_category sc on sc.slug = uv.category_code
                     where uv.id = :unifiedVideoId
                    """,
                    new MapSqlParameterSource("unifiedVideoId", unifiedVideoId),
                    this::mapUnifiedAdultAssessmentBase));
        } catch (EmptyResultDataAccessException ignored) {
            return Optional.empty();
        }
    }

    private Map<UUID, UnifiedAdultAssessmentBase> findUnifiedAdultAssessmentBases(Collection<UUID> unifiedVideoIds) {
        List<UnifiedAdultAssessmentBase> rows = jdbcTemplate.query(
                """
                select uv.id,
                       uv.title,
                       uv.alias_title,
                       uv.description,
                       uv.remarks,
                       uv.subtitle,
                       uv.category_code,
                       sc.name as category_name,
                       cast(uv.actor_names as varchar) as actor_names,
                       cast(uv.director_names as varchar) as director_names,
                       cast(uv.genre_codes as varchar) as genre_codes
                  from unified_videos uv
                  left join standard_category sc on sc.slug = uv.category_code
                 where uv.id in (:ids)
                """,
                new MapSqlParameterSource("ids", unifiedVideoIds),
                this::mapUnifiedAdultAssessmentBase);
        Map<UUID, UnifiedAdultAssessmentBase> byId = new LinkedHashMap<>();
        for (UnifiedAdultAssessmentBase row : rows) {
            byId.putIfAbsent(row.id(), row);
        }
        return byId;
    }

    private List<AdultAssessmentInput.SourceEvidence> findAdultAssessmentSources(UUID unifiedVideoId) {
        return jdbcTemplate.query(
                """
                select ds.name as data_source_name,
                       coalesce(ds.adult_restricted, false) as data_source_adult_restricted,
                       rv.source_category_code,
                       rv.source_category_name,
                       coalesce(ds.base_url, sd.domain_value) as source_domain,
                       cast(null as varchar) as raw_metadata
                  from raw_video_unified_video rvuv
                  join raw_videos rv on rv.id = rvuv.raw_video_id
                  left join data_sources ds on ds.id = rv.data_source_id
                  left join cover_images ci on ci.id = rv.cover_image_id
                  left join source_domains sd on sd.id = ci.source_domain_id
                 where rvuv.unified_video_id = :unifiedVideoId
                 order by rv.created_at asc, rv.id asc
                """,
                new MapSqlParameterSource("unifiedVideoId", unifiedVideoId),
                (rs, rowNum) -> new AdultAssessmentInput.SourceEvidence(
                        rs.getString("data_source_name"),
                        rs.getBoolean("data_source_adult_restricted"),
                        rs.getString("source_category_code"),
                        rs.getString("source_category_name"),
                        rs.getString("source_domain"),
                        rs.getString("raw_metadata")));
    }

    private Map<UUID, List<AdultAssessmentInput.SourceEvidence>> findAdultAssessmentSources(
            Collection<UUID> unifiedVideoIds) {
        Map<UUID, List<AdultAssessmentInput.SourceEvidence>> sourcesByUnifiedId = new LinkedHashMap<>();
        jdbcTemplate.query(
                """
                select rvuv.unified_video_id,
                       ds.name as data_source_name,
                       coalesce(ds.adult_restricted, false) as data_source_adult_restricted,
                       rv.source_category_code,
                       rv.source_category_name,
                       coalesce(ds.base_url, sd.domain_value) as source_domain,
                       cast(null as varchar) as raw_metadata
                  from raw_video_unified_video rvuv
                  join raw_videos rv on rv.id = rvuv.raw_video_id
                  left join data_sources ds on ds.id = rv.data_source_id
                  left join cover_images ci on ci.id = rv.cover_image_id
                  left join source_domains sd on sd.id = ci.source_domain_id
                 where rvuv.unified_video_id in (:ids)
                 order by rvuv.unified_video_id, rv.created_at asc, rv.id asc
                """,
                new MapSqlParameterSource("ids", unifiedVideoIds),
                rs -> {
                    UUID unifiedVideoId = rs.getObject("unified_video_id", UUID.class);
                    sourcesByUnifiedId.computeIfAbsent(unifiedVideoId, ignored -> new ArrayList<>())
                            .add(new AdultAssessmentInput.SourceEvidence(
                                    rs.getString("data_source_name"),
                                    rs.getBoolean("data_source_adult_restricted"),
                                    rs.getString("source_category_code"),
                                    rs.getString("source_category_name"),
                                    rs.getString("source_domain"),
                                    rs.getString("raw_metadata")));
                });
        return sourcesByUnifiedId;
    }

    private void writeUnifiedAdultAssessment(UUID unifiedVideoId, AdultAssessment assessment) {
        jdbcTemplate.update(
                """
                update unified_videos
                   set adult_restricted = :adultRestricted,
                       adult_assessment = cast(:adultAssessment as jsonb),
                       adult_checked_at = now(),
                       updated_at = now(),
                       version = coalesce(version, 0) + 1
                 where id = :unifiedVideoId
                """,
                new MapSqlParameterSource()
                        .addValue("unifiedVideoId", unifiedVideoId)
                        .addValue("adultRestricted", assessment.adultRestricted())
                        .addValue("adultAssessment", writeJson(assessment)));
    }

    private List<UUID> writeUnifiedAdultAssessments(List<AdultAssessmentWrite> writes) {
        if (writes == null || writes.isEmpty()) {
            return List.of();
        }
        MapSqlParameterSource[] batch = writes.stream()
                .map(write -> new MapSqlParameterSource()
                        .addValue("unifiedVideoId", write.unifiedVideoId())
                        .addValue("adultRestricted", write.assessment().adultRestricted())
                        .addValue("adultAssessment", writeJson(write.assessment())))
                .toArray(MapSqlParameterSource[]::new);
        int[] updates = jdbcTemplate.batchUpdate(
                """
                update unified_videos
                   set adult_restricted = :adultRestricted,
                       adult_assessment = cast(:adultAssessment as jsonb),
                       adult_checked_at = now(),
                       updated_at = now(),
                       version = coalesce(version, 0) + 1
                 where id = :unifiedVideoId
                """,
                batch);
        List<UUID> updatedIds = new ArrayList<>(updates.length);
        for (int i = 0; i < updates.length; i++) {
            if (updates[i] > 0) {
                updatedIds.add(writes.get(i).unifiedVideoId());
            }
        }
        return updatedIds;
    }

    private UnifiedAdultAssessmentBase mapUnifiedAdultAssessmentBase(ResultSet rs, int rowNum) throws SQLException {
        return new UnifiedAdultAssessmentBase(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("alias_title"),
                rs.getString("description"),
                rs.getString("remarks"),
                rs.getString("subtitle"),
                rs.getString("category_code"),
                rs.getString("category_name"),
                rs.getString("actor_names"),
                rs.getString("director_names"),
                rs.getString("genre_codes"));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize adult assessment", ex);
        }
    }

    private void mergeVictimScalarsToRoot(UUID rootUnifiedVideoId, List<UUID> victimUnifiedVideoIds) {
        for (UUID victimUnifiedVideoId : victimUnifiedVideoIds) {
            jdbcTemplate.update(
                    """
                    update unified_videos root
                    set douban_id = coalesce(nullif(root.douban_id, ''), nullif(victim.douban_id, '')),
                        tmdb_id = coalesce(nullif(root.tmdb_id, ''), nullif(victim.tmdb_id, '')),
                        imdb_id = coalesce(nullif(root.imdb_id, ''), nullif(victim.imdb_id, '')),
                        rotten_tomatoes_id = coalesce(
                            nullif(root.rotten_tomatoes_id, ''),
                            nullif(victim.rotten_tomatoes_id, '')
                        ),
                        description = coalesce(nullif(root.description, ''), nullif(victim.description, '')),
                        score = coalesce(root.score, victim.score),
                        published_at = coalesce(root.published_at, victim.published_at),
                        alias_title = coalesce(nullif(root.alias_title, ''), nullif(victim.alias_title, '')),
                        total_episodes = coalesce(nullif(root.total_episodes, ''), nullif(victim.total_episodes, '')),
                        duration = coalesce(nullif(root.duration, ''), nullif(victim.duration, '')),
                        remarks = coalesce(nullif(root.remarks, ''), nullif(victim.remarks, '')),
                        season = coalesce(root.season, victim.season),
                        subtitle = coalesce(nullif(root.subtitle, ''), nullif(victim.subtitle, '')),
                        last_trend_at = case
                            when root.last_trend_at is null then victim.last_trend_at
                            when victim.last_trend_at is null then root.last_trend_at
                            when victim.last_trend_at > root.last_trend_at then victim.last_trend_at
                            else root.last_trend_at
                        end,
                        metadata_status = 'DIRTY',
                        updated_at = now(),
                        version = coalesce(root.version, 0) + 1
                    from unified_videos victim
                    where root.id = :rootId
                      and victim.id = :victimId
                    """,
                    new MapSqlParameterSource()
                            .addValue("rootId", rootUnifiedVideoId)
                            .addValue("victimId", victimUnifiedVideoId));
        }
    }

    private void mergeVictimFlatMetadataToRoot(UUID rootUnifiedVideoId, List<UUID> victimUnifiedVideoIds) {
        if (victimUnifiedVideoIds == null || victimUnifiedVideoIds.isEmpty()) {
            return;
        }
        List<RawFlatMetadataRow> rows = jdbcTemplate.query(
                """
                select cast(actor_names as varchar) as actor_names,
                       cast(director_names as varchar) as director_names,
                       cast(area_codes as varchar) as area_codes,
                       cast(language_codes as varchar) as language_codes,
                       cast(genre_codes as varchar) as genre_codes,
                       category_code
                 from unified_videos
                 where id = :rootId
                    or id in (:victimIds)
                 order by case when id = :rootId then 0 else 1 end, id
                """,
                new MapSqlParameterSource()
                        .addValue("rootId", rootUnifiedVideoId)
                        .addValue("victimIds", victimUnifiedVideoIds),
                (rs, rowNum) -> new RawFlatMetadataRow(
                        JsonStringArrays.readSet(objectMapper, rs.getString("actor_names")),
                        JsonStringArrays.readSet(objectMapper, rs.getString("director_names")),
                        JsonStringArrays.readSet(objectMapper, rs.getString("area_codes")),
                        JsonStringArrays.readSet(objectMapper, rs.getString("language_codes")),
                        JsonStringArrays.readSet(objectMapper, rs.getString("genre_codes")),
                        rs.getString("category_code")));
        if (rows.isEmpty()) {
            return;
        }
        jdbcTemplate.update(
                """
                update unified_videos
                   set actor_names = cast(:actorNames as jsonb),
                       director_names = cast(:directorNames as jsonb),
                       area_codes = cast(:areaCodes as jsonb),
                       language_codes = cast(:languageCodes as jsonb),
                       genre_codes = cast(:genreCodes as jsonb),
                       category_code = case
                           when nullif(:categoryCode, '') is not null and (
                               category_code is null
                               or nullif(trim(category_code), '') is null
                               or lower(category_code) not in (:allowedCategoryCodes)
                           ) then :categoryCode
                           else category_code
                       end,
                       updated_at = now(),
                       version = coalesce(version, 0) + 1
                 where id = :rootId
                """,
                new MapSqlParameterSource()
                        .addValue("rootId", rootUnifiedVideoId)
                        .addValue("actorNames", JsonStringArrays.write(
                                objectMapper,
                                collectFlatValues(rows, RawFlatMetadataRow::actorNames)))
                        .addValue("directorNames", JsonStringArrays.write(
                                objectMapper,
                                collectFlatValues(rows, RawFlatMetadataRow::directorNames)))
                        .addValue("areaCodes", JsonStringArrays.write(
                                objectMapper,
                                collectFlatValues(rows, RawFlatMetadataRow::areaCodes)))
                        .addValue("languageCodes", JsonStringArrays.write(
                                objectMapper,
                                collectFlatValues(rows, RawFlatMetadataRow::languageCodes)))
                        .addValue("genreCodes", JsonStringArrays.write(
                                objectMapper,
                                collectFlatValues(rows, RawFlatMetadataRow::genreCodes)))
                        .addValue("categoryCode", firstCategoryCode(rows))
                        .addValue("allowedCategoryCodes", ALLOWED_CATEGORY_CODES));
    }

    private String firstCategoryCode(List<RawFlatMetadataRow> rows) {
        for (RawFlatMetadataRow row : rows) {
            if (StringUtils.hasText(row.categoryCode())) {
                return row.categoryCode().trim();
            }
        }
        return null;
    }

    private void deleteVictimUnifiedRows(MapSqlParameterSource params) {
        jdbcTemplate.update("delete from unified_videos where id in (:victimIds)", params);
    }

    private RawVideoAggregationRecord mapRawVideo(ResultSet rs, int rowNum) throws SQLException {
        Date publishedAt = rs.getDate("published_at");
        return new RawVideoAggregationRecord(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getString("alias_title"),
                rs.getString("description"),
                rs.getString("year"),
                rs.getBigDecimal("score"),
                publishedAt == null ? null : publishedAt.toLocalDate(),
                rs.getString("total_episodes"),
                rs.getString("duration"),
                rs.getString("remarks"),
                rs.getString("subtitle"),
                (Integer) rs.getObject("season"),
                rs.getString("category_name"),
                rs.getString("category_code"),
                rs.getString("douban_id"),
                rs.getString("tmdb_id"),
                rs.getString("imdb_id"),
                rs.getString("rotten_tomatoes_id"),
                rs.getString("normalization_status"),
                rs.getString("enrichment_status"),
                rs.getString("aggregation_status"));
    }

    private void evictMetadataNameCache(MetadataNameOwnerType ownerType, Collection<UUID> ownerIds) {
        metadataNameRepository.evictOwner(ownerType, ownerIds);
    }

    private void evictAllMetadataNameCache() {
        metadataNameRepository.evictAll();
    }

    private MapSqlParameterSource paramsForUnified(UUID unifiedVideoId, RawVideoAggregationRecord rawVideo) {
        return new MapSqlParameterSource()
                .addValue("id", unifiedVideoId)
                .addValue("title", trimToLength(rawVideo.title(), 255))
                .addValue("aliasTitle", trimToLength(rawVideo.aliasTitle(), 255))
                .addValue("description", rawVideo.description())
                .addValue("year", trimToLength(rawVideo.year(), 20))
                .addValue("score", rawVideo.score())
                .addValue("publishedAt", rawVideo.publishedAt() == null ? null : Date.valueOf(rawVideo.publishedAt()))
                .addValue("totalEpisodes", trimToLength(rawVideo.totalEpisodes(), 50))
                .addValue("duration", trimToLength(rawVideo.duration(), 50))
                .addValue("remarks", trimToLength(rawVideo.remarks(), 255))
                .addValue("subtitle", trimToLength(rawVideo.subtitle(), 255))
                .addValue("season", rawVideo.season())
                .addValue("categoryCode", trimToLength(rawVideo.categoryCode(), 100))
                .addValue("allowedCategoryCodes", ALLOWED_CATEGORY_CODES)
                .addValue("doubanId", trimToLength(validOrNull(rawVideo.doubanId()), 20))
                .addValue("tmdbId", trimToLength(validOrNull(rawVideo.tmdbId()), 20))
                .addValue("imdbId", trimToLength(validOrNull(rawVideo.imdbId()), 20))
                .addValue("rottenTomatoesId", trimToLength(validOrNull(rawVideo.rottenTomatoesId()), 50));
    }

    private boolean isValidExternalId(String value) {
        return StringUtils.hasText(value) && !"0".equals(value.trim());
    }

    private String validOrNull(String value) {
        return isValidExternalId(value) ? value.trim() : null;
    }

    private String trimToLength(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private record CoverImageChoice(UUID id, String storageType, String status, long fileSize, boolean fileSizeWasNull) {
        boolean isManaged() {
            return !"EXTERNAL".equals(storageType);
        }

        boolean isLocalStored() {
            return "LOCAL".equals(storageType) && "LOCAL_STORED".equals(status);
        }

        long fileSizeOrZero() {
            return fileSizeWasNull ? 0L : fileSize;
        }
    }

    private record RawFlatMetadataRow(
            Set<String> actorNames,
            Set<String> directorNames,
            Set<String> areaCodes,
            Set<String> languageCodes,
            Set<String> genreCodes,
            String categoryCode) {
    }

    private record UnifiedAdultAssessmentBase(
            UUID id,
            String title,
            String aliasTitle,
            String description,
            String remarks,
            String subtitle,
            String categoryCode,
            String categoryName,
            String actorNamesJson,
            String directorNamesJson,
            String genreCodesJson) {
    }

    private record AdultAssessmentWrite(UUID unifiedVideoId, AdultAssessment assessment) {
    }
}

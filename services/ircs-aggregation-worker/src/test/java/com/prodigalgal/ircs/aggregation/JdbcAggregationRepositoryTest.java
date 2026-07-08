package com.prodigalgal.ircs.aggregation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.metadata.JsonStringArrays;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class JdbcAggregationRepositoryTest {

    private static final ObjectMapper OBJECT_MAPPER = JsonMapper.builder().build();

    @Mock
    private NamedParameterJdbcTemplate jdbcTemplate;

    @Mock
    private AggregationMatchingStrategy matchingStrategy;

    @Mock
    private AggregationContextSearchClient contextSearchClient;

    @Captor
    private ArgumentCaptor<String> sqlCaptor;

    @Captor
    private ArgumentCaptor<MapSqlParameterSource> paramsCaptor;

    @Test
    void recallsTitleYearCandidatesWhenExternalIdsAreAbsent() {
        RawVideoAggregationRecord rawVideo = rawVideo("Codex Signal", "2026", null);
        UUID unifiedVideoId = UUID.randomUUID();
        UnifiedVideoAggregationCandidate candidate = new UnifiedVideoAggregationCandidate(
                unifiedVideoId,
                "Codex Signal",
                null,
                null,
                "2026",
                "12",
                "45m",
                null,
                "电影",
                null,
                null,
                null,
                null,
                null);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy);

        when(jdbcTemplate.query(
                        any(String.class),
                        any(MapSqlParameterSource.class),
                        anyUnifiedCandidateRowMapper()))
                .thenReturn(List.of(candidate));
        when(matchingStrategy.findMatchPlan(eq(rawVideo), eq(List.of(candidate))))
                .thenReturn(AggregationMatchPlan.rootOnly(unifiedVideoId));

        Optional<UUID> result = repository.findMatchingUnifiedVideo(rawVideo);

        assertEquals(Optional.of(unifiedVideoId), result);
        verify(jdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), anyUnifiedCandidateRowMapper());
        assertTrue(sqlCaptor.getValue().contains("similarity(title, :title)"));
        assertTrue(sqlCaptor.getValue().contains("year = :year"));
        assertEquals("Codex Signal", paramsCaptor.getValue().getValue("title"));
        assertEquals("2026", paramsCaptor.getValue().getValue("year"));
        verify(matchingStrategy).findMatchPlan(rawVideo, List.of(candidate));
    }

    @Test
    void titleRecallOmitsYearPredicateWhenRawYearIsBlank() {
        RawVideoAggregationRecord rawVideo = rawVideo("Codex Signal", "", null);
        UUID unifiedVideoId = UUID.randomUUID();
        UnifiedVideoAggregationCandidate candidate = new UnifiedVideoAggregationCandidate(
                unifiedVideoId,
                "Codex Signal",
                null,
                null,
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy);

        when(jdbcTemplate.query(
                        any(String.class),
                        any(MapSqlParameterSource.class),
                        anyUnifiedCandidateRowMapper()))
                .thenReturn(List.of(candidate));
        when(matchingStrategy.findMatchPlan(eq(rawVideo), eq(List.of(candidate))))
                .thenReturn(AggregationMatchPlan.rootOnly(unifiedVideoId));

        Optional<UUID> result = repository.findMatchingUnifiedVideo(rawVideo);

        assertEquals(Optional.of(unifiedVideoId), result);
        verify(jdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), anyUnifiedCandidateRowMapper());
        assertFalse(sqlCaptor.getValue().contains(":year is null"));
        assertFalse(sqlCaptor.getValue().contains("year = :year"));
        assertTrue(sqlCaptor.getValue().contains("similarity(title, :title)"));
        assertFalse(paramsCaptor.getValue().hasValue("year"));
    }

    @Test
    void recallsContextUnifiedCandidatesForRawBatchWithStableDeduplication() {
        RawVideoAggregationRecord rawWithExternalId = rawVideo("Codex Signal", "2026", "douban-1");
        RawVideoAggregationRecord rawWithTitleOnly = rawVideo("Codex Signal", "2026", null);
        UUID unifiedVideoId = UUID.randomUUID();
        UnifiedVideoAggregationCandidate candidate = new UnifiedVideoAggregationCandidate(
                unifiedVideoId,
                "Codex Signal",
                null,
                null,
                "2026",
                "12",
                "45m",
                null,
                "电影",
                "douban-1",
                null,
                null,
                null,
                null);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy);

        when(jdbcTemplate.query(
                        any(String.class),
                        any(MapSqlParameterSource.class),
                        anyUnifiedCandidateRowMapper()))
                .thenReturn(List.of(candidate), List.of(candidate));

        List<UnifiedVideoAggregationCandidate> result =
                repository.findContextUnifiedCandidates(List.of(rawWithExternalId, rawWithTitleOnly));

        assertEquals(List.of(candidate), result);
        verify(jdbcTemplate, times(2)).query(sqlCaptor.capture(), paramsCaptor.capture(), anyUnifiedCandidateRowMapper());
        assertTrue(sqlCaptor.getAllValues().get(0).contains("douban_id = :doubanId"));
        assertTrue(sqlCaptor.getAllValues().get(1).contains("similarity(title, :title)"));
    }

    @Test
    void contextTitleRecallUsesSearchServiceIdsAndLoadsCandidatesBackFromDb() {
        RawVideoAggregationRecord rawWithTitleOnly = rawVideo("Codex Signal", "2026", null);
        UUID unifiedVideoId = UUID.randomUUID();
        UnifiedVideoAggregationCandidate candidate = new UnifiedVideoAggregationCandidate(
                unifiedVideoId,
                "Codex Signal",
                null,
                null,
                "2026",
                "12",
                "45m",
                null,
                "电影",
                null,
                null,
                null,
                null,
                null);
        JdbcAggregationRepository repository =
                JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy, contextSearchClient);

        when(contextSearchClient.findCandidateUnifiedVideoIds("Codex Signal", "2026"))
                .thenReturn(AggregationContextSearchClient.ContextSearchResult.attempted(List.of(unifiedVideoId)));
        when(jdbcTemplate.query(
                        any(String.class),
                        any(MapSqlParameterSource.class),
                        anyUnifiedCandidateRowMapper()))
                .thenReturn(List.of(candidate));

        List<UnifiedVideoAggregationCandidate> result =
                repository.findContextUnifiedCandidates(List.of(rawWithTitleOnly));

        assertEquals(List.of(candidate), result);
        verify(contextSearchClient).findCandidateUnifiedVideoIds("Codex Signal", "2026");
        verify(jdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), anyUnifiedCandidateRowMapper());
        assertTrue(sqlCaptor.getValue().contains("where id in (:ids)"));
        assertEquals(List.of(unifiedVideoId), paramsCaptor.getValue().getValue("ids"));
    }

    @Test
    void contextTitleRecallDoesNotFallbackToSqlWhenSearchServiceReturnsEmpty() {
        RawVideoAggregationRecord rawWithTitleOnly = rawVideo("Codex Signal", "2026", null);
        JdbcAggregationRepository repository =
                JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy, contextSearchClient);

        when(contextSearchClient.findCandidateUnifiedVideoIds("Codex Signal", "2026"))
                .thenReturn(AggregationContextSearchClient.ContextSearchResult.attempted(List.of()));

        List<UnifiedVideoAggregationCandidate> result =
                repository.findContextUnifiedCandidates(List.of(rawWithTitleOnly));

        assertEquals(List.of(), result);
        verify(contextSearchClient).findCandidateUnifiedVideoIds("Codex Signal", "2026");
        verify(jdbcTemplate, times(0)).query(
                any(String.class),
                any(MapSqlParameterSource.class),
                anyUnifiedCandidateRowMapper());
    }

    @Test
    void contextTitleRecallFallsBackToSqlOnlyWhenSearchRecallIsNotAttempted() {
        RawVideoAggregationRecord rawWithTitleOnly = rawVideo("Codex Signal", "2026", null);
        UUID unifiedVideoId = UUID.randomUUID();
        UnifiedVideoAggregationCandidate candidate = new UnifiedVideoAggregationCandidate(
                unifiedVideoId,
                "Codex Signal",
                null,
                null,
                "2026",
                "12",
                "45m",
                null,
                "电影",
                null,
                null,
                null,
                null,
                null);
        JdbcAggregationRepository repository =
                JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy, contextSearchClient);

        when(contextSearchClient.findCandidateUnifiedVideoIds("Codex Signal", "2026"))
                .thenReturn(AggregationContextSearchClient.ContextSearchResult.notAttempted());
        when(jdbcTemplate.query(
                        any(String.class),
                        any(MapSqlParameterSource.class),
                        anyUnifiedCandidateRowMapper()))
                .thenReturn(List.of(candidate));

        List<UnifiedVideoAggregationCandidate> result =
                repository.findContextUnifiedCandidates(List.of(rawWithTitleOnly));

        assertEquals(List.of(candidate), result);
        verify(jdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), anyUnifiedCandidateRowMapper());
        assertTrue(sqlCaptor.getValue().contains("similarity(title, :title)"));
    }

    @Test
    void contextExternalRecallDoesNotCallSearchServiceWhenDoubanOrTmdbIsPresent() {
        RawVideoAggregationRecord rawWithExternalId = rawVideo("Codex Signal", "2026", "douban-1");
        UUID unifiedVideoId = UUID.randomUUID();
        UnifiedVideoAggregationCandidate candidate = new UnifiedVideoAggregationCandidate(
                unifiedVideoId,
                "Codex Signal",
                null,
                null,
                "2026",
                "12",
                "45m",
                null,
                "电影",
                "douban-1",
                null,
                null,
                null,
                null);
        JdbcAggregationRepository repository =
                JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy, contextSearchClient);

        when(jdbcTemplate.query(
                        any(String.class),
                        any(MapSqlParameterSource.class),
                        anyUnifiedCandidateRowMapper()))
                .thenReturn(List.of(candidate));

        List<UnifiedVideoAggregationCandidate> result =
                repository.findContextUnifiedCandidates(List.of(rawWithExternalId));

        assertEquals(List.of(candidate), result);
        verify(contextSearchClient, times(0)).findCandidateUnifiedVideoIds(any(String.class), any(String.class));
        verify(jdbcTemplate).query(sqlCaptor.capture(), paramsCaptor.capture(), anyUnifiedCandidateRowMapper());
        assertTrue(sqlCaptor.getValue().contains("douban_id = :doubanId"));
    }

    @Test
    void contextExternalRecallUsesOnlyDoubanAndTmdbLikeV1() {
        RawVideoAggregationRecord rawVideo = new RawVideoAggregationRecord(
                UUID.randomUUID(),
                null,
                null,
                "Aggregation description",
                "2026",
                new BigDecimal("8.9"),
                LocalDate.of(2026, 6, 4),
                "12",
                "45m",
                null,
                null,
                null,
                "电影",
                null,
                null,
                "imdb-1",
                "rt-1",
                "READY",
                "SUCCESS",
                "PROCESSING");
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy);

        List<UnifiedVideoAggregationCandidate> result = repository.findContextUnifiedCandidates(List.of(rawVideo));

        assertEquals(List.of(), result);
        verify(jdbcTemplate, times(0)).query(
                any(String.class),
                any(MapSqlParameterSource.class),
                anyUnifiedCandidateRowMapper());
    }

    @Test
    void findRawVideoExposesStandardCategoryNameForGraphBucket() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-raw-category-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createCandidateSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID categoryId = UUID.randomUUID();
        UUID rawVideoId = UUID.randomUUID();
        jdbc.update("insert into standard_category (id, name, slug) values (?, ?, ?)", categoryId, "电影", "movie");
        jdbc.update("""
                insert into raw_videos (
                    id, created_at, updated_at, version, source_vid, source_hash, title, year,
                    enrichment_status, normalization_status, aggregation_status, category_code
                ) values (?, now(), now(), 0, 'source-1', 'hash-1', 'Codex Signal', '2026',
                    'SUCCESS', 'READY', 'PROCESSING', 'movie')
                """, rawVideoId);

        RawVideoAggregationRecord result = repository.findRawVideo(rawVideoId).orElseThrow();

        assertEquals("电影", result.categoryName());
    }

    @Test
    void findRawVideoExposesMetadataNamesForV1GraphScoring() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-raw-metadata-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createCandidateSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID rawVideoId = UUID.randomUUID();
        jdbc.update("""
                insert into raw_videos (
                    id, created_at, updated_at, version, source_vid, source_hash, title, year,
                    actor_names, director_names, area_codes,
                    enrichment_status, normalization_status, aggregation_status
                ) values (?, now(), now(), 0, 'source-1', 'hash-1', 'Codex Signal', '2026',
                    cast(? as jsonb), cast(? as jsonb), cast(? as jsonb),
                    'SUCCESS', 'READY', 'PROCESSING')
                """, rawVideoId, flatJson("Actor A"), flatJson("Director A"), flatJson("JP"));

        RawVideoAggregationRecord result = repository.findRawVideo(rawVideoId).orElseThrow();

        assertEquals(Set.of("Actor A"), result.actorNames());
        assertEquals(Set.of("Director A"), result.directorNames());
        assertEquals(Set.of("JP"), result.areaNames());
    }

    @Test
    void contextUnifiedCandidatesExposeStandardCategoryNameForGraphBucket() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-unified-category-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createCandidateSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID categoryId = UUID.randomUUID();
        UUID rawVideoId = UUID.randomUUID();
        UUID unifiedVideoId = UUID.randomUUID();
        jdbc.update("insert into standard_category (id, name, slug) values (?, ?, ?)", categoryId, "电视剧", "tv");
        jdbc.update("""
                insert into raw_videos (
                    id, created_at, updated_at, version, source_vid, source_hash, title, year,
                    douban_id, enrichment_status, normalization_status, aggregation_status
                ) values (?, now(), now(), 0, 'source-1', 'hash-1', 'Codex Signal', '2026',
                    'douban-1', 'SUCCESS', 'READY', 'PROCESSING')
                """, rawVideoId);
        jdbc.update("""
                insert into unified_videos (
                    id, created_at, updated_at, version, title, year, douban_id, category_code, metadata_status
                ) values (?, now(), now(), 0, 'Codex Signal', '2026', 'douban-1', 'tv', 'SYNCED')
                """, unifiedVideoId);
        RawVideoAggregationRecord rawVideo = repository.findRawVideo(rawVideoId).orElseThrow();

        List<UnifiedVideoAggregationCandidate> result = repository.findContextUnifiedCandidates(List.of(rawVideo));

        assertEquals(1, result.size());
        assertEquals("电视剧", result.getFirst().categoryName());
    }

    @Test
    void contextUnifiedCandidatesExposeMetadataNamesForV1GraphScoring() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-unified-metadata-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createCandidateSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID rawVideoId = UUID.randomUUID();
        UUID unifiedVideoId = UUID.randomUUID();
        jdbc.update("""
                insert into raw_videos (
                    id, created_at, updated_at, version, source_vid, source_hash, title, year,
                    douban_id, enrichment_status, normalization_status, aggregation_status
                ) values (?, now(), now(), 0, 'source-1', 'hash-1', 'Codex Signal', '2026',
                    'douban-1', 'SUCCESS', 'READY', 'PROCESSING')
                """, rawVideoId);
        jdbc.update("""
                insert into unified_videos (
                    id, created_at, updated_at, version, title, year, douban_id,
                    actor_names, director_names, area_codes, metadata_status
                ) values (?, now(), now(), 0, 'Codex Signal', '2026', 'douban-1',
                    cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), 'SYNCED')
                """, unifiedVideoId, flatJson("Actor U"), flatJson("Director U"), flatJson("US"));
        RawVideoAggregationRecord rawVideo = repository.findRawVideo(rawVideoId).orElseThrow();

        List<UnifiedVideoAggregationCandidate> result = repository.findContextUnifiedCandidates(List.of(rawVideo));

        assertEquals(1, result.size());
        assertEquals(Set.of("Actor U"), result.getFirst().actorNames());
        assertEquals(Set.of("Director U"), result.getFirst().directorNames());
        assertEquals(Set.of("US"), result.getFirst().areaNames());
    }


    @Test
    void mergeDuplicateUnifiedVideosMigratesBindingsAndDeletesVictimRowsInOrder() {
        UUID rootId = UUID.randomUUID();
        UUID victimId = UUID.randomUUID();
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy);
        when(jdbcTemplate.query(any(String.class), any(MapSqlParameterSource.class), any(RowMapper.class)))
                .thenReturn(List.of());

        repository.mergeDuplicateUnifiedVideos(rootId, List.of(victimId));

        verify(jdbcTemplate, times(8)).update(sqlCaptor.capture(), paramsCaptor.capture());
        List<String> sqlStatements = sqlCaptor.getAllValues();
        assertTrue(sqlStatements.get(0).contains("insert into raw_video_unified_video"));
        assertTrue(sqlStatements.get(0).contains("on conflict do nothing"));
        assertTrue(sqlStatements.get(1).contains("delete from raw_video_unified_video"));
        assertTrue(sqlStatements.get(2).contains("delete from member_favorites"));
        assertTrue(sqlStatements.get(2).contains("root.member_id = member_favorites.member_id"));
        assertTrue(sqlStatements.get(3).contains("update member_favorites"));
        assertTrue(sqlStatements.get(4).contains("delete from member_watch_histories"));
        assertTrue(sqlStatements.get(4).contains("root.member_id = member_watch_histories.member_id"));
        assertTrue(sqlStatements.get(5).contains("update member_watch_histories"));
        assertTrue(sqlStatements.get(6).contains("update unified_videos root"));
        assertTrue(sqlStatements.get(6).contains("victim.id = :victimId"));
        assertTrue(sqlStatements.get(7).contains("delete from unified_videos"));
        assertTrue(sqlStatements.stream().noneMatch(this::containsRetiredUnifiedMetadataTable));
        assertTrue(paramsCaptor.getAllValues().subList(0, 6).stream()
                .allMatch(params -> rootId.equals(params.getValue("rootId"))
                        && List.of(victimId).equals(params.getValue("victimIds"))));
        assertEquals(rootId, paramsCaptor.getAllValues().get(6).getValue("rootId"));
        assertEquals(victimId, paramsCaptor.getAllValues().get(6).getValue("victimId"));
        assertEquals(rootId, paramsCaptor.getAllValues().get(7).getValue("rootId"));
        assertEquals(List.of(victimId), paramsCaptor.getAllValues().get(7).getValue("victimIds"));
    }

    @Test
    void deleteAllAggregationResetOwnedRowsDeletesGlobalResetTablesInSafeOrder() {
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy);
        when(jdbcTemplate.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(1);

        int changedRows = repository.deleteAllAggregationResetOwnedRows();

        assertEquals(8, changedRows);
        verify(jdbcTemplate, times(8)).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        List<String> sqlStatements = sqlCaptor.getAllValues();
        assertTrue(sqlStatements.get(0).contains("delete from magnet_provider_runs"));
        assertTrue(sqlStatements.get(1).contains("delete from magnet_search_jobs"));
        assertTrue(sqlStatements.get(2).contains("delete from magnet_links"));
        assertTrue(sqlStatements.get(3).contains("delete from member_favorites"));
        assertTrue(sqlStatements.get(4).contains("delete from member_watch_histories"));
        assertTrue(sqlStatements.get(5).contains("delete from raw_video_unified_video"));
        assertTrue(sqlStatements.get(6).contains("delete from unified_video_tags"));
        assertTrue(sqlStatements.get(7).contains("delete from unified_videos"));
    }

    @Test
    void markAllRawAggregationPendingUsesAggregationTimelineOnly() {
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy);
        when(jdbcTemplate.update(any(String.class), any(MapSqlParameterSource.class))).thenReturn(3);

        int changedRows = repository.markAllRawAggregationPending();

        assertEquals(3, changedRows);
        verify(jdbcTemplate).update(sqlCaptor.capture(), any(MapSqlParameterSource.class));
        assertTrue(sqlCaptor.getValue().contains("set aggregation_status = 'PENDING'"));
        assertTrue(sqlCaptor.getValue().contains("aggregation_status_updated_at = now()"));
    }

    @Test
    void findPendingRawIdsWithoutRuntimeProgressSelectsPendingRawVideosOnly() {
        UUID rawVideoId = UUID.randomUUID();
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy);
        when(jdbcTemplate.queryForList(any(String.class), any(MapSqlParameterSource.class), eq(UUID.class)))
                .thenReturn(List.of(rawVideoId));

        List<UUID> result = repository.findPendingRawIdsWithoutRuntimeProgress(25);

        assertEquals(List.of(rawVideoId), result);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), paramsCaptor.capture(), eq(UUID.class));
        assertTrue(sqlCaptor.getValue().contains("from raw_videos rv"));
        assertTrue(sqlCaptor.getValue().contains("where rv.aggregation_status = 'PENDING'"));
        assertEquals(25, paramsCaptor.getValue().getValue("limit"));
    }

    @Test
    void findUnifiedIdsMissingCoverFromRawSelectsBoundUnifiedVideosWithRawCover() {
        UUID unifiedVideoId = UUID.randomUUID();
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(jdbcTemplate, matchingStrategy);
        when(jdbcTemplate.queryForList(any(String.class), any(MapSqlParameterSource.class), eq(UUID.class)))
                .thenReturn(List.of(unifiedVideoId));

        List<UUID> result = repository.findUnifiedIdsMissingCoverFromRaw(10);

        assertEquals(List.of(unifiedVideoId), result);
        verify(jdbcTemplate).queryForList(sqlCaptor.capture(), paramsCaptor.capture(), eq(UUID.class));
        assertTrue(sqlCaptor.getValue().contains("from unified_videos uv"));
        assertTrue(sqlCaptor.getValue().contains("join raw_video_unified_video rvuv"));
        assertTrue(sqlCaptor.getValue().contains("uv.cover_image_id is null"));
        assertTrue(sqlCaptor.getValue().contains("rv.cover_image_id is not null"));
        assertEquals(10, paramsCaptor.getValue().getValue("limit"));
    }

    @Test
    void backfillUnifiedCoverImagesFromRawUpdatesMissingUnifiedCoversInBatch() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-cover-backfill-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createPipelineSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID unifiedVideoId = UUID.randomUUID();
        UUID firstRawVideoId = UUID.randomUUID();
        UUID secondRawVideoId = UUID.randomUUID();
        UUID firstCover = UUID.randomUUID();
        UUID secondCover = UUID.randomUUID();
        seedCoverImage(jdbc, firstCover, "EXTERNAL", null);
        seedCoverImage(jdbc, secondCover, "LOCAL", 128L);
        seedCoverUnified(jdbc, unifiedVideoId, null, "[]");
        seedCoverRaw(jdbc, firstRawVideoId, "cover-backfill-first", firstCover, LocalDateTime.of(2026, 1, 1, 0, 0));
        seedCoverRaw(jdbc, secondRawVideoId, "cover-backfill-second", secondCover, LocalDateTime.of(2026, 1, 2, 0, 0));
        bindPipelineRaw(jdbc, firstRawVideoId, unifiedVideoId);
        bindPipelineRaw(jdbc, secondRawVideoId, unifiedVideoId);

        List<UnifiedCoverBackfillResult> result = repository.backfillUnifiedCoverImagesFromRaw(10);

        assertEquals(List.of(new UnifiedCoverBackfillResult(unifiedVideoId, firstCover, "EXTERNAL", "UNPROCESSED")), result);
        assertEquals(firstCover, scalar(jdbc, "select cover_image_id from unified_videos where id = ?", unifiedVideoId, UUID.class));
    }

    @Test
    void mergeDuplicateUnifiedVideosMigratesRowsWithConflictSafeCleanup() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-merge-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createMergeSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID rootId = UUID.randomUUID();
        UUID victimId = UUID.randomUUID();
        UUID rawId = UUID.randomUUID();
        UUID memberWithConflict = UUID.randomUUID();
        UUID memberWithoutConflict = UUID.randomUUID();
        seedMergeFixture(jdbc, rootId, victimId, rawId, memberWithConflict, memberWithoutConflict);

        repository.mergeDuplicateUnifiedVideos(rootId, List.of(victimId));

        assertEquals(1, count(jdbc, "select count(*) from unified_videos where id = ?", rootId));
        assertEquals(0, count(jdbc, "select count(*) from unified_videos where id = ?", victimId));
        assertEquals(1, count(jdbc, "select count(*) from raw_video_unified_video where raw_video_id = ? and unified_video_id = ?", rawId, rootId));
        assertEquals(0, count(jdbc, "select count(*) from raw_video_unified_video where unified_video_id = ?", victimId));
        assertEquals("BOUND", jdbc.queryForObject(
                "select aggregation_status from raw_videos where id = ?",
                String.class,
                rawId));
        assertEquals(1, count(jdbc, "select count(*) from member_favorites where member_id = ? and unified_video_id = ?", memberWithConflict, rootId));
        assertEquals(0, count(jdbc, "select count(*) from member_favorites where unified_video_id = ?", victimId));
        assertEquals(1, count(jdbc, "select count(*) from member_watch_histories where member_id = ? and unified_video_id = ?", memberWithoutConflict, rootId));
        assertEquals(0, count(jdbc, "select count(*) from member_watch_histories where unified_video_id = ?", victimId));
        assertEquals(Set.of("Root Actor", "Victim Actor"), jsonSet(jdbc, "select actor_names from unified_videos where id = ?", rootId));
        assertEquals(Set.of("Victim Director"), jsonSet(jdbc, "select director_names from unified_videos where id = ?", rootId));
        assertEquals(Set.of("shared-genre", "victim-genre"), jsonSet(jdbc, "select genre_codes from unified_videos where id = ?", rootId));
        assertEquals(Set.of("zh"), jsonSet(jdbc, "select language_codes from unified_videos where id = ?", rootId));
        assertEquals(Set.of("CN"), jsonSet(jdbc, "select area_codes from unified_videos where id = ?", rootId));
        assertEquals("movie", scalar(jdbc, "select category_code from unified_videos where id = ?", rootId, String.class));
        assertEquals("victim-douban", scalar(jdbc, "select douban_id from unified_videos where id = ?", rootId, String.class));
        assertEquals("root-tmdb", scalar(jdbc, "select tmdb_id from unified_videos where id = ?", rootId, String.class));
        assertEquals("victim-imdb", scalar(jdbc, "select imdb_id from unified_videos where id = ?", rootId, String.class));
        assertEquals("victim-rt", scalar(jdbc, "select rotten_tomatoes_id from unified_videos where id = ?", rootId, String.class));
        assertEquals("victim-description", scalar(jdbc, "select description from unified_videos where id = ?", rootId, String.class));
        assertEquals("root-alias", scalar(jdbc, "select alias_title from unified_videos where id = ?", rootId, String.class));
        assertEquals(new BigDecimal("8.8"), scalar(jdbc, "select score from unified_videos where id = ?", rootId, BigDecimal.class));
        assertEquals(LocalDate.of(2026, 1, 2), scalar(jdbc, "select published_at from unified_videos where id = ?", rootId, LocalDate.class));
        assertEquals("12", scalar(jdbc, "select total_episodes from unified_videos where id = ?", rootId, String.class));
        assertEquals("45m", scalar(jdbc, "select duration from unified_videos where id = ?", rootId, String.class));
        assertEquals("root-remarks", scalar(jdbc, "select remarks from unified_videos where id = ?", rootId, String.class));
        assertEquals(2, scalar(jdbc, "select season from unified_videos where id = ?", rootId, Integer.class));
        assertEquals("victim-subtitle", scalar(jdbc, "select subtitle from unified_videos where id = ?", rootId, String.class));
        assertEquals(
                LocalDateTime.of(2026, 6, 2, 10, 0),
                scalar(jdbc, "select last_trend_at from unified_videos where id = ?", rootId, LocalDateTime.class));
        assertEquals("DIRTY", scalar(jdbc, "select metadata_status from unified_videos where id = ?", rootId, String.class));
    }

    @Test
    void markBoundUpdatesAggregationStatusTimeWithoutMutatingBusinessUpdatedAt() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-status-time-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createPipelineSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID rawVideoId = UUID.randomUUID();
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 0, 0);
        LocalDateTime businessUpdatedAt = LocalDateTime.of(2026, 1, 2, 0, 0);
        LocalDateTime statusUpdatedAt = LocalDateTime.of(2026, 1, 3, 0, 0);
        jdbc.update("""
                insert into raw_videos (
                    id, created_at, updated_at, aggregation_status_updated_at, version,
                    source_vid, source_hash, title, enrichment_status, normalization_status, aggregation_status
                ) values (?, ?, ?, ?, 0, 'status-raw', 'status-hash', 'Status Raw', 'SUCCESS', 'READY', 'PROCESSING')
                """,
                rawVideoId,
                createdAt,
                businessUpdatedAt,
                statusUpdatedAt);

        assertEquals(1, repository.markBound(rawVideoId));

        assertEquals("BOUND", scalar(jdbc, "select aggregation_status from raw_videos where id = ?", rawVideoId, String.class));
        assertEquals(businessUpdatedAt, scalar(jdbc, "select updated_at from raw_videos where id = ?", rawVideoId, LocalDateTime.class));
        LocalDateTime changedStatusTime = scalar(
                jdbc,
                "select aggregation_status_updated_at from raw_videos where id = ?",
                rawVideoId,
                LocalDateTime.class);
        assertTrue(changedStatusTime.isAfter(statusUpdatedAt));
    }

    @Test
    void rebuildUnifiedPipelineRelationsVotesCategoryAndRebuildsUnlockedRelations() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-pipeline-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createPipelineSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID unifiedVideoId = UUID.randomUUID();
        UUID oldCategoryId = UUID.randomUUID();
        UUID votedCategoryId = UUID.randomUUID();
        UUID losingCategoryId = UUID.randomUUID();
        UUID rawOne = UUID.randomUUID();
        UUID rawTwo = UUID.randomUUID();
        UUID rawThree = UUID.randomUUID();
        UUID actorOne = UUID.randomUUID();
        UUID actorTwo = UUID.randomUUID();
        UUID staleActor = UUID.randomUUID();
        UUID directorOne = UUID.randomUUID();
        UUID staleDirector = UUID.randomUUID();
        UUID genreOne = UUID.randomUUID();
        UUID genreTwo = UUID.randomUUID();
        UUID staleGenre = UUID.randomUUID();
        UUID languageOne = UUID.randomUUID();
        UUID staleLanguage = UUID.randomUUID();
        UUID areaOne = UUID.randomUUID();
        UUID staleArea = UUID.randomUUID();
        seedPipelineUnlockedFixture(
                jdbc,
                unifiedVideoId,
                oldCategoryId,
                votedCategoryId,
                losingCategoryId,
                rawOne,
                rawTwo,
                rawThree,
                actorOne,
                actorTwo,
                staleActor,
                directorOne,
                staleDirector,
                genreOne,
                genreTwo,
                staleGenre,
                languageOne,
                staleLanguage,
                areaOne,
                staleArea);

        repository.rebuildUnifiedPipelineRelations(unifiedVideoId);

        assertEquals(votedCategoryId, scalar(
                jdbc,
                "select category_id from unified_videos where id = ?",
                unifiedVideoId,
                UUID.class));
        assertEquals("movie", scalar(jdbc, "select category_code from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals(Set.of("Actor One", "Actor Two"), jsonSet(jdbc, "select actor_names from unified_videos where id = ?", unifiedVideoId));
        assertEquals(Set.of("Director One"), jsonSet(jdbc, "select director_names from unified_videos where id = ?", unifiedVideoId));
        assertEquals(Set.of("genre-one", "genre-two"), jsonSet(jdbc, "select genre_codes from unified_videos where id = ?", unifiedVideoId));
        assertEquals(Set.of("language-one"), jsonSet(jdbc, "select language_codes from unified_videos where id = ?", unifiedVideoId));
        assertEquals(Set.of("area-one"), jsonSet(jdbc, "select area_codes from unified_videos where id = ?", unifiedVideoId));
    }

    @Test
    void rebuildUnifiedPipelineRelationsPreservesLockedFields() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-pipeline-locked-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createPipelineSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID unifiedVideoId = UUID.randomUUID();
        UUID lockedCategoryId = UUID.randomUUID();
        UUID rawCategoryId = UUID.randomUUID();
        UUID rawVideoId = UUID.randomUUID();
        UUID lockedActor = UUID.randomUUID();
        UUID rawActor = UUID.randomUUID();
        UUID lockedDirector = UUID.randomUUID();
        UUID rawDirector = UUID.randomUUID();
        UUID lockedGenre = UUID.randomUUID();
        UUID rawGenre = UUID.randomUUID();
        UUID lockedLanguage = UUID.randomUUID();
        UUID rawLanguage = UUID.randomUUID();
        UUID lockedArea = UUID.randomUUID();
        UUID rawArea = UUID.randomUUID();
        seedPipelineLockedFixture(
                jdbc,
                unifiedVideoId,
                lockedCategoryId,
                rawCategoryId,
                rawVideoId,
                lockedActor,
                rawActor,
                lockedDirector,
                rawDirector,
                lockedGenre,
                rawGenre,
                lockedLanguage,
                rawLanguage,
                lockedArea,
                rawArea);

        repository.rebuildUnifiedPipelineRelations(unifiedVideoId);

        assertEquals(lockedCategoryId, scalar(
                jdbc,
                "select category_id from unified_videos where id = ?",
                unifiedVideoId,
                UUID.class));
        assertEquals("locked-category", scalar(jdbc, "select category_code from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals(Set.of("Locked Actor"), jsonSet(jdbc, "select actor_names from unified_videos where id = ?", unifiedVideoId));
        assertEquals(Set.of("Locked Director"), jsonSet(jdbc, "select director_names from unified_videos where id = ?", unifiedVideoId));
        assertEquals(Set.of("locked-genre"), jsonSet(jdbc, "select genre_codes from unified_videos where id = ?", unifiedVideoId));
        assertEquals(Set.of("locked-language"), jsonSet(jdbc, "select language_codes from unified_videos where id = ?", unifiedVideoId));
        assertEquals(Set.of("locked-area"), jsonSet(jdbc, "select area_codes from unified_videos where id = ?", unifiedVideoId));
    }

    @Test
    void rebuildUnifiedPipelineScalarsAggregatesUnlockedRawSources() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-scalars-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createPipelineSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID unifiedVideoId = UUID.randomUUID();
        UUID rawOne = UUID.randomUUID();
        UUID rawTwo = UUID.randomUUID();
        UUID rawThree = UUID.randomUUID();
        seedScalarUnlockedFixture(jdbc, unifiedVideoId, rawOne, rawTwo, rawThree);

        repository.rebuildUnifiedPipelineScalars(unifiedVideoId);

        assertEquals("Voted Title", scalar(jdbc, "select title from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("2026", scalar(jdbc, "select year from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals(new BigDecimal("9.5"), scalar(jdbc, "select score from unified_videos where id = ?", unifiedVideoId, BigDecimal.class));
        assertEquals(LocalDate.of(2025, 1, 1), scalar(jdbc, "select published_at from unified_videos where id = ?", unifiedVideoId, LocalDate.class));
        assertEquals("longest alias title", scalar(jdbc, "select alias_title from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals(
                "the longest description wins",
                scalar(jdbc, "select description from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("latest remarks", scalar(jdbc, "select remarks from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("12", scalar(jdbc, "select total_episodes from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("45m", scalar(jdbc, "select duration from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals(2, scalar(jdbc, "select season from unified_videos where id = ?", unifiedVideoId, Integer.class));
        assertEquals("longest subtitle value", scalar(jdbc, "select subtitle from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("db-existing-douban", scalar(jdbc, "select douban_id from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("tmdb-first-valid", scalar(jdbc, "select tmdb_id from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("imdb-first-valid", scalar(jdbc, "select imdb_id from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("rt-first-valid", scalar(jdbc, "select rotten_tomatoes_id from unified_videos where id = ?", unifiedVideoId, String.class));
    }

    @Test
    void rebuildUnifiedPipelineScalarsPreservesLockedFieldsButExternalIdsIgnoreLocks() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-scalars-locked-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createPipelineSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID unifiedVideoId = UUID.randomUUID();
        UUID rawVideoId = UUID.randomUUID();
        seedScalarLockedFixture(jdbc, unifiedVideoId, rawVideoId);

        repository.rebuildUnifiedPipelineScalars(unifiedVideoId);

        assertEquals("Locked Title", scalar(jdbc, "select title from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("1999", scalar(jdbc, "select year from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals(new BigDecimal("1.1"), scalar(jdbc, "select score from unified_videos where id = ?", unifiedVideoId, BigDecimal.class));
        assertEquals(LocalDate.of(1999, 1, 1), scalar(jdbc, "select published_at from unified_videos where id = ?", unifiedVideoId, LocalDate.class));
        assertEquals("locked alias", scalar(jdbc, "select alias_title from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("locked description", scalar(jdbc, "select description from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("locked remarks", scalar(jdbc, "select remarks from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("1", scalar(jdbc, "select total_episodes from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("10m", scalar(jdbc, "select duration from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals(1, scalar(jdbc, "select season from unified_videos where id = ?", unifiedVideoId, Integer.class));
        assertEquals("locked subtitle", scalar(jdbc, "select subtitle from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("raw-douban", scalar(jdbc, "select douban_id from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("raw-tmdb", scalar(jdbc, "select tmdb_id from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("raw-imdb", scalar(jdbc, "select imdb_id from unified_videos where id = ?", unifiedVideoId, String.class));
        assertEquals("raw-rt", scalar(jdbc, "select rotten_tomatoes_id from unified_videos where id = ?", unifiedVideoId, String.class));
    }

    @Test
    void rebuildUnifiedAdultAssessmentMarksRestrictionWithoutChangingCategory() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-adult-assessment-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createPipelineSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID unifiedVideoId = UUID.randomUUID();
        UUID rawVideoId = UUID.randomUUID();
        UUID dataSourceId = UUID.randomUUID();

        seedStandardCategory(jdbc, UUID.randomUUID(), "movie", "电影");
        jdbc.update("""
                insert into data_sources (id, name, base_url, adult_restricted)
                values (?, 'Adult Source', 'https://jav.example.test', true)
                """, dataSourceId);
        jdbc.update("""
                insert into unified_videos (
                    id, created_at, updated_at, version, title, category_code,
                    actor_names, director_names, genre_codes, metadata_status
                ) values (
                    ?, now(), now(), 0, '普通电影标题', 'movie',
                    cast('[]' as jsonb), cast('[]' as jsonb), cast('[]' as jsonb), 'SYNCED'
                )
                """, unifiedVideoId);
        jdbc.update("""
                insert into raw_videos (
                    id, created_at, updated_at, version, source_vid, source_hash, title,
                    data_source_id, source_category_code, source_category_name, raw_metadata,
                    enrichment_status, normalization_status, aggregation_status, category_code
                ) values (
                    ?, now(), now(), 0, 'adult-raw', 'adult-hash', 'JAV Sample',
                    ?, 'movie', '电影', cast('{"site":"jav.example.test"}' as jsonb),
                    'SUCCESS', 'READY', 'BOUND', 'movie'
                )
                """, rawVideoId, dataSourceId);
        bindPipelineRaw(jdbc, rawVideoId, unifiedVideoId);

        repository.rebuildUnifiedAdultAssessment(unifiedVideoId);

        assertEquals(Boolean.TRUE, scalar(jdbc, "select adult_restricted from unified_videos where id = ?", unifiedVideoId, Boolean.class));
        assertEquals("movie", scalar(jdbc, "select category_code from unified_videos where id = ?", unifiedVideoId, String.class));
        String assessment = scalar(jdbc, "select adult_assessment from unified_videos where id = ?", unifiedVideoId, String.class);
        assertTrue(assessment.contains("dataSourceAdultRestricted"));
        assertFalse(assessment.contains("rawMetadata"));
    }

    @Test
    void rebuildUnifiedPipelineCoverImageChoosesFirstRawCoverWhenUnifiedCoverIsEmpty() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-cover-empty-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createPipelineSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID unifiedVideoId = UUID.randomUUID();
        UUID firstRaw = UUID.randomUUID();
        UUID secondRaw = UUID.randomUUID();
        UUID firstCover = UUID.randomUUID();
        UUID secondCover = UUID.randomUUID();
        seedCoverImage(jdbc, firstCover, "EXTERNAL", 100L);
        seedCoverImage(jdbc, secondCover, "EXTERNAL", 109L);
        seedCoverUnified(jdbc, unifiedVideoId, null, "[]");
        seedCoverRaw(jdbc, firstRaw, "hash-cover-first", firstCover, LocalDateTime.of(2026, 1, 1, 0, 0));
        seedCoverRaw(jdbc, secondRaw, "hash-cover-second", secondCover, LocalDateTime.of(2026, 1, 2, 0, 0));
        bindPipelineRaw(jdbc, firstRaw, unifiedVideoId);
        bindPipelineRaw(jdbc, secondRaw, unifiedVideoId);

        List<UUID> promoteCandidates = repository.rebuildUnifiedPipelineCoverImage(unifiedVideoId);

        assertTrue(promoteCandidates.isEmpty());
        assertEquals(firstCover, scalar(jdbc, "select cover_image_id from unified_videos where id = ?", unifiedVideoId, UUID.class));
    }

    @Test
    void rebuildUnifiedPipelineCoverImagePrefersManagedCoverOverExternalCurrent() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-cover-managed-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createPipelineSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID unifiedVideoId = UUID.randomUUID();
        UUID rawVideoId = UUID.randomUUID();
        UUID externalCover = UUID.randomUUID();
        UUID localCover = UUID.randomUUID();
        seedCoverImage(jdbc, externalCover, "EXTERNAL", 900L);
        seedCoverImage(jdbc, localCover, "LOCAL", 1L);
        seedCoverUnified(jdbc, unifiedVideoId, externalCover, "[]");
        seedCoverRaw(jdbc, rawVideoId, "hash-cover-local", localCover, LocalDateTime.of(2026, 1, 1, 0, 0));
        bindPipelineRaw(jdbc, rawVideoId, unifiedVideoId);

        List<UUID> promoteCandidates = repository.rebuildUnifiedPipelineCoverImage(unifiedVideoId);

        assertEquals(List.of(localCover), promoteCandidates);
        assertEquals(localCover, scalar(jdbc, "select cover_image_id from unified_videos where id = ?", unifiedVideoId, UUID.class));
    }

    @Test
    void rebuildUnifiedPipelineCoverImageUsesTenPercentThresholdForSameManagedness() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-cover-threshold-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createPipelineSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID unifiedVideoId = UUID.randomUUID();
        UUID firstRaw = UUID.randomUUID();
        UUID secondRaw = UUID.randomUUID();
        UUID currentLocal = UUID.randomUUID();
        UUID tooSmallLocal = UUID.randomUUID();
        UUID largeEnoughLocal = UUID.randomUUID();
        seedCoverImage(jdbc, currentLocal, "LOCAL", 100L);
        seedCoverImage(jdbc, tooSmallLocal, "LOCAL", 110L);
        seedCoverImage(jdbc, largeEnoughLocal, "LOCAL", 111L);
        seedCoverUnified(jdbc, unifiedVideoId, currentLocal, "[]");
        seedCoverRaw(jdbc, firstRaw, "hash-cover-small", tooSmallLocal, LocalDateTime.of(2026, 1, 1, 0, 0));
        seedCoverRaw(jdbc, secondRaw, "hash-cover-large", largeEnoughLocal, LocalDateTime.of(2026, 1, 2, 0, 0));
        bindPipelineRaw(jdbc, firstRaw, unifiedVideoId);
        bindPipelineRaw(jdbc, secondRaw, unifiedVideoId);

        List<UUID> promoteCandidates = repository.rebuildUnifiedPipelineCoverImage(unifiedVideoId);

        assertEquals(List.of(largeEnoughLocal), promoteCandidates);
        assertEquals(largeEnoughLocal, scalar(jdbc, "select cover_image_id from unified_videos where id = ?", unifiedVideoId, UUID.class));
    }

    @Test
    void rebuildUnifiedPipelineCoverImageIgnoresCoverImageUrlLockLikeV1Handler() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-cover-locked-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createPipelineSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID unifiedVideoId = UUID.randomUUID();
        UUID rawVideoId = UUID.randomUUID();
        UUID externalCover = UUID.randomUUID();
        UUID r2Cover = UUID.randomUUID();
        seedCoverImage(jdbc, externalCover, "EXTERNAL", 900L);
        seedCoverImage(jdbc, r2Cover, "R2", null);
        seedCoverUnified(jdbc, unifiedVideoId, externalCover, "[\"coverImageUrl\"]");
        seedCoverRaw(jdbc, rawVideoId, "hash-cover-r2", r2Cover, LocalDateTime.of(2026, 1, 1, 0, 0));
        bindPipelineRaw(jdbc, rawVideoId, unifiedVideoId);

        List<UUID> promoteCandidates = repository.rebuildUnifiedPipelineCoverImage(unifiedVideoId);

        assertTrue(promoteCandidates.isEmpty());
        assertEquals(r2Cover, scalar(jdbc, "select cover_image_id from unified_videos where id = ?", unifiedVideoId, UUID.class));
    }

    @Test
    void rebuildUnifiedPipelineCoverImageDoesNotPromoteLocalCoverUnlessItIsStored() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:aggregation-cover-local-not-stored-" + UUID.randomUUID()
                        + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH;NON_KEYWORDS=YEAR;DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        createPipelineSchema(jdbc);
        NamedParameterJdbcTemplate namedJdbc = new NamedParameterJdbcTemplate(dataSource);
        JdbcAggregationRepository repository = JdbcAggregationRepository.forTest(namedJdbc, new AggregationMatchingStrategy());
        UUID unifiedVideoId = UUID.randomUUID();
        UUID rawVideoId = UUID.randomUUID();
        UUID localFetchingCover = UUID.randomUUID();
        seedCoverImage(jdbc, localFetchingCover, "LOCAL", "FETCHING", 200L);
        seedCoverUnified(jdbc, unifiedVideoId, null, "[]");
        seedCoverRaw(jdbc, rawVideoId, "hash-cover-fetching", localFetchingCover, LocalDateTime.of(2026, 1, 1, 0, 0));
        bindPipelineRaw(jdbc, rawVideoId, unifiedVideoId);

        List<UUID> promoteCandidates = repository.rebuildUnifiedPipelineCoverImage(unifiedVideoId);

        assertTrue(promoteCandidates.isEmpty());
        assertEquals(localFetchingCover, scalar(jdbc, "select cover_image_id from unified_videos where id = ?", unifiedVideoId, UUID.class));
    }

    private RawVideoAggregationRecord rawVideo(String title, String year, String doubanId) {
        return new RawVideoAggregationRecord(
                UUID.randomUUID(),
                title,
                null,
                "Aggregation description",
                year,
                new BigDecimal("8.9"),
                LocalDate.of(2026, 6, 4),
                "12",
                "45m",
                null,
                null,
                null,
                "电影",
                doubanId,
                null,
                null,
                null,
                "READY",
                "SUCCESS",
                "PROCESSING");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private RowMapper<UnifiedVideoAggregationCandidate> anyUnifiedCandidateRowMapper() {
        return (RowMapper) any(RowMapper.class);
    }

    private void createCandidateSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                create table standard_category (
                    id uuid primary key,
                    name varchar(100) not null,
                    slug varchar(100) not null unique
                )
                """);
        jdbc.execute("""
                create table raw_videos (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    aggregation_status_updated_at timestamp default now() not null,
                    version bigint,
                    source_vid varchar(255) not null,
                    source_hash varchar(64) not null unique,
                    title varchar(255),
                    alias_title varchar(255),
                    description text,
                    year varchar(20),
                    score decimal(3, 1),
                    published_at date,
                    total_episodes varchar(50),
                    duration varchar(50),
                    remarks varchar(255),
                    subtitle varchar(255),
                    season integer,
                    douban_id varchar(20),
                    tmdb_id varchar(20),
                    imdb_id varchar(20),
                    rotten_tomatoes_id varchar(50),
                    enrichment_status varchar(50) not null,
                    normalization_status varchar(50) not null,
                    aggregation_status varchar(50) not null,
                    actor_names jsonb default '[]',
                    director_names jsonb default '[]',
                    area_codes jsonb default '[]',
                    language_codes jsonb default '[]',
                    genre_codes jsonb default '[]',
                    category_code varchar(100)
                )
                """);
        jdbc.execute("""
                create table unified_videos (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint,
                    title varchar(255),
                    alias_title varchar(255),
                    subtitle varchar(255),
                    year varchar(20),
                    total_episodes varchar(50),
                    duration varchar(50),
                    remarks varchar(255),
                    douban_id varchar(20),
                    tmdb_id varchar(20),
                    imdb_id varchar(20),
                    rotten_tomatoes_id varchar(50),
                    season integer,
                    category_id uuid,
                    category_code varchar(100),
                    actor_names jsonb default '[]',
                    director_names jsonb default '[]',
                    area_codes jsonb default '[]',
                    language_codes jsonb default '[]',
                    genre_codes jsonb default '[]',
                    adult_restricted boolean default false not null,
                    adult_assessment jsonb default '{}',
                    adult_checked_at timestamp,
                    metadata_status varchar(50) not null
                )
                """);
    }

    private void createMergeSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                create table unified_videos (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint,
                    title varchar(255) not null,
                    alias_title varchar(255),
                    description text,
                    score decimal(3, 1),
                    published_at date,
                    douban_id varchar(20),
                    tmdb_id varchar(20),
                    imdb_id varchar(20),
                    rotten_tomatoes_id varchar(50),
                    total_episodes varchar(50),
                    duration varchar(50),
                    remarks varchar(255),
                    season integer,
                    subtitle varchar(255),
                    last_trend_at timestamp,
                    locked_fields jsonb,
                    category_code varchar(100),
                    actor_names jsonb default '[]',
                    director_names jsonb default '[]',
                    area_codes jsonb default '[]',
                    language_codes jsonb default '[]',
                    genre_codes jsonb default '[]',
                    adult_restricted boolean default false not null,
                    adult_assessment jsonb default '{}',
                    adult_checked_at timestamp,
                    metadata_status varchar(50) not null
                )
                """);
        jdbc.execute("""
                create table raw_videos (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    aggregation_status_updated_at timestamp default now() not null,
                    version bigint,
                    source_vid varchar(255) not null,
                    source_hash varchar(64) not null unique,
                    title varchar(255) not null,
                    enrichment_status varchar(50) not null,
                    normalization_status varchar(50) not null,
                    aggregation_status varchar(50) not null,
                    actor_names jsonb default '[]',
                    director_names jsonb default '[]',
                    area_codes jsonb default '[]',
                    language_codes jsonb default '[]',
                    genre_codes jsonb default '[]',
                    category_code varchar(100)
                )
                """);
        jdbc.execute("""
                create table raw_video_unified_video (
                    raw_video_id uuid not null,
                    unified_video_id uuid not null,
                    primary key (raw_video_id, unified_video_id)
                )
                """);
        jdbc.execute("""
                create table members (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    email varchar(255) not null,
                    password_hash varchar(255) not null,
                    role varchar(50) not null,
                    status varchar(50) not null
                )
                """);
        jdbc.execute("""
                create table member_favorites (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint,
                    member_id uuid not null,
                    unified_video_id uuid not null,
                    unique (member_id, unified_video_id)
                )
                """);
        jdbc.execute("""
                create table member_watch_histories (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint,
                    member_id uuid not null,
                    unified_video_id uuid not null,
                    episode_name varchar(255) not null,
                    progress_seconds integer,
                    duration_seconds integer,
                    last_watched_at timestamp not null,
                    unique (member_id, unified_video_id)
                )
                """);
    }

    private void createPipelineSchema(JdbcTemplate jdbc) {
        jdbc.execute("""
                create table unified_videos (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    version bigint,
                    title varchar(255) not null,
                    alias_title varchar(255),
                    description text,
                    year varchar(20),
                    score decimal(3, 1),
                    published_at date,
                    douban_id varchar(20),
                    tmdb_id varchar(20),
                    imdb_id varchar(20),
                    rotten_tomatoes_id varchar(50),
                    total_episodes varchar(50),
                    duration varchar(50),
                    remarks varchar(255),
                    season integer,
                    subtitle varchar(255),
                    cover_image_id uuid,
                    locked_fields jsonb,
                    category_id uuid,
                    category_code varchar(100),
                    actor_names jsonb default '[]',
                    director_names jsonb default '[]',
                    area_codes jsonb default '[]',
                    language_codes jsonb default '[]',
                    genre_codes jsonb default '[]',
                    adult_restricted boolean default false not null,
                    adult_assessment jsonb default '{}',
                    adult_checked_at timestamp,
                    metadata_status varchar(50) not null
                )
                """);
        jdbc.execute("""
                create table raw_videos (
                    id uuid primary key,
                    created_at timestamp not null,
                    updated_at timestamp not null,
                    aggregation_status_updated_at timestamp default now() not null,
                    version bigint,
                    source_vid varchar(255) not null,
                    source_hash varchar(64) not null unique,
                    title varchar(255) not null,
                    alias_title varchar(255),
                    description text,
                    year varchar(20),
                    score decimal(3, 1),
                    published_at date,
                    douban_id varchar(20),
                    tmdb_id varchar(20),
                    imdb_id varchar(20),
                    rotten_tomatoes_id varchar(50),
                    total_episodes varchar(50),
                    duration varchar(50),
                    remarks varchar(255),
                    season integer,
                    subtitle varchar(255),
                    cover_image_id uuid,
                    data_source_id uuid,
                    source_category_code varchar(100),
                    source_category_name varchar(255),
                    raw_metadata jsonb default '{}',
                    enrichment_status varchar(50) not null,
                    normalization_status varchar(50) not null,
                    aggregation_status varchar(50) not null,
                    actor_names jsonb default '[]',
                    director_names jsonb default '[]',
                    area_codes jsonb default '[]',
                    language_codes jsonb default '[]',
                    genre_codes jsonb default '[]',
                    category_code varchar(100)
                )
                """);
        jdbc.execute("""
                create table raw_video_unified_video (
                    raw_video_id uuid not null,
                    unified_video_id uuid not null,
                    primary key (raw_video_id, unified_video_id)
                )
                """);
        jdbc.execute("""
                create table data_sources (
                    id uuid primary key,
                    name varchar(255) not null,
                    base_url varchar(1024),
                    adult_restricted boolean default false not null
                )
                """);
        jdbc.execute("""
                create table standard_category (
                    id uuid primary key,
                    name varchar(100) not null,
                    slug varchar(100) not null unique
                )
                """);
        jdbc.execute("""
                create table cover_images (
                    id uuid primary key,
                    storage_type varchar(50) not null,
                    status varchar(50) not null,
                    file_size bigint,
                    source_domain_id uuid
                )
                """);
        jdbc.execute("""
                create table source_domains (
                    id uuid primary key,
                    domain_value varchar(1024) not null
                )
                """);
    }

    private void seedMergeFixture(
            JdbcTemplate jdbc,
            UUID rootId,
            UUID victimId,
            UUID rawId,
            UUID memberWithConflict,
            UUID memberWithoutConflict) {
        jdbc.update("""
                insert into unified_videos (
                    id, created_at, updated_at, version, title, alias_title, description, score, published_at,
                    douban_id, tmdb_id, imdb_id, rotten_tomatoes_id, total_episodes, duration, remarks,
                    season, subtitle, last_trend_at, locked_fields, metadata_status
                ) values (?, now(), now(), 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '[]', 'SYNCED')
                """,
                rootId,
                "Root",
                "root-alias",
                null,
                null,
                LocalDate.of(2026, 1, 2),
                null,
                "root-tmdb",
                null,
                null,
                null,
                "45m",
                "root-remarks",
                null,
                null,
                LocalDateTime.of(2026, 6, 1, 10, 0));
        jdbc.update("""
                insert into unified_videos (
                    id, created_at, updated_at, version, title, alias_title, description, score, published_at,
                    douban_id, tmdb_id, imdb_id, rotten_tomatoes_id, total_episodes, duration, remarks,
                    season, subtitle, last_trend_at, locked_fields, metadata_status
                ) values (?, now(), now(), 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '[]', 'SYNCED')
                """,
                victimId,
                "Victim",
                "victim-alias",
                "victim-description",
                new BigDecimal("8.8"),
                LocalDate.of(2026, 3, 4),
                "victim-douban",
                "victim-tmdb",
                "victim-imdb",
                "victim-rt",
                "12",
                "50m",
                "victim-remarks",
                2,
                "victim-subtitle",
                LocalDateTime.of(2026, 6, 2, 10, 0));
        jdbc.update("insert into raw_videos (id, created_at, updated_at, version, source_vid, source_hash, title, enrichment_status, normalization_status, aggregation_status) values (?, now(), now(), 0, 'raw-1', 'hash-1', 'Raw', 'SUCCESS', 'READY', 'BOUND')", rawId);
        jdbc.update("insert into raw_video_unified_video (raw_video_id, unified_video_id) values (?, ?)", rawId, victimId);
        jdbc.update("insert into raw_video_unified_video (raw_video_id, unified_video_id) values (?, ?)", rawId, rootId);
        seedMember(jdbc, memberWithConflict, "conflict@example.test");
        seedMember(jdbc, memberWithoutConflict, "move@example.test");
        jdbc.update("insert into member_favorites (id, created_at, updated_at, version, member_id, unified_video_id) values (?, now(), now(), 0, ?, ?)", UUID.randomUUID(), memberWithConflict, rootId);
        jdbc.update("insert into member_favorites (id, created_at, updated_at, version, member_id, unified_video_id) values (?, now(), now(), 0, ?, ?)", UUID.randomUUID(), memberWithConflict, victimId);
        jdbc.update("insert into member_favorites (id, created_at, updated_at, version, member_id, unified_video_id) values (?, now(), now(), 0, ?, ?)", UUID.randomUUID(), memberWithoutConflict, victimId);
        jdbc.update("insert into member_watch_histories (id, created_at, updated_at, version, member_id, unified_video_id, episode_name, progress_seconds, duration_seconds, last_watched_at) values (?, now(), now(), 0, ?, ?, 'Root Episode', 1, 10, now())", UUID.randomUUID(), memberWithConflict, rootId);
        jdbc.update("insert into member_watch_histories (id, created_at, updated_at, version, member_id, unified_video_id, episode_name, progress_seconds, duration_seconds, last_watched_at) values (?, now(), now(), 0, ?, ?, 'Victim Episode', 2, 10, now())", UUID.randomUUID(), memberWithConflict, victimId);
        jdbc.update("insert into member_watch_histories (id, created_at, updated_at, version, member_id, unified_video_id, episode_name, progress_seconds, duration_seconds, last_watched_at) values (?, now(), now(), 0, ?, ?, 'Move Episode', 3, 10, now())", UUID.randomUUID(), memberWithoutConflict, victimId);
        jdbc.update("""
                update unified_videos
                   set actor_names = cast(? as jsonb),
                       genre_codes = cast(? as jsonb),
                       category_code = 'movie'
                 where id = ?
                """, flatJson("Root Actor"), flatJson("shared-genre"), rootId);
        jdbc.update("""
                update unified_videos
                   set actor_names = cast(? as jsonb),
                       director_names = cast(? as jsonb),
                       genre_codes = cast(? as jsonb),
                       language_codes = cast(? as jsonb),
                       area_codes = cast(? as jsonb),
                       category_code = 'tv'
                 where id = ?
                """,
                flatJson("Victim Actor"),
                flatJson("Victim Director"),
                flatJson("shared-genre", "victim-genre"),
                flatJson("zh"),
                flatJson("CN"),
                victimId);
    }

    private void seedPipelineUnlockedFixture(
            JdbcTemplate jdbc,
            UUID unifiedVideoId,
            UUID oldCategoryId,
            UUID votedCategoryId,
            UUID losingCategoryId,
            UUID rawOne,
            UUID rawTwo,
            UUID rawThree,
            UUID actorOne,
            UUID actorTwo,
            UUID staleActor,
            UUID directorOne,
            UUID staleDirector,
            UUID genreOne,
            UUID genreTwo,
            UUID staleGenre,
            UUID languageOne,
            UUID staleLanguage,
            UUID areaOne,
            UUID staleArea) {
        jdbc.update("insert into unified_videos (id, created_at, updated_at, version, title, locked_fields, category_id, category_code, actor_names, director_names, genre_codes, language_codes, area_codes, metadata_status) values (?, now(), now(), 0, 'Unified', '[]', ?, 'old-category', cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), 'SYNCED')",
                unifiedVideoId,
                oldCategoryId,
                flatJson("Stale Actor"),
                flatJson("Stale Director"),
                flatJson("stale-genre"),
                flatJson("stale-language"),
                flatJson("stale-area"));
        seedStandardCategory(jdbc, votedCategoryId, "movie", "电影");
        seedStandardCategory(jdbc, losingCategoryId, "tv", "电视剧");
        seedPipelineRawVideo(jdbc, rawOne, "hash-pipeline-1", "movie");
        seedPipelineRawVideo(jdbc, rawTwo, "hash-pipeline-2", "movie");
        seedPipelineRawVideo(jdbc, rawThree, "hash-pipeline-3", "tv");
        bindPipelineRaw(jdbc, rawOne, unifiedVideoId);
        bindPipelineRaw(jdbc, rawTwo, unifiedVideoId);
        bindPipelineRaw(jdbc, rawThree, unifiedVideoId);
        updateRawFlatMetadata(
                jdbc,
                rawOne,
                flatJson("Actor One"),
                flatJson(),
                flatJson(),
                flatJson("language-one"),
                flatJson("genre-one"),
                "movie");
        updateRawFlatMetadata(
                jdbc,
                rawTwo,
                flatJson("Actor Two"),
                flatJson("Director One"),
                flatJson("area-one"),
                flatJson(),
                flatJson("genre-one"),
                "movie");
        updateRawFlatMetadata(
                jdbc,
                rawThree,
                flatJson("Actor One"),
                flatJson(),
                flatJson(),
                flatJson(),
                flatJson("genre-two"),
                "tv");
    }

    private void seedPipelineLockedFixture(
            JdbcTemplate jdbc,
            UUID unifiedVideoId,
            UUID lockedCategoryId,
            UUID rawCategoryId,
            UUID rawVideoId,
            UUID lockedActor,
            UUID rawActor,
            UUID lockedDirector,
            UUID rawDirector,
            UUID lockedGenre,
            UUID rawGenre,
            UUID lockedLanguage,
            UUID rawLanguage,
            UUID lockedArea,
            UUID rawArea) {
        jdbc.update("""
                insert into unified_videos (
                    id, created_at, updated_at, version, title, locked_fields, category_id, category_code,
                    actor_names, director_names, genre_codes, language_codes, area_codes, metadata_status
                ) values (
                    ?, now(), now(), 0, 'Locked Unified',
                    '["category","actors","directors","genres","area","language"]',
                    ?, 'locked-category',
                    cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), cast(? as jsonb), 'SYNCED'
                )
                """,
                unifiedVideoId,
                lockedCategoryId,
                flatJson("Locked Actor"),
                flatJson("Locked Director"),
                flatJson("locked-genre"),
                flatJson("locked-language"),
                flatJson("locked-area"));
        seedStandardCategory(jdbc, rawCategoryId, "raw-category", "Raw Category");
        seedPipelineRawVideo(jdbc, rawVideoId, "hash-pipeline-locked", "raw-category");
        bindPipelineRaw(jdbc, rawVideoId, unifiedVideoId);
        updateRawFlatMetadata(
                jdbc,
                rawVideoId,
                flatJson("Raw Actor"),
                flatJson("Raw Director"),
                flatJson("raw-area"),
                flatJson("raw-language"),
                flatJson("raw-genre"),
                "raw-category");
    }

    private void seedStandardCategory(JdbcTemplate jdbc, UUID categoryId, String slug, String name) {
        jdbc.update("insert into standard_category (id, name, slug) values (?, ?, ?)", categoryId, name, slug);
    }

    private void seedPipelineRawVideo(JdbcTemplate jdbc, UUID rawVideoId, String sourceHash, String categoryCode) {
        jdbc.update("""
                insert into raw_videos (
                    id, created_at, updated_at, version, source_vid, source_hash, title,
                    enrichment_status, normalization_status, aggregation_status, category_code
                ) values (?, now(), now(), 0, ?, ?, 'Raw', 'SUCCESS', 'READY', 'BOUND', ?)
                """, rawVideoId, sourceHash, sourceHash, categoryCode);
    }

    private void updateRawFlatMetadata(
            JdbcTemplate jdbc,
            UUID rawVideoId,
            String actorNames,
            String directorNames,
            String areaCodes,
            String languageCodes,
            String genreCodes,
            String categoryCode) {
        jdbc.update("""
                update raw_videos
                   set actor_names = cast(? as jsonb),
                       director_names = cast(? as jsonb),
                       area_codes = cast(? as jsonb),
                       language_codes = cast(? as jsonb),
                       genre_codes = cast(? as jsonb),
                       category_code = ?
                 where id = ?
                """, actorNames, directorNames, areaCodes, languageCodes, genreCodes, categoryCode, rawVideoId);
    }

    private void seedScalarUnlockedFixture(JdbcTemplate jdbc, UUID unifiedVideoId, UUID rawOne, UUID rawTwo, UUID rawThree) {
        jdbc.update("""
                insert into unified_videos (
                    id, created_at, updated_at, version, title, alias_title, description, year, score,
                    published_at, douban_id, tmdb_id, imdb_id, rotten_tomatoes_id, total_episodes,
                    duration, remarks, season, subtitle, locked_fields, metadata_status
                ) values (
                    ?, timestamp '2026-01-01 00:00:00', timestamp '2026-01-01 00:00:00', 0,
                    'Old Title', 'old alias', 'old description', '2000', 1.0, date '2000-01-01',
                    'db-existing-douban', '', '', '', '', '', 'old remarks', null, 'old subtitle',
                    '[]', 'SYNCED'
                )
                """, unifiedVideoId);
        seedScalarRawVideo(
                jdbc,
                rawOne,
                "hash-scalar-1",
                "Voted Title",
                "short",
                "short description",
                "2026",
                new BigDecimal("8.5"),
                LocalDate.of(2025, 1, 1),
                "0",
                "tmdb-first-valid",
                null,
                "rt-first-valid",
                "12",
                "45m",
                "older remarks",
                2,
                "sub",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 1, 0));
        seedScalarRawVideo(
                jdbc,
                rawTwo,
                "hash-scalar-2",
                "Voted Title",
                "longest alias title",
                "the longest description wins",
                "2026",
                new BigDecimal("9.5"),
                LocalDate.of(2025, 2, 1),
                "raw-douban-ignored",
                "tmdb-second-valid",
                "imdb-first-valid",
                null,
                "24",
                "60m",
                "latest remarks",
                3,
                "longest subtitle value",
                LocalDateTime.of(2026, 1, 2, 0, 0),
                LocalDateTime.of(2026, 1, 4, 1, 0));
        seedScalarRawVideo(
                jdbc,
                rawThree,
                "hash-scalar-3",
                "Other Title",
                "alias",
                "medium description",
                "2025",
                new BigDecimal("7.0"),
                LocalDate.of(2025, 3, 1),
                null,
                "tmdb-third-valid",
                "imdb-third-valid",
                "rt-third-valid",
                "36",
                "90m",
                "middle remarks",
                4,
                "subtitle",
                LocalDateTime.of(2026, 1, 3, 0, 0),
                LocalDateTime.of(2026, 1, 3, 1, 0));
        bindPipelineRaw(jdbc, rawOne, unifiedVideoId);
        bindPipelineRaw(jdbc, rawTwo, unifiedVideoId);
        bindPipelineRaw(jdbc, rawThree, unifiedVideoId);
    }

    private void seedScalarLockedFixture(JdbcTemplate jdbc, UUID unifiedVideoId, UUID rawVideoId) {
        jdbc.update("""
                insert into unified_videos (
                    id, created_at, updated_at, version, title, alias_title, description, year, score,
                    published_at, douban_id, tmdb_id, imdb_id, rotten_tomatoes_id, total_episodes,
                    duration, remarks, season, subtitle, locked_fields, metadata_status
                ) values (
                    ?, timestamp '2026-01-01 00:00:00', timestamp '2026-01-01 00:00:00', 0,
                    'Locked Title', 'locked alias', 'locked description', '1999', 1.1, date '1999-01-01',
                    '', '', '', '', '1', '10m', 'locked remarks', 1, 'locked subtitle',
                    '["title","year","score","publishedAt","aliasTitle","description","remarks","totalEpisodes","duration","season","subtitle"]',
                    'SYNCED'
                )
                """, unifiedVideoId);
        seedScalarRawVideo(
                jdbc,
                rawVideoId,
                "hash-scalar-locked",
                "Raw Title",
                "raw alias longest",
                "raw description longest",
                "2026",
                new BigDecimal("9.9"),
                LocalDate.of(2026, 6, 9),
                "raw-douban",
                "raw-tmdb",
                "raw-imdb",
                "raw-rt",
                "99",
                "99m",
                "raw remarks",
                9,
                "raw subtitle longest",
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 1, 1, 1, 0));
        bindPipelineRaw(jdbc, rawVideoId, unifiedVideoId);
    }

    private void seedScalarRawVideo(
            JdbcTemplate jdbc,
            UUID rawVideoId,
            String sourceHash,
            String title,
            String aliasTitle,
            String description,
            String year,
            BigDecimal score,
            LocalDate publishedAt,
            String doubanId,
            String tmdbId,
            String imdbId,
            String rottenTomatoesId,
            String totalEpisodes,
            String duration,
            String remarks,
            Integer season,
            String subtitle,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
        jdbc.update("""
                insert into raw_videos (
                    id, created_at, updated_at, version, source_vid, source_hash, title, alias_title,
                    description, year, score, published_at, douban_id, tmdb_id, imdb_id,
                    rotten_tomatoes_id, total_episodes, duration, remarks, season, subtitle,
                    enrichment_status, normalization_status, aggregation_status
                ) values (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SUCCESS', 'READY', 'BOUND')
                """,
                rawVideoId,
                createdAt,
                updatedAt,
                sourceHash,
                sourceHash,
                title,
                aliasTitle,
                description,
                year,
                score,
                publishedAt,
                doubanId,
                tmdbId,
                imdbId,
                rottenTomatoesId,
                totalEpisodes,
                duration,
                remarks,
                season,
                subtitle);
    }

    private void seedCoverImage(JdbcTemplate jdbc, UUID coverImageId, String storageType, Long fileSize) {
        seedCoverImage(jdbc, coverImageId, storageType, defaultCoverStatus(storageType), fileSize);
    }

    private void seedCoverImage(JdbcTemplate jdbc, UUID coverImageId, String storageType, String status, Long fileSize) {
        jdbc.update(
                "insert into cover_images (id, storage_type, status, file_size) values (?, ?, ?, ?)",
                coverImageId,
                storageType,
                status,
                fileSize);
    }

    private String defaultCoverStatus(String storageType) {
        return switch (storageType) {
            case "LOCAL" -> "LOCAL_STORED";
            case "R2" -> "REMOTE_STORED";
            default -> "UNPROCESSED";
        };
    }

    private void seedCoverUnified(JdbcTemplate jdbc, UUID unifiedVideoId, UUID coverImageId, String lockedFields) {
        jdbc.update("""
                insert into unified_videos (
                    id, created_at, updated_at, version, title, cover_image_id, locked_fields, metadata_status
                ) values (?, timestamp '2026-01-01 00:00:00', timestamp '2026-01-01 00:00:00', 0, 'Unified', ?, ?, 'SYNCED')
                """,
                unifiedVideoId,
                coverImageId,
                lockedFields);
    }

    private void seedCoverRaw(
            JdbcTemplate jdbc,
            UUID rawVideoId,
            String sourceHash,
            UUID coverImageId,
            LocalDateTime createdAt) {
        jdbc.update("""
                insert into raw_videos (
                    id, created_at, updated_at, version, source_vid, source_hash, title, cover_image_id,
                    enrichment_status, normalization_status, aggregation_status
                ) values (?, ?, ?, 0, ?, ?, 'Raw', ?, 'SUCCESS', 'READY', 'BOUND')
                """,
                rawVideoId,
                createdAt,
                createdAt,
                sourceHash,
                sourceHash,
                coverImageId);
    }

    private void bindPipelineRaw(JdbcTemplate jdbc, UUID rawVideoId, UUID unifiedVideoId) {
        jdbc.update(
                "insert into raw_video_unified_video (raw_video_id, unified_video_id) values (?, ?)",
                rawVideoId,
                unifiedVideoId);
    }

    private void seedMember(JdbcTemplate jdbc, UUID memberId, String email) {
        jdbc.update(
                "insert into members (id, created_at, updated_at, email, password_hash, role, status) values (?, now(), now(), ?, 'hash', 'USER', 'ACTIVE')",
                memberId,
                email);
    }

    private int count(JdbcTemplate jdbc, String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private <T> T scalar(JdbcTemplate jdbc, String sql, Object arg, Class<T> type) {
        return jdbc.queryForObject(sql, type, arg);
    }

    private Set<String> jsonSet(JdbcTemplate jdbc, String sql, Object arg) {
        return JsonStringArrays.readSet(OBJECT_MAPPER, scalar(jdbc, sql, arg, String.class));
    }

    private String flatJson(String... values) {
        return JsonStringArrays.write(OBJECT_MAPPER, List.of(values));
    }

    private boolean containsRetiredUnifiedMetadataTable(String sql) {
        return sql.contains("unified_video_actors")
                || sql.contains("unified_video_directors")
                || sql.contains("unified_video_genres")
                || sql.contains("unified_video_standard_languages")
                || sql.contains("unified_video_standard_areas");
    }
}

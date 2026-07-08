package com.prodigalgal.ircs.magnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class JdbcMagnetRepositoryTest {

    private JdbcTemplate jdbcTemplate;
    private JdbcMagnetRepository repository;

    @BeforeEach
    void setUp() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:magnet_" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        jdbcTemplate = new JdbcTemplate(dataSource);
        repository = new JdbcMagnetRepository(
                jdbcTemplate,
                new NamedParameterJdbcTemplate(dataSource),
                new ObjectMapper());
        createSchema();
    }

    @Test
    void writesSearchJobProviderRunAndMagnetLink() {
        UUID unifiedVideoId = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        insertUnifiedVideo(unifiedVideoId, "tt1234567");
        insertProvider(providerId, "codex_provider", true);

        MagnetUnifiedVideoSearchTarget target = repository.findUnifiedVideoSearchTarget(unifiedVideoId).orElseThrow();
        List<MagnetExternalIdQuery> plan = List.of(new MagnetExternalIdQuery("IMDB", target.imdbId()));
        MagnetSearchJobSummary runningJob = repository.createRunningSearchJob(unifiedVideoId, plan);
        MagnetProviderSummary provider = repository.listEnabledProviders().get(0);
        MagnetProviderCandidate candidate = candidate("abcdef1234567890abcdef1234567890abcdef12");

        MagnetLinkSummary link = repository.upsertLink(unifiedVideoId, provider, candidate);
        repository.createProviderRun(
                runningJob.id(),
                provider,
                plan.get(0),
                "SUCCESS",
                "fixture://magnet-provider/codex_provider?externalId=tt1234567",
                200,
                1,
                1,
                5,
                null);
        MagnetSearchJobSummary finishedJob = repository.finishSearchJob(
                runningJob.id(),
                List.of(provider.code()),
                1,
                1,
                0);

        assertEquals("RUNNING", runningJob.status());
        assertNotNull(runningJob.startedAt());
        assertEquals("SUCCESS", finishedJob.status());
        assertEquals(List.of("codex_provider"), finishedJob.providerCodes());
        assertEquals(1, finishedJob.totalCandidates());
        assertEquals(1, finishedJob.acceptedCount());
        assertEquals(0, finishedJob.rejectedCount());
        assertEquals("APPROVED", link.status());
        assertTrue(finishedJob.externalIdPlan().toString().contains("tt1234567"));
        assertEquals(1, countRows("magnet_search_jobs"));
        assertEquals(1, countRows("magnet_provider_runs"));
        assertEquals(1, countRows("magnet_links"));
    }

    @Test
    void marksJobPartialSuccessWhenAtLeastOneProviderRunSucceeds() {
        UUID unifiedVideoId = UUID.randomUUID();
        UUID successProviderId = UUID.randomUUID();
        UUID failedProviderId = UUID.randomUUID();
        insertUnifiedVideo(unifiedVideoId, "tt1234567");
        insertProvider(successProviderId, "yts_bz", true);
        insertProvider(failedProviderId, "thepiratebay", true);
        List<MagnetExternalIdQuery> plan = List.of(new MagnetExternalIdQuery("IMDB", "tt1234567"));
        MagnetSearchJobSummary runningJob = repository.createRunningSearchJob(unifiedVideoId, "AUTO_SCHEDULED", plan);
        List<MagnetProviderSummary> providers = repository.listEnabledProviders();
        MagnetProviderSummary successProvider = providers.stream()
                .filter(provider -> provider.code().equals("yts_bz"))
                .findFirst()
                .orElseThrow();
        MagnetProviderSummary failedProvider = providers.stream()
                .filter(provider -> provider.code().equals("thepiratebay"))
                .findFirst()
                .orElseThrow();

        repository.createProviderRun(
                runningJob.id(),
                successProvider,
                plan.getFirst(),
                "SUCCESS",
                "https://movies-api.accel.li/api/v2/list_movies.json?query_term=tt1234567",
                200,
                0,
                0,
                20,
                null);
        repository.createProviderRun(
                runningJob.id(),
                failedProvider,
                plan.getFirst(),
                "FAILED",
                "https://apibay.org/q.php?q=tt1234567",
                null,
                0,
                0,
                10000,
                "GENERIC_MAGNET_TIMEOUT");

        MagnetSearchJobSummary finishedJob = repository.finishSearchJob(
                runningJob.id(),
                List.of("yts_bz", "thepiratebay"),
                0,
                0,
                1);

        assertEquals("PARTIAL_SUCCESS", finishedJob.status());
        assertEquals(0, finishedJob.totalCandidates());
        assertEquals(0, finishedJob.acceptedCount());
        assertEquals(0, finishedJob.rejectedCount());
        assertEquals(2, countRows("magnet_provider_runs"));
    }

    @Test
    void createsSkippedJobWithoutProviderRunOrLink() {
        UUID unifiedVideoId = UUID.randomUUID();
        insertUnifiedVideo(unifiedVideoId, null);

        MagnetSearchJobSummary job = repository.createSkippedSearchJob(
                unifiedVideoId,
                "没有可用于第一阶段搜刮的 IMDb ID",
                List.of(),
                List.of());

        assertEquals("SKIPPED", job.status());
        assertEquals("没有可用于第一阶段搜刮的 IMDb ID", job.skippedReason());
        assertEquals(1, countRows("magnet_search_jobs"));
        assertEquals(0, countRows("magnet_provider_runs"));
        assertEquals(0, countRows("magnet_links"));
    }

    @Test
    void findsAutoSearchCandidatesWithoutApprovedLinksOrRecentJobs() {
        UUID eligible = UUID.randomUUID();
        UUID approved = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        UUID running = UUID.randomUUID();
        UUID empty = UUID.randomUUID();
        UUID providerId = UUID.randomUUID();
        insertUnifiedVideo(eligible, null, "Eligible Movie");
        insertUnifiedVideo(approved, null, "Approved Movie");
        insertUnifiedVideo(recent, null, "Recent Movie");
        insertUnifiedVideo(running, null, "Running Movie");
        insertUnifiedVideo(empty, null, null);
        insertProvider(providerId, "codex_provider", true);
        MagnetProviderSummary provider = repository.listEnabledProviders().getFirst();
        repository.upsertLink(approved, provider, candidate("abcdef1234567890abcdef1234567890abcdef12"));
        repository.createSkippedSearchJob(
                recent,
                "AUTO_SCHEDULED",
                "recent",
                List.of(new MagnetExternalIdQuery("TITLE", "Recent Movie")),
                List.of(provider.code()));
        repository.createRunningSearchJob(
                running,
                "AUTO_SCHEDULED",
                List.of(new MagnetExternalIdQuery("TITLE", "Running Movie")));

        List<UUID> candidates = repository.findAutoSearchCandidates(10, Instant.parse("2026-06-07T00:00:00Z"));

        assertEquals(List.of(eligible), candidates);
    }

    private void createSchema() {
        jdbcTemplate.execute("create domain if not exists jsonb as json");
        jdbcTemplate.execute("""
                create table unified_videos (
                    id uuid primary key,
                    created_at timestamp with time zone not null,
                    updated_at timestamp with time zone not null,
                    title varchar(255),
                    alias_title varchar(255),
                "year" varchar(20),
                    imdb_id varchar(20),
                    tmdb_id varchar(20),
                    douban_id varchar(20),
                    metadata_status varchar(50) not null default 'SYNCED'
                )
                """);
        jdbcTemplate.execute("""
                create table magnet_providers (
                    id uuid primary key,
                    created_at timestamp with time zone not null,
                    updated_at timestamp with time zone not null,
                    version bigint,
                    code varchar(64) not null unique,
                    name varchar(100) not null,
                    provider_type varchar(50) not null,
                    base_url varchar(500) not null,
                    enabled boolean not null,
                    priority integer not null,
                    risk_level varchar(50) not null,
                    supported_external_ids jsonb not null,
                    min_delay_ms integer not null,
                    max_delay_ms integer not null,
                    timeout_ms integer not null,
                    result_limit integer not null,
                    auto_approve_allowed boolean not null,
                    content_policy varchar(500),
                    last_health_check_at timestamp with time zone,
                    last_health_status varchar(50),
                    last_error_message text
                )
                """);
        jdbcTemplate.execute("""
                create table magnet_search_jobs (
                    id uuid primary key,
                    created_at timestamp with time zone not null,
                    updated_at timestamp with time zone not null,
                    version bigint,
                    unified_video_id uuid not null,
                    trigger_type varchar(30) not null,
                    status varchar(30) not null,
                    provider_codes jsonb not null,
                    external_id_plan jsonb not null,
                    started_at timestamp with time zone,
                    finished_at timestamp with time zone,
                    total_candidates integer not null,
                    accepted_count integer not null,
                    rejected_count integer not null,
                    skipped_reason varchar(255),
                    error_message text
                )
                """);
        jdbcTemplate.execute("""
                create table magnet_provider_runs (
                    id uuid primary key,
                    created_at timestamp with time zone not null,
                    updated_at timestamp with time zone not null,
                    version bigint,
                    job_id uuid not null,
                    provider_id uuid,
                    provider_code varchar(64) not null,
                    external_id_type varchar(20) not null,
                    external_id_value varchar(100) not null,
                    status varchar(30) not null,
                    request_url varchar(1000),
                    http_status integer,
                    candidate_count integer not null,
                    accepted_count integer not null,
                    duration_ms bigint,
                    error_message text
                )
                """);
        jdbcTemplate.execute("""
                create table magnet_links (
                    id uuid primary key,
                    created_at timestamp with time zone not null,
                    updated_at timestamp with time zone not null,
                    version bigint,
                    unified_video_id uuid not null,
                    provider_id uuid,
                    provider_code varchar(64) not null,
                    info_hash varchar(64) not null,
                    magnet_uri text not null,
                    title varchar(500) not null,
                    size_bytes bigint,
                    size_label varchar(100),
                    published_at timestamp with time zone,
                    seeders integer,
                    leechers integer,
                    quality varchar(50),
                    resolution varchar(50),
                    matched_external_id_type varchar(20) not null,
                    matched_external_id_value varchar(100) not null,
                    match_score integer not null,
                    status varchar(30) not null,
                    source_url varchar(1000),
                    tags jsonb not null,
                    provider_evidence jsonb not null,
                    first_seen_at timestamp with time zone not null,
                    last_seen_at timestamp with time zone not null,
                    unique (unified_video_id, info_hash)
                )
                """);
    }

    private void insertUnifiedVideo(UUID id, String imdbId) {
        insertUnifiedVideo(id, imdbId, "Codex Fixture");
    }

    private void insertUnifiedVideo(UUID id, String imdbId, String title) {
        jdbcTemplate.update(
                "insert into unified_videos (id, created_at, updated_at, title, \"year\", imdb_id) values (?, ?, ?, ?, ?, ?)",
                id,
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:00Z"),
                title,
                "2026",
                imdbId);
    }

    private void insertProvider(UUID id, String code, boolean autoApproveAllowed) {
        jdbcTemplate.update(
                """
                insert into magnet_providers (
                    id, created_at, updated_at, version, code, name, provider_type, base_url,
                    enabled, priority, risk_level, supported_external_ids, min_delay_ms,
                    max_delay_ms, timeout_ms, result_limit, auto_approve_allowed, content_policy
                ) values (?, ?, ?, 0, ?, ?, ?, ?, true, 10, 'HIGH', cast(? as jsonb), 1000, 3000, 10000, 20, ?, ?)
                """,
                id,
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:00Z"),
                code,
                "Codex Provider",
                "CODEX_PROVIDER",
                "https://example.invalid/api",
                "[\"IMDB\"]",
                autoApproveAllowed,
                "仅用于 dev smoke。");
    }

    private MagnetProviderCandidate candidate(String infoHash) {
        return new MagnetProviderCandidate(
                infoHash,
                "magnet:?xt=urn:btih:" + infoHash,
                "Codex Fixture",
                1024L,
                "1 KB",
                Instant.parse("2026-06-07T00:00:00Z"),
                10,
                1,
                "WEB",
                "1080p",
                "IMDB",
                "tt1234567",
                100,
                "fixture://magnet-provider/codex_provider",
                List.of("dev-safe"),
                Map.of("mode", "fixture"));
    }

    private int countRows(String tableName) {
        Integer count = jdbcTemplate.queryForObject("select count(*) from " + tableName, Integer.class);
        return count == null ? 0 : count;
    }
}

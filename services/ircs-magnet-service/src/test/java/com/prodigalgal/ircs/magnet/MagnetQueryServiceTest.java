package com.prodigalgal.ircs.magnet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.cache.CacheRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class MagnetQueryServiceTest {

    private final JdbcMagnetRepository magnetRepository = org.mockito.Mockito.mock(JdbcMagnetRepository.class);
    private final MagnetProviderSearchRunner providerSearchRunner = org.mockito.Mockito.mock(MagnetProviderSearchRunner.class);
    private final MagnetQueryService service = new MagnetQueryService(
            magnetRepository,
            providerSearchRunner,
            MagnetReadModelCache.disabled(),
            null,
            null);

    @Test
    void listProvidersCachesReadModel() {
        MagnetProviderSummary provider = provider(UUID.randomUUID());
        when(magnetRepository.listProviders()).thenReturn(List.of(provider));
        MagnetQueryService cachedService = cachedService();

        assertEquals(List.of(provider), cachedService.listProviders());
        assertEquals(List.of(provider), cachedService.listProviders());

        verify(magnetRepository, times(1)).listProviders();
    }

    @Test
    void createProviderEvictsCachedProviderReadModel() {
        MagnetProviderSummary before = provider(UUID.randomUUID());
        MagnetProviderSummary created = provider(UUID.randomUUID());
        when(magnetRepository.listProviders()).thenReturn(List.of(before), List.of(before, created));
        when(magnetRepository.createProvider(any())).thenReturn(created);
        MagnetQueryService cachedService = cachedService();

        assertEquals(List.of(before), cachedService.listProviders());
        assertSame(created, cachedService.createProvider(providerRequest(null)));
        assertEquals(List.of(before, created), cachedService.listProviders());

        verify(magnetRepository, times(2)).listProviders();
    }

    @Test
    void approvedLinksCachePerUnifiedVideo() {
        UUID unifiedVideoId = UUID.randomUUID();
        MagnetProviderCandidate candidate = candidate("abcdef1234567890abcdef1234567890abcdef12");
        MagnetLinkSummary link = link(unifiedVideoId, candidate);
        when(magnetRepository.findApprovedLinks(unifiedVideoId)).thenReturn(List.of(link));
        MagnetQueryService cachedService = cachedService();

        assertEquals(List.of(link), cachedService.findApprovedLinks(unifiedVideoId));
        assertEquals(List.of(link), cachedService.findApprovedLinks(unifiedVideoId));

        verify(magnetRepository, times(1)).findApprovedLinks(unifiedVideoId);
    }

    @Test
    void triggerUnifiedSearchEvictsApprovedLinksWhenLinksAreTouched() {
        UUID unifiedVideoId = UUID.randomUUID();
        MagnetProviderSummary provider = provider(UUID.randomUUID());
        MagnetSearchJobSummary runningJob = searchJob(unifiedVideoId, "RUNNING", 0, 0, List.of());
        MagnetSearchJobSummary finishedJob = searchJob(unifiedVideoId, "SUCCESS", 1, 1, List.of("codex_provider"));
        MagnetProviderCandidate oldCandidate = candidate("1111111111111111111111111111111111111111");
        MagnetProviderCandidate freshCandidate = candidate("abcdef1234567890abcdef1234567890abcdef12");
        MagnetLinkSummary oldLink = link(unifiedVideoId, oldCandidate);
        MagnetLinkSummary freshLink = link(unifiedVideoId, freshCandidate);

        when(magnetRepository.findApprovedLinks(unifiedVideoId)).thenReturn(List.of(oldLink), List.of(freshLink));
        when(magnetRepository.findUnifiedVideoSearchTarget(unifiedVideoId))
                .thenReturn(Optional.of(searchTarget(unifiedVideoId, "tt1234567", null)));
        when(magnetRepository.listEnabledProviders()).thenReturn(List.of(provider));
        when(magnetRepository.createRunningSearchJob(eq(unifiedVideoId), eq("ADMIN_MANUAL"), anyList())).thenReturn(runningJob);
        when(providerSearchRunner.search(eq(provider), any(), eq(unifiedVideoId)))
                .thenReturn(new MagnetProviderSearchResult(
                        "fixture://magnet-provider/codex_provider?externalId=tt1234567",
                        200,
                        List.of(freshCandidate)));
        when(magnetRepository.upsertLink(unifiedVideoId, provider, freshCandidate)).thenReturn(freshLink);
        when(magnetRepository.finishSearchJob(runningJob.id(), List.of("codex_provider"), 1, 1, 0))
                .thenReturn(finishedJob);
        MagnetQueryService cachedService = cachedService();

        assertEquals(List.of(oldLink), cachedService.findApprovedLinks(unifiedVideoId));
        cachedService.triggerUnifiedSearch(unifiedVideoId);
        assertEquals(List.of(freshLink), cachedService.findApprovedLinks(unifiedVideoId));

        verify(magnetRepository, times(2)).findApprovedLinks(unifiedVideoId);
    }

    @Test
    void rejectsCreateProviderWithBodyId() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.createProvider(providerRequest(UUID.randomUUID())));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void rejectsUpdateProviderIdMismatch() {
        UUID pathId = UUID.randomUUID();
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.updateProvider(pathId, providerRequest(UUID.randomUUID())));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(magnetRepository, never()).updateProvider(any(), any());
    }

    @Test
    void allowsUpdateProviderWithoutBodyIdLikeV1() {
        UUID pathId = UUID.randomUUID();
        MagnetProviderSummary provider = provider(pathId);
        when(magnetRepository.updateProvider(eq(pathId), any()))
                .thenReturn(Optional.of(provider));

        assertSame(provider, service.updateProvider(pathId, providerRequest(null)));
    }

    @Test
    void runsDevSafeProviderSearchForExistingUnifiedVideo() {
        UUID unifiedVideoId = UUID.randomUUID();
        MagnetProviderSummary provider = provider(UUID.randomUUID());
        MagnetSearchJobSummary runningJob = searchJob(unifiedVideoId, "RUNNING", 0, 0, List.of());
        MagnetSearchJobSummary finishedJob = searchJob(unifiedVideoId, "SUCCESS", 1, 1, List.of("codex_provider"));
        MagnetProviderCandidate candidate = candidate("abcdef1234567890abcdef1234567890abcdef12");
        MagnetLinkSummary link = link(unifiedVideoId, candidate);

        when(magnetRepository.findUnifiedVideoSearchTarget(unifiedVideoId))
                .thenReturn(Optional.of(searchTarget(unifiedVideoId, "tt1234567", null)));
        when(magnetRepository.listEnabledProviders()).thenReturn(List.of(provider));
        when(magnetRepository.createRunningSearchJob(eq(unifiedVideoId), eq("ADMIN_MANUAL"), anyList())).thenReturn(runningJob);
        when(providerSearchRunner.search(eq(provider), any(), eq(unifiedVideoId)))
                .thenReturn(new MagnetProviderSearchResult(
                        "fixture://magnet-provider/codex_provider?externalId=tt1234567",
                        200,
                        List.of(candidate)));
        when(magnetRepository.upsertLink(unifiedVideoId, provider, candidate)).thenReturn(link);
        when(magnetRepository.finishSearchJob(runningJob.id(), List.of("codex_provider"), 1, 1, 0))
                .thenReturn(finishedJob);

        MagnetSearchJobSummary result = service.triggerUnifiedSearch(unifiedVideoId);

        assertEquals("SUCCESS", result.status());
        assertEquals(1, result.totalCandidates());
        assertEquals(1, result.acceptedCount());
        assertEquals(List.of(link), result.links());
        verify(providerSearchRunner).search(eq(provider), any(), eq(unifiedVideoId));
        verify(magnetRepository).createProviderRun(
                eq(runningJob.id()),
                eq(provider),
                any(),
                eq("SUCCESS"),
                eq("fixture://magnet-provider/codex_provider?externalId=tt1234567"),
                eq(200),
                eq(1),
                eq(1),
                anyLong(),
                eq(null));
    }

    @Test
    void skipsSearchWithoutExternalIdOrTitle() {
        UUID unifiedVideoId = UUID.randomUUID();
        MagnetSearchJobSummary skippedJob = searchJob(unifiedVideoId, "SKIPPED", 0, 0, List.of());
        when(magnetRepository.findUnifiedVideoSearchTarget(unifiedVideoId))
                .thenReturn(Optional.of(searchTarget(unifiedVideoId, null, null)));
        when(magnetRepository.createSkippedSearchJob(eq(unifiedVideoId), eq("ADMIN_MANUAL"), any(), anyList(), eq(List.of())))
                .thenReturn(skippedJob);

        assertSame(skippedJob, service.triggerUnifiedSearch(unifiedVideoId));
        verify(providerSearchRunner, never()).search(any(), any(), any());
        verify(magnetRepository, never()).createRunningSearchJob(any(), any(), anyList());
    }

    @Test
    void fallsBackToTitleSearchForYtsProviderWithoutExternalIds() {
        UUID unifiedVideoId = UUID.randomUUID();
        MagnetProviderSummary provider = ytsProvider(UUID.randomUUID());
        MagnetSearchJobSummary runningJob = searchJob(unifiedVideoId, "RUNNING", 0, 0, List.of());
        MagnetSearchJobSummary finishedJob = searchJob(unifiedVideoId, "SUCCESS", 1, 1, List.of("codex_provider"));
        MagnetProviderCandidate candidate = new MagnetProviderCandidate(
                "abcdef1234567890abcdef1234567890abcdef12",
                "magnet:?xt=urn:btih:abcdef1234567890abcdef1234567890abcdef12",
                "Codex Fixture 2026",
                1024L,
                "1 KB",
                Instant.parse("2026-06-07T00:00:00Z"),
                10,
                1,
                "WEB",
                "1080p",
                "TITLE_YEAR",
                "Codex Fixture 2026",
                82,
                "fixture://magnet-provider/codex_provider",
                List.of("dev-safe"),
                Map.of("mode", "fixture"));
        MagnetLinkSummary link = link(unifiedVideoId, candidate);

        when(magnetRepository.findUnifiedVideoSearchTarget(unifiedVideoId))
                .thenReturn(Optional.of(searchTarget(unifiedVideoId, null, "Codex Fixture")));
        when(magnetRepository.listEnabledProviders()).thenReturn(List.of(provider));
        when(magnetRepository.createRunningSearchJob(eq(unifiedVideoId), eq("ADMIN_MANUAL"), anyList())).thenReturn(runningJob);
        when(providerSearchRunner.search(eq(provider), any(), eq(unifiedVideoId)))
                .thenReturn(new MagnetProviderSearchResult("fixture://title-search", 200, List.of(candidate)))
                .thenReturn(new MagnetProviderSearchResult("fixture://title-search-plain", 200, List.of()));
        when(magnetRepository.upsertLink(unifiedVideoId, provider, candidate)).thenReturn(link);
        when(magnetRepository.finishSearchJob(runningJob.id(), List.of("codex_provider"), 1, 1, 0))
                .thenReturn(finishedJob);

        MagnetSearchJobSummary result = service.triggerUnifiedSearch(unifiedVideoId);

        assertEquals("SUCCESS", result.status());
        assertEquals(List.of(link), result.links());
        verify(providerSearchRunner, times(2)).search(eq(provider), any(), eq(unifiedVideoId));
    }

    @Test
    void automaticSearchUsesAutoTriggerType() {
        UUID unifiedVideoId = UUID.randomUUID();
        MagnetProviderSummary provider = provider(UUID.randomUUID());
        MagnetSearchJobSummary runningJob = searchJob(unifiedVideoId, "RUNNING", 0, 0, List.of());
        MagnetSearchJobSummary finishedJob = searchJob(unifiedVideoId, "SUCCESS", 0, 0, List.of("codex_provider"));

        when(magnetRepository.findUnifiedVideoSearchTarget(unifiedVideoId))
                .thenReturn(Optional.of(searchTarget(unifiedVideoId, "tt1234567", null)));
        when(magnetRepository.listEnabledProviders()).thenReturn(List.of(provider));
        when(magnetRepository.createRunningSearchJob(eq(unifiedVideoId), eq("AUTO_SCHEDULED"), anyList()))
                .thenReturn(runningJob);
        when(providerSearchRunner.search(eq(provider), any(), eq(unifiedVideoId)))
                .thenReturn(new MagnetProviderSearchResult("fixture://empty", 200, List.of()));
        when(magnetRepository.finishSearchJob(runningJob.id(), List.of("codex_provider"), 0, 0, 0))
                .thenReturn(finishedJob);

        MagnetSearchJobSummary result = service.triggerAutomaticSearch(unifiedVideoId);

        assertEquals("SUCCESS", result.status());
        verify(magnetRepository).createRunningSearchJob(eq(unifiedVideoId), eq("AUTO_SCHEDULED"), anyList());
    }

    @Test
    void rejectsSearchForMissingUnifiedVideo() {
        UUID unifiedVideoId = UUID.randomUUID();
        when(magnetRepository.findUnifiedVideoSearchTarget(unifiedVideoId)).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.triggerUnifiedSearch(unifiedVideoId));

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        verify(magnetRepository, never()).createRunningSearchJob(any(), any(), anyList());
        verify(providerSearchRunner, never()).search(any(), any(), any());
    }

    private MagnetProviderRequest providerRequest(UUID id) {
        return new MagnetProviderRequest(
                id,
                "codex_provider",
                "Codex Provider",
                "CODEX_PROVIDER",
                "https://example.invalid/api",
                true,
                10,
                "HIGH",
                List.of("IMDB"),
                1000,
                3000,
                10000,
                20,
                true,
                "仅用于 dev smoke。",
                null,
                null,
                null,
                null,
                null);
    }

    private MagnetProviderSummary provider(UUID id) {
        return new MagnetProviderSummary(
                id,
                "codex_provider",
                "Codex Provider",
                "CODEX_PROVIDER",
                "https://example.invalid/api",
                true,
                10,
                "HIGH",
                List.of("IMDB"),
                1000,
                3000,
                10000,
                20,
                true,
                "仅用于 dev smoke。",
                null,
                null,
                null,
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:00Z"));
    }

    private MagnetSearchJobSummary searchJob(
            UUID unifiedVideoId,
            String status,
            int totalCandidates,
            int acceptedCount,
            List<String> providerCodes) {
        return new MagnetSearchJobSummary(
                UUID.randomUUID(),
                unifiedVideoId,
                "ADMIN_MANUAL",
                status,
                providerCodes,
                List.of(Map.of("type", "IMDB", "value", "tt1234567")),
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:01Z"),
                totalCandidates,
                acceptedCount,
                Math.max(0, totalCandidates - acceptedCount),
                "SKIPPED".equals(status) ? "没有可用于搜刮的外部 ID 或标题" : null,
                null,
                List.of(),
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:01Z"));
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

    private MagnetLinkSummary link(UUID unifiedVideoId, MagnetProviderCandidate candidate) {
        return new MagnetLinkSummary(
                UUID.randomUUID(),
                unifiedVideoId,
                "codex_provider",
                candidate.infoHash(),
                candidate.magnetUri(),
                candidate.title(),
                candidate.sizeBytes(),
                candidate.sizeLabel(),
                candidate.publishedAt(),
                candidate.seeders(),
                candidate.leechers(),
                candidate.quality(),
                candidate.resolution(),
                candidate.matchedExternalIdType(),
                candidate.matchedExternalIdValue(),
                candidate.matchScore(),
                "APPROVED",
                candidate.sourceUrl(),
                candidate.tags(),
                Instant.parse("2026-06-07T00:00:00Z"),
                Instant.parse("2026-06-07T00:00:01Z"));
    }

    private MagnetQueryService cachedService() {
        MagnetReadModelCache cache = new MagnetReadModelCache(
                JsonMapper.builder().findAndAddModules().build(),
                new CacheRegistry(),
                null,
                null,
                true,
                Duration.ofMinutes(5),
                Duration.ofSeconds(60));
        return new MagnetQueryService(magnetRepository, providerSearchRunner, cache, null, null);
    }

    private MagnetUnifiedVideoSearchTarget searchTarget(UUID id, String imdbId, String title) {
        return new MagnetUnifiedVideoSearchTarget(
                id,
                title,
                null,
                title == null ? null : "2026",
                imdbId,
                null,
                null,
                "SYNCED");
    }

    private MagnetProviderSummary ytsProvider(UUID id) {
        MagnetProviderSummary base = provider(id);
        return new MagnetProviderSummary(
                base.id(),
                base.code(),
                base.name(),
                "YTS_BZ",
                base.baseUrl(),
                base.enabled(),
                base.priority(),
                base.riskLevel(),
                base.supportedExternalIds(),
                base.minDelayMs(),
                base.maxDelayMs(),
                base.timeoutMs(),
                base.resultLimit(),
                base.autoApproveAllowed(),
                base.contentPolicy(),
                base.lastHealthCheckAt(),
                base.lastHealthStatus(),
                base.lastErrorMessage(),
                base.createdAt(),
                base.updatedAt());
    }
}

package com.prodigalgal.ircs.ops.dashboard.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.ops.dashboard.domain.DashboardReadModelSnapshot;
import com.prodigalgal.ircs.ops.dashboard.dto.DashboardDistributionsResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.DashboardEfficiencyResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.DashboardStatsResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.TaskRuntimeOverviewResponse;
import com.prodigalgal.ircs.ops.dashboard.domain.DashboardStatsSnapshot;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.AggregationOpsStatsClient;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.DashboardSearchIndexStatsClient;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.DashboardSearchIndexStatsClient.SearchIndexCounts;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.DashboardReadModelSnapshotRepository;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.DashboardStatsSnapshotRepository;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.JdbcDashboardRepository;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.SearchOpsStatsClient;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class DashboardQueryServiceTest {

    private final JdbcDashboardRepository repository = org.mockito.Mockito.mock(JdbcDashboardRepository.class);
    private final SystemMetricsService metricsService = org.mockito.Mockito.mock(SystemMetricsService.class);
    private final TaskRuntimeOverviewService taskRuntimeOverviewService =
            org.mockito.Mockito.mock(TaskRuntimeOverviewService.class);
    private final SearchOpsStatsClient searchOpsStatsClient = org.mockito.Mockito.mock(SearchOpsStatsClient.class);
    private final AggregationOpsStatsClient aggregationOpsStatsClient =
            org.mockito.Mockito.mock(AggregationOpsStatsClient.class);
    private final DashboardSearchIndexStatsClient searchIndexStatsClient =
            org.mockito.Mockito.mock(DashboardSearchIndexStatsClient.class);
    private final DashboardStatsSnapshotRepository statsSnapshotRepository =
            org.mockito.Mockito.mock(DashboardStatsSnapshotRepository.class);
    private final DashboardReadModelSnapshotRepository readModelSnapshotRepository =
            org.mockito.Mockito.mock(DashboardReadModelSnapshotRepository.class);
    private final DashboardQueryService service =
            new DashboardQueryService(
                    repository,
                    metricsService,
                    taskRuntimeOverviewService,
                    searchOpsStatsClient,
                    aggregationOpsStatsClient,
                    searchIndexStatsClient,
                    statsSnapshotRepository,
                    readModelSnapshotRepository,
                    JsonMapper.builder().findAndAddModules().build());

    @Test
    void statisticsUsesShortLivedReadCacheUntilRefresh() {
        DashboardStatsResponse first = new DashboardStatsResponse(1, 0, 2, 0, 3, 0, 0, 0, 0, 0, 0, 0);
        DashboardStatsResponse refreshed = new DashboardStatsResponse(2, 0, 3, 0, 4, 0, 0, 0, 0, 0, 0, 0);
        when(repository.loadStats()).thenReturn(first, refreshed);
        when(searchIndexStatsClient.currentCounts())
                .thenReturn(new SearchIndexCounts(OptionalLong.empty(), OptionalLong.empty()));

        assertEquals(first.withSearchCounts(1, 2), service.getStatistics());
        assertEquals(first.withSearchCounts(1, 2), service.getStatistics());
        verify(repository, times(1)).loadStats();

        service.refresh(14);

        assertEquals(refreshed.withSearchCounts(2, 3), service.getStatistics());
        verify(repository, times(2)).loadStats();
    }

    @Test
    void statisticsUseSearchIndexCountsWhenAvailable() {
        DashboardStatsResponse stats = new DashboardStatsResponse(1, 0, 2, 0, 3, 0, 0, 0, 0, 0, 0, 0);
        when(repository.loadStats()).thenReturn(stats);
        when(searchIndexStatsClient.currentCounts())
                .thenReturn(new SearchIndexCounts(OptionalLong.of(10L), OptionalLong.of(20L)));

        DashboardStatsResponse response = service.getStatistics();

        assertEquals(1, response.rawCountDb());
        assertEquals(10, response.rawCountEs());
        assertEquals(2, response.unifiedCountDb());
        assertEquals(20, response.unifiedCountEs());
    }

    @Test
    void statisticsUsesMaterializedSnapshotBeforeLiveQuery() {
        Instant generatedAt = Instant.parse("2026-07-01T00:00:00Z");
        DashboardStatsResponse snapshotStats =
                new DashboardStatsResponse(11, 12, 21, 22, 31, 1, 2, 3, 4, 5, 6, 7);
        DashboardStatsSnapshot snapshot = new DashboardStatsSnapshot(
                snapshotStats,
                generatedAt,
                generatedAt.plusSeconds(6),
                generatedAt.plusSeconds(66),
                "database");
        when(statsSnapshotRepository.findUsable()).thenReturn(Optional.of(snapshot));

        DashboardStatsResponse response = service.getStatistics();

        assertEquals(snapshotStats, response);
        verify(repository, times(0)).loadStats();
        verify(searchIndexStatsClient, times(0)).currentCounts();
    }

    @Test
    void statisticsFallBackToDatabaseCountsWhenSearchIndexUnavailable() {
        DashboardStatsResponse stats = new DashboardStatsResponse(7, 0, 8, 0, 3, 0, 0, 0, 0, 0, 0, 0);
        when(repository.loadStats()).thenReturn(stats);
        when(searchIndexStatsClient.currentCounts()).thenThrow(new IllegalStateException("es unavailable"));

        DashboardStatsResponse response = service.getStatistics();

        assertEquals(7, response.rawCountEs());
        assertEquals(8, response.unifiedCountEs());
    }

    @Test
    void trendUsesKeyedReadCachePerBoundedDayRange() {
        when(repository.weeklyTrend(14)).thenReturn(List.of());
        when(repository.weeklyTrend(30)).thenReturn(List.of());

        service.getTrend(14);
        service.getTrend(14);
        service.getTrend(30);

        verify(repository, times(1)).weeklyTrend(14);
        verify(repository, times(1)).weeklyTrend(30);
    }

    @Test
    void analysisBlocksUseIndependentReadCaches() {
        when(repository.weeklyTrend(14)).thenReturn(List.of());
        when(repository.categoryDistribution()).thenReturn(List.of());
        when(repository.sourceDistribution()).thenReturn(List.of());
        when(repository.enrichmentStatusDistribution()).thenReturn(List.of());
        when(repository.idCoverage()).thenReturn(Map.of("Both", 1L));
        when(repository.sourceEfficiency()).thenReturn(List.of());
        when(repository.categoryEfficiency()).thenReturn(List.of());

        service.getTrend(14);
        service.getTrend(14);
        service.getDistributions();
        service.getDistributions();
        service.getCoverage();
        service.getCoverage();
        service.getEfficiency();
        service.getEfficiency();

        verify(repository, times(1)).weeklyTrend(14);
        verify(repository, times(1)).categoryDistribution();
        verify(repository, times(1)).sourceDistribution();
        verify(repository, times(1)).enrichmentStatusDistribution();
        verify(repository, times(1)).idCoverage();
        verify(repository, times(1)).sourceEfficiency();
        verify(repository, times(1)).categoryEfficiency();
    }

    @Test
    void distributionsUseMaterializedSnapshotBeforeLiveQuery() {
        DashboardDistributionsResponse snapshotPayload =
                new DashboardDistributionsResponse(List.of(), List.of(), List.of());
        when(readModelSnapshotRepository.findUsable(eq(DashboardReadModelSnapshotRepository.DISTRIBUTIONS_KEY), any()))
                .thenReturn(Optional.of(new DashboardReadModelSnapshot<>(
                        snapshotPayload,
                        Instant.now(),
                        Instant.now().plusSeconds(30),
                        Instant.now().plusSeconds(300),
                        "database")));

        DashboardDistributionsResponse response = service.getDistributions();

        assertEquals(snapshotPayload, response);
        verify(repository, times(0)).categoryDistribution();
        verify(repository, times(0)).sourceDistribution();
        verify(repository, times(0)).enrichmentStatusDistribution();
    }

    @Test
    void sourceQualityUsesMaterializedSnapshotBeforeLiveQuery() {
        when(readModelSnapshotRepository.findUsable(eq(DashboardReadModelSnapshotRepository.SOURCE_QUALITY_KEY), any()))
                .thenReturn(Optional.of(new DashboardReadModelSnapshot<>(
                        List.of(),
                        Instant.now(),
                        Instant.now().plusSeconds(30),
                        Instant.now().plusSeconds(300),
                        "database")));

        List<?> response = service.getSourceQuality();

        assertTrue(response.isEmpty());
        verify(repository, times(0)).sourceQuality();
    }

    @Test
    void splitResponsesNormalizeNullPayloads() {
        when(repository.categoryDistribution()).thenReturn(null);
        when(repository.sourceDistribution()).thenReturn(null);
        when(repository.enrichmentStatusDistribution()).thenReturn(null);
        when(repository.sourceEfficiency()).thenReturn(null);
        when(repository.categoryEfficiency()).thenReturn(null);

        DashboardDistributionsResponse distributions = service.getDistributions();
        DashboardEfficiencyResponse efficiency = service.getEfficiency();

        assertTrue(distributions.categoryDistribution().isEmpty());
        assertTrue(distributions.sourceDistribution().isEmpty());
        assertTrue(distributions.enrichmentStatusDistribution().isEmpty());
        assertTrue(efficiency.sourceEfficiency().isEmpty());
        assertTrue(efficiency.categoryEfficiency().isEmpty());
    }

    @Test
    void warmAnalysisBlocksWarmsSplitCachesUntilDeadline() {
        DashboardStatsResponse stats = new DashboardStatsResponse(1, 0, 2, 0, 3, 0, 0, 0, 0, 0, 0, 0);
        TaskRuntimeOverviewResponse overview = new TaskRuntimeOverviewResponse(
                java.time.Instant.parse("2026-06-13T00:00:00Z"),
                25,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                Map.of(),
                List.of());
        when(repository.loadStats()).thenReturn(stats);
        when(searchIndexStatsClient.currentCounts())
                .thenReturn(new SearchIndexCounts(OptionalLong.empty(), OptionalLong.empty()));
        when(repository.weeklyTrend(14)).thenReturn(List.of());
        when(repository.categoryDistribution()).thenReturn(List.of());
        when(repository.sourceDistribution()).thenReturn(List.of());
        when(repository.enrichmentStatusDistribution()).thenReturn(List.of());
        when(repository.idCoverage()).thenReturn(Map.of());
        when(repository.sourceEfficiency()).thenReturn(List.of());
        when(repository.categoryEfficiency()).thenReturn(List.of());
        when(taskRuntimeOverviewService.currentOverview(25)).thenReturn(overview);
        when(searchOpsStatsClient.currentStats()).thenReturn(Map.of("available", true));
        when(aggregationOpsStatsClient.currentStats()).thenReturn(Map.of("available", true));

        int warmed = service.warmAnalysisBlocks(14, 25, Instant.now().plusSeconds(5));

        assertEquals(9, warmed);
        verify(repository).loadStats();
        verify(statsSnapshotRepository).save(eq(stats.withSearchCounts(1, 2)), any(Instant.class));
        verify(repository).weeklyTrend(14);
        verify(repository).categoryDistribution();
        verify(repository).sourceDistribution();
        verify(repository).enrichmentStatusDistribution();
        verify(repository).idCoverage();
        verify(repository).sourceEfficiency();
        verify(repository).categoryEfficiency();
        verify(metricsService).currentMetrics();
        verify(taskRuntimeOverviewService).currentOverview(25);
    }

    @Test
    void warmAnalysisBlocksReusesFreshStatsSnapshotWithoutLiveRefresh() {
        Instant generatedAt = Instant.now();
        DashboardStatsResponse snapshotStats =
                new DashboardStatsResponse(11, 12, 21, 22, 31, 1, 2, 3, 4, 5, 6, 7);
        when(statsSnapshotRepository.findUsable()).thenReturn(Optional.of(new DashboardStatsSnapshot(
                snapshotStats,
                generatedAt,
                generatedAt.plusSeconds(60),
                generatedAt.plusSeconds(600),
                "database")));

        int warmed = service.warmAnalysisBlocks(14, 25, Instant.now().plusSeconds(5));

        assertEquals(9, warmed);
        assertEquals(snapshotStats, service.getStatistics());
        verify(repository, times(0)).loadStats();
        verify(searchIndexStatsClient, times(0)).currentCounts();
        verify(statsSnapshotRepository, times(0)).save(any(DashboardStatsResponse.class), any(Instant.class));
    }

    @Test
    void warmAnalysisBlocksStopsWhenDeadlineAlreadyExpired() {
        int warmed = service.warmAnalysisBlocks(14, 25, Instant.now().minusSeconds(1));

        assertEquals(0, warmed);
        verify(repository, times(0)).loadStats();
    }

    @Test
    void refreshDelegatesToRateMetricsRefresh() {
        service.refresh(14);

        verify(metricsService).refreshRateMetrics();
    }

    @Test
    void refreshClearsDashboardAndSourceQualityCaches() {
        when(repository.weeklyTrend(14)).thenReturn(List.of());
        when(repository.sourceQuality()).thenReturn(List.of());
        when(repository.idCoverage()).thenReturn(Map.of());

        service.getTrend(14);
        service.getSourceQuality();
        service.getCoverage();

        service.refresh(14);
        service.getTrend(14);
        service.getSourceQuality();
        service.getCoverage();

        verify(repository, times(2)).weeklyTrend(14);
        verify(repository, times(2)).sourceQuality();
        verify(repository, times(2)).idCoverage();
    }

    @Test
    void taskRuntimeOverviewDelegatesToOverviewService() {
        TaskRuntimeOverviewResponse response = new TaskRuntimeOverviewResponse(
                java.time.Instant.parse("2026-06-13T00:00:00Z"),
                50,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                Map.of(),
                Map.of(),
                List.of());
        when(taskRuntimeOverviewService.currentOverview(25)).thenReturn(response);

        assertEquals(response, service.getTaskRuntimeOverview(25));
        verify(taskRuntimeOverviewService).currentOverview(25);
    }

    @Test
    void opsStatsFallBackToEmptyMapWhenClientFails() {
        when(searchOpsStatsClient.currentStats()).thenThrow(new IllegalStateException("search unavailable"));
        when(aggregationOpsStatsClient.currentStats()).thenThrow(new IllegalStateException("aggregation unavailable"));

        assertEquals(
                Map.of("available", false, "unavailableReason", "SEARCH_OPS_UNAVAILABLE"),
                service.getSearchOpsStats());
        assertEquals(
                Map.of("available", false, "unavailableReason", "AGGREGATION_OPS_UNAVAILABLE"),
                service.getAggregationOpsStats());
    }
}

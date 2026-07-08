package com.prodigalgal.ircs.ops.dashboard.application;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.ops.dashboard.dto.DashboardDistributionsResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.DashboardEfficiencyResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.DashboardStatsResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.SourceQualityResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.SystemMetricsResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.TaskRuntimeOverviewResponse;
import com.prodigalgal.ircs.ops.dashboard.dto.ChartDataPoint;
import com.prodigalgal.ircs.ops.dashboard.domain.DashboardReadModelSnapshot;
import com.prodigalgal.ircs.ops.dashboard.domain.DashboardStatsSnapshot;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.AggregationOpsStatsClient;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.DashboardSearchIndexStatsClient;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.DashboardReadModelSnapshotRepository;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.DashboardStatsSnapshotRepository;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.JdbcDashboardRepository;
import com.prodigalgal.ircs.ops.dashboard.infrastructure.SearchOpsStatsClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DashboardQueryService {

    private static final Duration STATS_CACHE_TTL = Duration.ofSeconds(30);
    private static final Duration ANALYSIS_BLOCK_CACHE_TTL = Duration.ofMinutes(3);
    private static final Duration SOURCE_QUALITY_CACHE_TTL = Duration.ofSeconds(60);

    private final JdbcDashboardRepository dashboardRepository;
    private final SystemMetricsService systemMetricsService;
    private final TaskRuntimeOverviewService taskRuntimeOverviewService;
    private final SearchOpsStatsClient searchOpsStatsClient;
    private final AggregationOpsStatsClient aggregationOpsStatsClient;
    private final DashboardSearchIndexStatsClient searchIndexStatsClient;
    private final DashboardStatsSnapshotRepository statsSnapshotRepository;
    private final DashboardReadModelSnapshotRepository readModelSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final DashboardReadCache<DashboardStatsResponse> statsCache = new DashboardReadCache<>(STATS_CACHE_TTL);
    private final DashboardKeyedReadCache<Integer, List<ChartDataPoint>> trendCache =
            new DashboardKeyedReadCache<>(ANALYSIS_BLOCK_CACHE_TTL);
    private final DashboardReadCache<DashboardDistributionsResponse> distributionsCache =
            new DashboardReadCache<>(ANALYSIS_BLOCK_CACHE_TTL);
    private final DashboardReadCache<Map<String, Long>> coverageCache =
            new DashboardReadCache<>(ANALYSIS_BLOCK_CACHE_TTL);
    private final DashboardReadCache<DashboardEfficiencyResponse> efficiencyCache =
            new DashboardReadCache<>(ANALYSIS_BLOCK_CACHE_TTL);
    private final DashboardReadCache<List<SourceQualityResponse>> sourceQualityCache =
            new DashboardReadCache<>(SOURCE_QUALITY_CACHE_TTL);

    public DashboardStatsResponse getStatistics() {
        return statsCache.get(this::loadStatistics);
    }

    private DashboardStatsResponse loadStatistics() {
        return usableStatsSnapshot()
                .map(DashboardStatsSnapshot::stats)
                .orElseGet(this::refreshStatisticsSnapshot);
    }

    private DashboardStatsResponse refreshStatisticsSnapshot() {
        DashboardStatsResponse stats = loadLiveStatistics();
        statsSnapshotRepository.save(stats, Instant.now());
        statsCache.put(stats);
        return stats;
    }

    private DashboardStatsResponse loadLiveStatistics() {
        DashboardStatsResponse dbStats = dashboardRepository.loadStats();
        try {
            DashboardSearchIndexStatsClient.SearchIndexCounts indexCounts = searchIndexStatsClient.currentCounts();
            return dbStats.withSearchCounts(
                    indexCounts.rawCount().orElse(dbStats.rawCountDb()),
                    indexCounts.unifiedCount().orElse(dbStats.unifiedCountDb()));
        } catch (RuntimeException ignored) {
            return dbStats.withSearchCounts(dbStats.rawCountDb(), dbStats.unifiedCountDb());
        }
    }

    public List<ChartDataPoint> getTrend(int days) {
        int boundedDays = Math.max(1, Math.min(days, 90));
        return trendCache.get(boundedDays, () -> safeList(() -> dashboardRepository.weeklyTrend(boundedDays)));
    }

    public DashboardDistributionsResponse getDistributions() {
        return distributionsCache.get(this::loadDistributions);
    }

    private DashboardDistributionsResponse loadDistributions() {
        Optional<DashboardReadModelSnapshot<DashboardDistributionsResponse>> snapshot = usableReadModelSnapshot(
                DashboardReadModelSnapshotRepository.DISTRIBUTIONS_KEY,
                objectMapper.constructType(DashboardDistributionsResponse.class));
        return snapshot.map(DashboardReadModelSnapshot::payload)
                .orElseGet(this::refreshDistributionsSnapshot);
    }

    private DashboardDistributionsResponse refreshDistributionsSnapshot() {
        DashboardDistributionsResponse response = new DashboardDistributionsResponse(
                safeList(dashboardRepository::categoryDistribution),
                safeList(dashboardRepository::sourceDistribution),
                safeList(dashboardRepository::enrichmentStatusDistribution));
        readModelSnapshotRepository.save(
                DashboardReadModelSnapshotRepository.DISTRIBUTIONS_KEY,
                response,
                Instant.now());
        return response;
    }

    public Map<String, Long> getCoverage() {
        return coverageCache.get(() -> safeMap(dashboardRepository::idCoverage));
    }

    public DashboardEfficiencyResponse getEfficiency() {
        return efficiencyCache.get(() -> new DashboardEfficiencyResponse(
                safeList(dashboardRepository::sourceEfficiency),
                safeList(dashboardRepository::categoryEfficiency)));
    }

    public SystemMetricsResponse getMetrics() {
        return systemMetricsService.currentMetrics();
    }

    public TaskRuntimeOverviewResponse getTaskRuntimeOverview(int limit) {
        return taskRuntimeOverviewService.currentOverview(limit);
    }

    public Map<String, Object> getSearchOpsStats() {
        return safeOpsMap(searchOpsStatsClient::currentStats, "SEARCH_OPS_UNAVAILABLE");
    }

    public Map<String, Object> getAggregationOpsStats() {
        return safeOpsMap(aggregationOpsStatsClient::currentStats, "AGGREGATION_OPS_UNAVAILABLE");
    }

    public List<SourceQualityResponse> getSourceQuality() {
        return sourceQualityCache.get(this::loadSourceQuality);
    }

    private List<SourceQualityResponse> loadSourceQuality() {
        Optional<DashboardReadModelSnapshot<List<SourceQualityResponse>>> snapshot = usableReadModelSnapshot(
                DashboardReadModelSnapshotRepository.SOURCE_QUALITY_KEY,
                sourceQualityListType());
        return snapshot.map(DashboardReadModelSnapshot::payload)
                .orElseGet(this::refreshSourceQualitySnapshot);
    }

    private List<SourceQualityResponse> refreshSourceQualitySnapshot() {
        List<SourceQualityResponse> response = safeList(dashboardRepository::sourceQuality);
        readModelSnapshotRepository.save(
                DashboardReadModelSnapshotRepository.SOURCE_QUALITY_KEY,
                response,
                Instant.now());
        return response;
    }

    public int warmAnalysisBlocks(int days, int taskRuntimeLimit, Instant deadline) {
        int warmed = 0;
        warmed += warmBlock(deadline, this::warmStatisticsSnapshot);
        warmed += warmBlock(deadline, () -> getTrend(days));
        warmed += warmBlock(deadline, this::getDistributions);
        warmed += warmBlock(deadline, this::getCoverage);
        warmed += warmBlock(deadline, this::getEfficiency);
        warmed += warmBlock(deadline, this::getMetrics);
        warmed += warmBlock(deadline, () -> getTaskRuntimeOverview(taskRuntimeLimit));
        warmed += warmBlock(deadline, this::getSearchOpsStats);
        warmed += warmBlock(deadline, this::getAggregationOpsStats);
        return warmed;
    }

    public void refresh(int days) {
        statsCache.clear();
        statsSnapshotRepository.invalidate();
        readModelSnapshotRepository.invalidate(DashboardReadModelSnapshotRepository.DISTRIBUTIONS_KEY);
        readModelSnapshotRepository.invalidate(DashboardReadModelSnapshotRepository.SOURCE_QUALITY_KEY);
        trendCache.clear();
        distributionsCache.clear();
        coverageCache.clear();
        efficiencyCache.clear();
        sourceQualityCache.clear();
        taskRuntimeOverviewService.clearCache();
        systemMetricsService.refreshRateMetrics();
    }

    private DashboardStatsResponse warmStatisticsSnapshot() {
        Instant now = Instant.now();
        Optional<DashboardStatsSnapshot> snapshot = usableStatsSnapshot();
        if (snapshot.isPresent() && snapshot.get().freshAt(now)) {
            DashboardStatsResponse stats = snapshot.get().stats();
            statsCache.put(stats);
            return stats;
        }
        return refreshStatisticsSnapshot();
    }

    private Optional<DashboardStatsSnapshot> usableStatsSnapshot() {
        Optional<DashboardStatsSnapshot> snapshot = statsSnapshotRepository.findUsable();
        return snapshot == null ? Optional.empty() : snapshot;
    }

    private <T> Optional<DashboardReadModelSnapshot<T>> usableReadModelSnapshot(String key, JavaType payloadType) {
        Optional<DashboardReadModelSnapshot<T>> snapshot = readModelSnapshotRepository.findUsable(key, payloadType);
        return snapshot == null ? Optional.empty() : snapshot;
    }

    private JavaType sourceQualityListType() {
        return objectMapper.getTypeFactory().constructCollectionType(List.class, SourceQualityResponse.class);
    }

    private <T> List<T> safeList(Supplier<List<T>> supplier) {
        try {
            List<T> values = supplier.get();
            return values == null ? List.of() : values;
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private <T> Map<String, T> safeMap(Supplier<Map<String, T>> supplier) {
        try {
            Map<String, T> values = supplier.get();
            return values == null ? Map.of() : values;
        } catch (RuntimeException ignored) {
            return Map.of();
        }
    }

    private Map<String, Object> safeOpsMap(Supplier<Map<String, Object>> supplier, String unavailableReason) {
        try {
            Map<String, Object> values = supplier.get();
            return values == null ? DashboardFallbacks.unavailableOps(unavailableReason) : values;
        } catch (RuntimeException ignored) {
            return DashboardFallbacks.unavailableOps(unavailableReason);
        }
    }

    private int warmBlock(Instant deadline, Supplier<?> supplier) {
        if (deadline != null && !Instant.now().isBefore(deadline)) {
            return 0;
        }
        try {
            supplier.get();
            return 1;
        } catch (RuntimeException ignored) {
            return 0;
        }
    }

    private static final class DashboardReadCache<T> {

        private final Duration ttl;
        private volatile CachedValue<T> value;

        private DashboardReadCache(Duration ttl) {
            this.ttl = ttl;
        }

        T get(Supplier<T> loader) {
            Instant now = Instant.now();
            CachedValue<T> current = value;
            if (current != null && current.isFresh(now)) {
                return current.value();
            }
            synchronized (this) {
                now = Instant.now();
                current = value;
                if (current != null && current.isFresh(now)) {
                    return current.value();
                }
                T loaded = loader.get();
                value = new CachedValue<>(loaded, now.plus(ttl));
                return loaded;
            }
        }

        void put(T loaded) {
            value = new CachedValue<>(loaded, Instant.now().plus(ttl));
        }

        void clear() {
            value = null;
        }
    }

    private static final class DashboardKeyedReadCache<K, T> {

        private final Duration ttl;
        private final Map<K, CachedValue<T>> values = new ConcurrentHashMap<>();

        private DashboardKeyedReadCache(Duration ttl) {
            this.ttl = ttl;
        }

        T get(K key, Supplier<T> loader) {
            Instant now = Instant.now();
            CachedValue<T> current = values.get(key);
            if (current != null && current.isFresh(now)) {
                return current.value();
            }
            synchronized (this) {
                now = Instant.now();
                current = values.get(key);
                if (current != null && current.isFresh(now)) {
                    return current.value();
                }
                T loaded = loader.get();
                values.put(key, new CachedValue<>(loaded, now.plus(ttl)));
                return loaded;
            }
        }

        void clear() {
            values.clear();
        }
    }

    private record CachedValue<T>(T value, Instant expiresAt) {

        boolean isFresh(Instant now) {
            return now.isBefore(expiresAt);
        }
    }
}

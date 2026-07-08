package com.prodigalgal.ircs.ops.dashboard.dto;

import java.util.List;
import java.util.Map;

public record SystemMetricsResponse(
        RateMetricResponse pageDiscoveryRate,
        RateMetricResponse detailCollectionRate,
        RateMetricResponse normalizationRate,
        RateMetricResponse metadataRate,
        int ingestQueueDepth,
        int normalizeQueueDepth,
        int enrichQueueDepth,
        int downloadQueueDepth,
        List<QueueGroupResponse> queueGroups,
        long totalQueueBacklog,
        RedisMetricResponse redisMetric,
        long freeMemoryBytes,
        long freeDiskSpaceBytes,
        boolean r2Healthy,
        int activeThreads,
        Map<String, Object> history
) {
}

package com.prodigalgal.ircs.ops.dashboard.dto;

public record RedisMetricResponse(
        long usedMemoryBytes,
        int connectedClients,
        long opsPerSec,
        String version
) {
}

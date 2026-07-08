package com.prodigalgal.ircs.ops.traffic.dto;

public record TrafficSlotResponse(
        String key,
        String label,
        String business,
        String scope,
        String target,
        String egressIdentity,
        long waitingTasks,
        long nextAvailableInMs,
        double congestionRate,
        boolean isBlocked,
        String limiterType,
        Long remainingPermits,
        Long capacity,
        String lastResult,
        String lastObservedAt,
        long totalRequests,
        long allowedCount,
        long waitingCount,
        long rejectedCount,
        long errorCount) {
}

package com.prodigalgal.ircs.contracts.trend;

import java.util.UUID;

public record TrendDiscoveryScheduledTask(
        UUID taskId,
        UUID dataSourceId,
        String dataSourceName,
        String keyword,
        String status) {
}

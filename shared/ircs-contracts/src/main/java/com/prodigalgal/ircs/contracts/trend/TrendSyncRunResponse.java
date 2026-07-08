package com.prodigalgal.ircs.contracts.trend;

import java.util.List;

public record TrendSyncRunResponse(
        String taskName,
        long providerCount,
        long fetchedItems,
        TrendSyncApplyResponse applyResult,
        List<String> providerErrors,
        TrendDiscoveryScheduleResponse discoveryResult) {

    public TrendSyncRunResponse(
            String taskName,
            long providerCount,
            long fetchedItems,
            TrendSyncApplyResponse applyResult,
            List<String> providerErrors) {
        this(taskName, providerCount, fetchedItems, applyResult, providerErrors, null);
    }

    public TrendSyncRunResponse {
        providerErrors = providerErrors == null ? List.of() : List.copyOf(providerErrors);
    }
}

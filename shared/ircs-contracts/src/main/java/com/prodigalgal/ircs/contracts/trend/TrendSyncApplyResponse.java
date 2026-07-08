package com.prodigalgal.ircs.contracts.trend;

import java.util.List;
import java.util.UUID;

public record TrendSyncApplyResponse(
        String taskName,
        long candidates,
        long updatedByExternalId,
        long updatedByTitleMatch,
        long createdGhosts,
        long skippedDuplicates,
        List<UUID> updatedUnifiedVideoIds,
        List<UUID> createdUnifiedVideoIds,
        List<String> discoveryKeywords) {

    public TrendSyncApplyResponse {
        updatedUnifiedVideoIds = updatedUnifiedVideoIds == null ? List.of() : List.copyOf(updatedUnifiedVideoIds);
        createdUnifiedVideoIds = createdUnifiedVideoIds == null ? List.of() : List.copyOf(createdUnifiedVideoIds);
        discoveryKeywords = discoveryKeywords == null ? List.of() : List.copyOf(discoveryKeywords);
    }
}

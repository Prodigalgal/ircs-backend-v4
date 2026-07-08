package com.prodigalgal.ircs.contracts.search;

import java.util.List;
import java.util.UUID;

public record SearchSyncTaskBatchEnqueueRequest(
        List<UUID> entityIds,
        SearchEntityType entityType,
        SyncOperation operation,
        String sourceService,
        String correlationId) {
}

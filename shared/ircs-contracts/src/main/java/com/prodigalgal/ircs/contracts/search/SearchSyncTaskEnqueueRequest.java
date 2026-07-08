package com.prodigalgal.ircs.contracts.search;

import java.util.UUID;

public record SearchSyncTaskEnqueueRequest(
        UUID entityId,
        SearchEntityType entityType,
        SyncOperation operation,
        String sourceService,
        String correlationId) {
}

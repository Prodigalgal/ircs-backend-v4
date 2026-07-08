package com.prodigalgal.ircs.common.search;

import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SearchSyncMessage;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import java.util.UUID;

public record SearchSyncWorkPayload(
        UUID entityId,
        SearchEntityType entityType,
        SyncOperation operation,
        String sourceService,
        String correlationId) {

    public SearchSyncMessage toMessage() {
        return new SearchSyncMessage(entityId, entityType, operation);
    }
}

package com.prodigalgal.ircs.contracts.search;

public record SearchIndexMaintenanceResponse(
        SearchEntityType entityType,
        String operation,
        int deletedSyncTasks,
        boolean recreated
) {
}

package com.prodigalgal.ircs.ops.maintenance.application;

import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;

public interface MaintenanceAggregationClient {

    MaintenanceRunResult recalculateDirtyUnified(String correlationId);

    MaintenanceRunResult enqueuePendingRawWork(String correlationId);

    MaintenanceRunResult backfillUnifiedCovers(String correlationId);

    MaintenanceRunResult backfillUnifiedAdultAssessments(String correlationId);

    MaintenanceRunResult prepareAggregationReset(String correlationId);

    MaintenanceRunResult markAggregationResetRawPending(String correlationId);
}

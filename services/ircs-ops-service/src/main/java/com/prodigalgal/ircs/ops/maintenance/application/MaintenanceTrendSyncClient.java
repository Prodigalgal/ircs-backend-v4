package com.prodigalgal.ircs.ops.maintenance.application;

import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;

public interface MaintenanceTrendSyncClient {

    MaintenanceRunResult syncTrends(String correlationId);
}

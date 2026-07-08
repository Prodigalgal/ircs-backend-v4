package com.prodigalgal.ircs.ops.maintenance.application;

import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;

public interface MaintenanceNormalizationClient {

    MaintenanceRunResult resetAllNormalization(String correlationId);
}

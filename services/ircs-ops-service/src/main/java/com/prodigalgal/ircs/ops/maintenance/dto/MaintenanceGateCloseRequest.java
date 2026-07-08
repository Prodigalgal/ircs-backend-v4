package com.prodigalgal.ircs.ops.maintenance.dto;

import jakarta.validation.constraints.Size;

public record MaintenanceGateCloseRequest(@Size(max = 500) String reason) {
}

package com.prodigalgal.ircs.contracts.maintenance;

import java.util.Locale;

public enum MaintenanceGateStatus {
    ACTIVE,
    CLOSED;

    public static MaintenanceGateStatus parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("maintenance gate status must not be blank");
        }
        return MaintenanceGateStatus.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}

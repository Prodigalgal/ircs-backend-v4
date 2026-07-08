package com.prodigalgal.ircs.common.maintenance;

import java.util.Locale;

public enum MaintenanceGateCheckKind {
    WRITE,
    CONSUMER;

    public static MaintenanceGateCheckKind parse(String value) {
        if (value == null || value.isBlank()) {
            return WRITE;
        }
        return MaintenanceGateCheckKind.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}

package com.prodigalgal.ircs.contracts.maintenance;

import java.util.Locale;

public enum MaintenanceGateMode {
    QUIESCE_WRITES(true, false),
    READ_ONLY(true, true),
    PAUSE_CONSUMERS(false, true);

    private final boolean blocksWrites;
    private final boolean blocksConsumers;

    MaintenanceGateMode(boolean blocksWrites, boolean blocksConsumers) {
        this.blocksWrites = blocksWrites;
        this.blocksConsumers = blocksConsumers;
    }

    public boolean blocksWrites() {
        return blocksWrites;
    }

    public boolean blocksConsumers() {
        return blocksConsumers;
    }

    public static MaintenanceGateMode parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("maintenance gate mode must not be blank");
        }
        return MaintenanceGateMode.valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    }
}

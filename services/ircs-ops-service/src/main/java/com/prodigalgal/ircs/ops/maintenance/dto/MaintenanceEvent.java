package com.prodigalgal.ircs.ops.maintenance.dto;

import java.util.Map;

public record MaintenanceEvent(
        EventType type,
        Object payload,
        long timestamp
) {
    public enum EventType {
        LOG,
        WARN,
        ERROR,
        PROGRESS,
        DONE
    }

    public static MaintenanceEvent error(String message) {
        return new MaintenanceEvent(EventType.ERROR, message, System.currentTimeMillis());
    }

    public static MaintenanceEvent log(String message) {
        return new MaintenanceEvent(EventType.LOG, message, System.currentTimeMillis());
    }

    public static MaintenanceEvent warn(String message) {
        return new MaintenanceEvent(EventType.WARN, message, System.currentTimeMillis());
    }

    public static MaintenanceEvent progress(long processed, long total) {
        return new MaintenanceEvent(EventType.PROGRESS, Map.of(
                "processed", processed,
                "total", total), System.currentTimeMillis());
    }

    public static MaintenanceEvent done() {
        return new MaintenanceEvent(EventType.DONE, null, System.currentTimeMillis());
    }
}

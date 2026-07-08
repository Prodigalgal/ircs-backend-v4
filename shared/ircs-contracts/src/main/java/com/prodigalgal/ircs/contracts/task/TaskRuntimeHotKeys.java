package com.prodigalgal.ircs.contracts.task;

import java.util.UUID;

public final class TaskRuntimeHotKeys {

    private static final String PREFIX = "ircs:task:";

    public static String masterSnapshot(UUID masterTaskId) {
        return PREFIX + "mt:" + masterTaskId + ":snapshot";
    }

    public static String masterState(UUID masterTaskId) {
        return PREFIX + "mt:" + masterTaskId + ":state";
    }

    public static String pageState(UUID pageTaskId) {
        return PREFIX + "pt:" + pageTaskId + ":state";
    }

    public static String pageCompletedDetails(UUID pageTaskId) {
        return PREFIX + "pt:" + pageTaskId + ":details:done";
    }

    public static String pageFailedDetails(UUID pageTaskId) {
        return PREFIX + "pt:" + pageTaskId + ":details:failed";
    }

    public static String pageFailedDetailErrors(UUID pageTaskId) {
        return PREFIX + "pt:" + pageTaskId + ":details:failed:errors";
    }

    public static String masterDiscoveredPages(UUID masterTaskId) {
        return PREFIX + "mt:" + masterTaskId + ":pages:discovered";
    }

    public static String masterScheduledPages(UUID masterTaskId) {
        return PREFIX + "mt:" + masterTaskId + ":pages:scheduled";
    }

    public static String activeMasters() {
        return PREFIX + "mt:active";
    }

    public static String sourceMasters(UUID dataSourceId) {
        return PREFIX + "source:" + dataSourceId + ":masters";
    }

    public static String dirtyMasters() {
        return PREFIX + "mt:dirty";
    }

    public static String eventStream() {
        return PREFIX + "events";
    }

    private TaskRuntimeHotKeys() {
    }
}

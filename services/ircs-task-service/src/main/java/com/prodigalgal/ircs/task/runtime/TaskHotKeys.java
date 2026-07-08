package com.prodigalgal.ircs.task.runtime;

import com.prodigalgal.ircs.contracts.task.TaskRuntimeHotKeys;
import java.util.UUID;

public final class TaskHotKeys {

    public static String masterSnapshot(UUID masterTaskId) {
        return TaskRuntimeHotKeys.masterSnapshot(masterTaskId);
    }

    public static String masterState(UUID masterTaskId) {
        return TaskRuntimeHotKeys.masterState(masterTaskId);
    }

    public static String pageState(UUID pageTaskId) {
        return TaskRuntimeHotKeys.pageState(pageTaskId);
    }

    public static String pageCompletedDetails(UUID pageTaskId) {
        return TaskRuntimeHotKeys.pageCompletedDetails(pageTaskId);
    }

    public static String pageFailedDetails(UUID pageTaskId) {
        return TaskRuntimeHotKeys.pageFailedDetails(pageTaskId);
    }

    public static String pageFailedDetailErrors(UUID pageTaskId) {
        return TaskRuntimeHotKeys.pageFailedDetailErrors(pageTaskId);
    }

    public static String masterDiscoveredPages(UUID masterTaskId) {
        return TaskRuntimeHotKeys.masterDiscoveredPages(masterTaskId);
    }

    public static String masterScheduledPages(UUID masterTaskId) {
        return TaskRuntimeHotKeys.masterScheduledPages(masterTaskId);
    }

    public static String activeMasters() {
        return TaskRuntimeHotKeys.activeMasters();
    }

    public static String sourceMasters(UUID dataSourceId) {
        return TaskRuntimeHotKeys.sourceMasters(dataSourceId);
    }

    public static String dirtyMasters() {
        return TaskRuntimeHotKeys.dirtyMasters();
    }

    public static String eventStream() {
        return TaskRuntimeHotKeys.eventStream();
    }

    private TaskHotKeys() {
    }
}

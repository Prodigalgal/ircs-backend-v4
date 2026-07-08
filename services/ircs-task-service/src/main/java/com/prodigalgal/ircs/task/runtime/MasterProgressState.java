package com.prodigalgal.ircs.task.runtime;

import com.prodigalgal.ircs.task.domain.TaskRuntimeStatus;

public record MasterProgressState(
        String status,
        long pageScheduled,
        long pageCompleted,
        Integer totalPages,
        Integer startPage
) {
    public boolean completedCurrentSlice() {
        return TaskRuntimeStatus.isCompleted(status)
                && pageScheduled > 0
                && pageCompleted >= pageScheduled;
    }
}

package com.prodigalgal.ircs.task.runtime;

import java.time.Instant;
import java.util.UUID;

public record TaskDetailCompletion(
        UUID masterTaskId,
        UUID pageTaskId,
        UUID detailTaskId,
        String sourceVid,
        boolean successful,
        String errorMessage,
        Instant completedAt
) {
}

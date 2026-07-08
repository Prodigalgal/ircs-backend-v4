package com.prodigalgal.ircs.contracts.task;

import java.time.Instant;
import java.util.UUID;

public record TaskDetailDoneMessage(
        UUID masterTaskId,
        UUID pageTaskId,
        UUID detailTaskId,
        String sourceVid,
        boolean successful,
        String errorMessage,
        String correlationId,
        Instant completedAt
) {
}

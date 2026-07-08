package com.prodigalgal.ircs.contracts.task;

import java.time.Instant;
import java.util.UUID;

public record TaskPageFailedMessage(
        UUID masterTaskId,
        UUID pageTaskId,
        int pageNumber,
        String errorMessage,
        String correlationId,
        Instant failedAt
) {
}

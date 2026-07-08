package com.prodigalgal.ircs.task.dto;

import java.util.UUID;

public record MediaRequestBatchItemResponse(
        UUID id,
        UUID mediaRequestId,
        String title,
        Integer releaseYear,
        int requestCount,
        String status,
        UUID existingVideoId,
        String existingVideoSource,
        int scheduledTaskCount,
        String lastErrorMessage) {
}

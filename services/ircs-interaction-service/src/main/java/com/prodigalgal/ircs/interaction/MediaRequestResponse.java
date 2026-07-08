package com.prodigalgal.ircs.interaction;

import java.time.Instant;
import java.util.UUID;

public record MediaRequestResponse(
        UUID id,
        UUID memberId,
        String title,
        Integer releaseYear,
        String extraInfo,
        String status,
        int requestCount,
        Instant createdAt,
        Instant updatedAt,
        Instant lastRequestedAt,
        Instant scheduledAt,
        Instant completedAt,
        String lastErrorMessage,
        UUID existingVideoId,
        String existingVideoSource,
        int spentPoints) {
}

package com.prodigalgal.ircs.apigateway;

import java.time.Instant;
import java.util.UUID;

record AdminApiTokenRecord(
        UUID id,
        String name,
        String tokenPrefix,
        String tokenHash,
        String status,
        String createdBy,
        Instant createdAt,
        Instant lastUsedAt,
        Instant revokedAt,
        String revokedBy,
        Instant expiresAt) {
}

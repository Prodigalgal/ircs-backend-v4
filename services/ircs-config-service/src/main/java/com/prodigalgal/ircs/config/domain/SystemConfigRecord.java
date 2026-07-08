package com.prodigalgal.ircs.config.domain;

import java.time.Instant;
import java.util.UUID;

public record SystemConfigRecord(
        UUID id,
        String key,
        String value,
        String description,
        long revision,
        Instant updatedAt
) {
    public SystemConfigRecord(UUID id, String key, String value, String description, Instant updatedAt) {
        this(id, key, value, description, 0L, updatedAt);
    }
}

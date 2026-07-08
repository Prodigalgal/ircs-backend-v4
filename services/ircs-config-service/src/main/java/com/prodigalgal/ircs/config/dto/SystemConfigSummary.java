package com.prodigalgal.ircs.config.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SystemConfigSummary(
        UUID id,
        String key,
        String value,
        String effectiveValue,
        String effectiveSource,
        String description,
        long revision,
        Instant updatedAt,
        boolean sensitive,
        String activationMode,
        List<String> restartServices
) {
    public SystemConfigSummary(
            UUID id,
            String key,
            String value,
            String description,
            Instant updatedAt,
            boolean sensitive) {
        this(id, key, value, value, "DB", description, 0L, updatedAt, sensitive, "HOT", List.of());
    }

    public SystemConfigSummary(
            UUID id,
            String key,
            String value,
            String effectiveValue,
            String effectiveSource,
            String description,
            Instant updatedAt,
            boolean sensitive) {
        this(id, key, value, effectiveValue, effectiveSource, description, 0L, updatedAt, sensitive, "HOT", List.of());
    }

    public SystemConfigSummary(
            UUID id,
            String key,
            String value,
            String effectiveValue,
            String effectiveSource,
            String description,
            long revision,
            Instant updatedAt,
            boolean sensitive) {
        this(id, key, value, effectiveValue, effectiveSource, description, revision, updatedAt, sensitive, "HOT", List.of());
    }
}

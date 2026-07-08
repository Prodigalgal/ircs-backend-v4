package com.prodigalgal.ircs.contracts.config;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SystemConfigChangedEvent(
        UUID eventId,
        String key,
        Action action,
        String effectiveSource,
        boolean sensitive,
        long revision,
        long previousRevision,
        Instant changedAt
) {
    public static final String ROUTING_KEY = "config.system.changed";

    public SystemConfigChangedEvent {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(key, "key is required");
        Objects.requireNonNull(action, "action is required");
        Objects.requireNonNull(changedAt, "changedAt is required");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        if (previousRevision < 0) {
            throw new IllegalArgumentException("previousRevision must be >= 0");
        }
    }

    public enum Action {
        CREATED,
        UPDATED,
        DELETED
    }
}

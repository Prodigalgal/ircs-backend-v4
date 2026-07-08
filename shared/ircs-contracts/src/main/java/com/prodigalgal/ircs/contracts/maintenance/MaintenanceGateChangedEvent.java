package com.prodigalgal.ircs.contracts.maintenance;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record MaintenanceGateChangedEvent(
        UUID eventId,
        UUID operationId,
        String operationKey,
        Action action,
        String ownerService,
        String resourceType,
        String resourceScope,
        MaintenanceGateMode mode,
        MaintenanceGateStatus status,
        long revision,
        Instant changedAt,
        Instant expiresAt,
        String correlationId
) {
    public static final String ROUTING_KEY = "maintenance.gate.changed";
    public static final String ALL_SCOPE = "*";
    public static final String ALL_OWNER_SERVICES = "*";

    public MaintenanceGateChangedEvent {
        Objects.requireNonNull(eventId, "eventId is required");
        Objects.requireNonNull(operationId, "operationId is required");
        operationKey = required(operationKey, "operationKey");
        Objects.requireNonNull(action, "action is required");
        ownerService = required(ownerService, "ownerService");
        resourceType = required(resourceType, "resourceType");
        resourceScope = hasText(resourceScope) ? resourceScope.trim() : ALL_SCOPE;
        Objects.requireNonNull(mode, "mode is required");
        Objects.requireNonNull(status, "status is required");
        if (revision < 0) {
            throw new IllegalArgumentException("revision must be >= 0");
        }
        Objects.requireNonNull(changedAt, "changedAt is required");
        Objects.requireNonNull(expiresAt, "expiresAt is required");
        correlationId = hasText(correlationId) ? correlationId.trim() : "";
    }

    public enum Action {
        CREATED,
        CLOSED
    }

    private static String required(String value, String name) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}

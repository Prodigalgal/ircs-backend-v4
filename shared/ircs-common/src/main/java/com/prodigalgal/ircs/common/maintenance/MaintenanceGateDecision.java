package com.prodigalgal.ircs.common.maintenance;

import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import java.time.Instant;
import java.util.UUID;

public record MaintenanceGateDecision(
        boolean allowed,
        MaintenanceGateCheckKind checkKind,
        UUID operationId,
        String operationKey,
        String ownerService,
        String resourceType,
        String resourceScope,
        MaintenanceGateMode mode,
        String reason,
        Instant expiresAt
) {
    public static MaintenanceGateDecision allowed(
            MaintenanceGateCheckKind checkKind,
            String ownerService,
            String resourceType,
            String resourceScope) {
        return new MaintenanceGateDecision(
                true,
                checkKind,
                null,
                "",
                ownerService,
                resourceType,
                resourceScope,
                null,
                "",
                null);
    }

    public static MaintenanceGateDecision blocked(
            MaintenanceGateCheckKind checkKind,
            UUID operationId,
            String operationKey,
            String ownerService,
            String resourceType,
            String resourceScope,
            MaintenanceGateMode mode,
            String reason,
            Instant expiresAt) {
        return new MaintenanceGateDecision(
                false,
                checkKind,
                operationId,
                operationKey,
                ownerService,
                resourceType,
                resourceScope,
                mode,
                reason,
                expiresAt);
    }

    public MaintenanceGateDecision {
        checkKind = checkKind == null ? MaintenanceGateCheckKind.WRITE : checkKind;
        operationKey = operationKey == null ? "" : operationKey;
        ownerService = normalize(ownerService, "*");
        resourceType = normalize(resourceType, "*");
        resourceScope = normalize(resourceScope, "*");
        reason = reason == null ? "" : reason;
    }

    public String blockedMessage() {
        if (allowed) {
            return "";
        }
        return "maintenance gate blocks " + checkKind.name().toLowerCase(java.util.Locale.ROOT)
                + " for " + ownerService + "/" + resourceType + "/" + resourceScope;
    }

    private static String normalize(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}

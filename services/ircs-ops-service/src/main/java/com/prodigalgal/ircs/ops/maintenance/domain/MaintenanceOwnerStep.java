package com.prodigalgal.ircs.ops.maintenance.domain;

public record MaintenanceOwnerStep(
        String stepName,
        String ownerService,
        String operation,
        String requiredScope
) {
    public MaintenanceOwnerStep {
        stepName = normalizeRequired(stepName, "stepName");
        ownerService = normalizeRequired(ownerService, "ownerService");
        operation = normalizeRequired(operation, "operation");
        requiredScope = normalize(requiredScope);
    }

    private static String normalizeRequired(String value, String field) {
        String normalized = normalize(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}

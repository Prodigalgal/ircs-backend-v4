package com.prodigalgal.ircs.common.audit;

import java.util.UUID;

public record AuditReplicationWorkPayload(
        AuditClass auditClass,
        String sourceTable,
        UUID sourceId,
        String eventType,
        String payload) {
}

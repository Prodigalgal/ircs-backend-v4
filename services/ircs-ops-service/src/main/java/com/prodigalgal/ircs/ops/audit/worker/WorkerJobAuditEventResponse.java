package com.prodigalgal.ircs.ops.audit.worker;

import java.time.Instant;
import java.util.UUID;

public record WorkerJobAuditEventResponse(
        UUID id,
        Instant createdAt,
        Instant updatedAt,
        Long version,
        String auditClass,
        String jobSource,
        String jobType,
        String jobName,
        String correlationId,
        String status,
        Long durationMs,
        String errorClass,
        String errorMessage) {
    public WorkerJobAuditEventResponse(
            UUID id,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            String jobSource,
            String jobType,
            String jobName,
            String correlationId,
            String status,
            Long durationMs,
            String errorClass,
            String errorMessage) {
        this(
                id,
                createdAt,
                updatedAt,
                version,
                "SYSTEM",
                jobSource,
                jobType,
                jobName,
                correlationId,
                status,
                durationMs,
                errorClass,
                errorMessage);
    }
}

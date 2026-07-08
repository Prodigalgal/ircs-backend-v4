package com.prodigalgal.ircs.ops.audit.request;

import java.time.Instant;
import java.util.UUID;

public record RequestAuditLogResponse(
        UUID id,
        Instant createdAt,
        Instant updatedAt,
        Long version,
        String auditClass,
        String requestSource,
        String username,
        String method,
        String path,
        String queryString,
        Integer statusCode,
        Boolean success,
        Long durationMs,
        String clientIp,
        String userAgent,
        String traceId,
        String errorMessage) {
    public RequestAuditLogResponse(
            UUID id,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            String requestSource,
            String username,
            String method,
            String path,
            String queryString,
            Integer statusCode,
            Boolean success,
            Long durationMs,
            String clientIp,
            String userAgent,
            String traceId,
            String errorMessage) {
        this(
                id,
                createdAt,
                updatedAt,
                version,
                "BEHAVIOR",
                requestSource,
                username,
                method,
                path,
                queryString,
                statusCode,
                success,
                durationMs,
                clientIp,
                userAgent,
                traceId,
                errorMessage);
    }
}

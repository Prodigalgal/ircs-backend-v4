package com.prodigalgal.ircs.ops.audit.notification;

import java.time.Instant;
import java.util.UUID;

public record NotificationMailSendHistoryResponse(
        UUID id,
        Instant createdAt,
        Instant updatedAt,
        Long version,
        String auditClass,
        String correlationId,
        String recipient,
        String subject,
        String templateCode,
        String deliveryMode,
        String status,
        UUID credentialId,
        String failureCode,
        String failureMessage) {
    public NotificationMailSendHistoryResponse(
            UUID id,
            Instant createdAt,
            Instant updatedAt,
            Long version,
            String correlationId,
            String recipient,
            String subject,
            String templateCode,
            String deliveryMode,
            String status,
            UUID credentialId,
            String failureCode,
            String failureMessage) {
        this(
                id,
                createdAt,
                updatedAt,
                version,
                "SYSTEM",
                correlationId,
                recipient,
                subject,
                templateCode,
                deliveryMode,
                status,
                credentialId,
                failureCode,
                failureMessage);
    }
}

package com.prodigalgal.ircs.notification.mail;

import java.util.UUID;

record MailSendHistoryEvent(
        String correlationId,
        String recipient,
        String subject,
        String templateCode,
        NotificationMailProperties.DeliveryMode deliveryMode,
        MailSendHistoryStatus status,
        UUID credentialId,
        String failureCode,
        String failureMessage) {

    static MailSendHistoryEvent skipped(
            String correlationId,
            String recipient,
            String subject,
            String templateCode,
            NotificationMailProperties.DeliveryMode deliveryMode,
            String reason) {
        return new MailSendHistoryEvent(
                correlationId,
                recipient,
                subject,
                templateCode,
                deliveryMode,
                MailSendHistoryStatus.SKIPPED,
                null,
                reason,
                null);
    }

    static MailSendHistoryEvent sent(
            String correlationId,
            RenderedMail mail,
            String templateCode,
            NotificationMailProperties.DeliveryMode deliveryMode,
            MailCredential credential) {
        return new MailSendHistoryEvent(
                correlationId,
                mail.to(),
                mail.subject(),
                templateCode,
                deliveryMode,
                MailSendHistoryStatus.SENT,
                credential == null ? null : credential.id(),
                null,
                null);
    }

    static MailSendHistoryEvent failed(
            String correlationId,
            RenderedMail mail,
            String templateCode,
            NotificationMailProperties.DeliveryMode deliveryMode,
            MailCredential credential,
            Throwable error) {
        return new MailSendHistoryEvent(
                correlationId,
                mail.to(),
                mail.subject(),
                templateCode,
                deliveryMode,
                MailSendHistoryStatus.FAILED,
                credential == null ? null : credential.id(),
                error == null ? null : error.getClass().getName(),
                error == null ? null : error.getMessage());
    }
}

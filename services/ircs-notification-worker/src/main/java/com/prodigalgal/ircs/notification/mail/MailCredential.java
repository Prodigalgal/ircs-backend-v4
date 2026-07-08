package com.prodigalgal.ircs.notification.mail;

import java.util.UUID;

record MailCredential(
        UUID id,
        String username,
        String password,
        String smtpHost,
        Integer smtpPort,
        String smtpProtocol,
        Boolean smtpAuth,
        Boolean smtpStarttlsEnabled,
        Boolean smtpSslEnabled,
        Integer smtpTimeoutMs,
        Integer rateLimit,
        String rateLimitUnit,
        Long dayLimit,
        Long monthLimit) {

    MailCredential(UUID id, String username, String password) {
        this(id, username, password, null, null, null, null, null, null, null, null, null, null, null);
    }

    MailCredential(
            UUID id,
            String username,
            String password,
            String smtpHost,
            Integer smtpPort,
            String smtpProtocol,
            Boolean smtpAuth,
            Boolean smtpStarttlsEnabled,
            Boolean smtpSslEnabled,
            Integer smtpTimeoutMs) {
        this(
                id,
                username,
                password,
                smtpHost,
                smtpPort,
                smtpProtocol,
                smtpAuth,
                smtpStarttlsEnabled,
                smtpSslEnabled,
                smtpTimeoutMs,
                null,
                null,
                null,
                null);
    }
}

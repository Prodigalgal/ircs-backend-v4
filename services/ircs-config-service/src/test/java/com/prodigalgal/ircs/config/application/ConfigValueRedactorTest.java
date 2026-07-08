package com.prodigalgal.ircs.config.application;


import com.prodigalgal.ircs.config.domain.SystemConfigRecord;
import com.prodigalgal.ircs.config.dto.SystemConfigSummary;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConfigValueRedactorTest {

    private final ConfigValueRedactor redactor = new ConfigValueRedactor();

    @Test
    void redactsSensitiveValues() {
        SystemConfigSummary summary = redactor.sanitize(new SystemConfigRecord(
                UUID.randomUUID(),
                "security.jwt.secret",
                "very-secret",
                "JWT secret",
                Instant.parse("2026-06-03T00:00:00Z")));

        assertTrue(summary.sensitive());
        assertNull(summary.value());
    }

    @Test
    void redactsOAuthAppKeys() {
        SystemConfigSummary summary = redactor.sanitize(new SystemConfigRecord(
                UUID.randomUUID(),
                "member.oauth.qq.app-key",
                "qq-secret",
                "QQ app key",
                Instant.parse("2026-06-03T00:00:00Z")));

        assertTrue(summary.sensitive());
        assertNull(summary.value());
    }

    @Test
    void keepsNonSensitiveValues() {
        SystemConfigSummary summary = redactor.sanitize(new SystemConfigRecord(
                UUID.randomUUID(),
                "global.traffic.max-wait-ms",
                "120000",
                "Traffic wait",
                Instant.parse("2026-06-03T00:00:00Z")));

        assertFalse(summary.sensitive());
        assertTrue("120000".equals(summary.value()));
    }

    @Test
    void keepsNonSecretAuthSettingsVisible() {
        SystemConfigSummary summary = redactor.sanitize(new SystemConfigRecord(
                UUID.randomUUID(),
                "app.mail.properties.auth",
                "true",
                "SMTP auth switch",
                Instant.parse("2026-06-03T00:00:00Z")));

        assertFalse(summary.sensitive());
        assertTrue("true".equals(summary.value()));
    }
}

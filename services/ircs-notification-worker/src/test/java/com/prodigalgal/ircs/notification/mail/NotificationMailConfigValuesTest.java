package com.prodigalgal.ircs.notification.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class NotificationMailConfigValuesTest {

    private final SystemConfigRepository repository = org.mockito.Mockito.mock(SystemConfigRepository.class);

    @Test
    void usesV1DefaultsWhenNoRuntimeOrDbConfigExists() {
        NotificationMailConfigValues values = values(new MockEnvironment());

        assertTrue(values.enabled());
        assertEquals(NotificationMailConfigValues.DEFAULT_FROM, values.from());

        NotificationMailConfigValues.MailConnectOptions options = values.smtpOptions(Optional.empty());
        assertEquals("smtp.gmail.com", options.host());
        assertEquals(465, options.port());
        assertEquals("smtp", options.protocol());
        assertTrue(options.auth());
        assertFalse(options.starttlsEnabled());
        assertTrue(options.sslEnabled());
        assertEquals(10000, options.timeout());
        assertFalse(options.debug());
        assertEquals(1000, values.rateLimitMinMs());
        assertEquals(2000, values.rateLimitMaxMs());
        assertEquals(60000, values.rateLimitMaxWaitMs());
    }

    @Test
    void dbCanonicalKeysOverrideV1Defaults() {
        when(repository.findValue(NotificationMailConfigValues.ENABLED_KEY)).thenReturn(Optional.of("false"));
        when(repository.findValue(NotificationMailConfigValues.FROM_KEY))
                .thenReturn(Optional.of("DB From <db@example.invalid>"));
        when(repository.findValue(NotificationMailConfigValues.HOST_KEY))
                .thenReturn(Optional.of("smtp.db.example.invalid"));
        when(repository.findValue(NotificationMailConfigValues.PORT_KEY)).thenReturn(Optional.of("2525"));
        when(repository.findValue(NotificationMailConfigValues.PROTOCOL_KEY)).thenReturn(Optional.of("smtps"));
        when(repository.findValue(NotificationMailConfigValues.AUTH_KEY)).thenReturn(Optional.of("false"));
        when(repository.findValue(NotificationMailConfigValues.STARTTLS_KEY)).thenReturn(Optional.of("true"));
        when(repository.findValue(NotificationMailConfigValues.SSL_KEY)).thenReturn(Optional.of("false"));
        when(repository.findValue(NotificationMailConfigValues.TIMEOUT_KEY)).thenReturn(Optional.of("3000"));
        when(repository.findValue(NotificationMailConfigValues.DEBUG_KEY)).thenReturn(Optional.of("true"));

        NotificationMailConfigValues values = values(new MockEnvironment());

        assertFalse(values.enabled());
        assertEquals("DB From <db@example.invalid>", values.from());
        NotificationMailConfigValues.MailConnectOptions options = values.smtpOptions(Optional.empty());
        assertEquals("smtp.db.example.invalid", options.host());
        assertEquals(2525, options.port());
        assertEquals("smtps", options.protocol());
        assertFalse(options.auth());
        assertTrue(options.starttlsEnabled());
        assertFalse(options.sslEnabled());
        assertEquals(3000, options.timeout());
        assertTrue(options.debug());
    }

    @Test
    void runtimeAliasesOverrideDbCanonicalKeys() {
        when(repository.findValue(NotificationMailConfigValues.ENABLED_KEY)).thenReturn(Optional.of("true"));
        when(repository.findValue(NotificationMailConfigValues.FROM_KEY))
                .thenReturn(Optional.of("DB From <db@example.invalid>"));
        when(repository.findValue(NotificationMailConfigValues.HOST_KEY))
                .thenReturn(Optional.of("smtp.db.example.invalid"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_NOTIFICATION_MAIL_ENABLED", "false")
                .withProperty("APP_NOTIFICATION_MAIL_FROM", "Env From <env@example.invalid>")
                .withProperty("APP_MAIL_HOST", "smtp.env.example.invalid")
                .withProperty("APP_MAIL_PORT", "1025")
                .withProperty("APP_MAIL_SMTP_AUTH", "false")
                .withProperty("APP_MAIL_STARTTLS_ENABLED", "true")
                .withProperty("APP_MAIL_SSL_ENABLED", "false")
                .withProperty("APP_MAIL_TIMEOUT", "2000")
                .withProperty("APP_MAIL_DEBUG", "true");

        NotificationMailConfigValues values = values(environment);

        assertFalse(values.enabled());
        assertEquals("Env From <env@example.invalid>", values.from());
        NotificationMailConfigValues.MailConnectOptions options = values.smtpOptions(Optional.empty());
        assertEquals("smtp.env.example.invalid", options.host());
        assertEquals(1025, options.port());
        assertFalse(options.auth());
        assertTrue(options.starttlsEnabled());
        assertFalse(options.sslEnabled());
        assertEquals(2000, options.timeout());
        assertTrue(options.debug());
    }

    @Test
    void invalidValuesFallBackWithoutChangingDefaultSemantics() {
        when(repository.findValue(NotificationMailConfigValues.ENABLED_KEY)).thenReturn(Optional.of("not-a-boolean"));
        when(repository.findValue(NotificationMailConfigValues.PORT_KEY)).thenReturn(Optional.of("bad"));
        when(repository.findValue(NotificationMailConfigValues.TIMEOUT_KEY)).thenReturn(Optional.of("bad"));

        NotificationMailConfigValues values = values(new MockEnvironment());

        assertTrue(values.enabled());
        NotificationMailConfigValues.MailConnectOptions options = values.smtpOptions(Optional.empty());
        assertEquals(465, options.port());
        assertEquals(10000, options.timeout());
    }

    @Test
    void mailCredentialOverridesStaticRuntimeUsernameAndPassword() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_MAIL_USERNAME", "static@example.invalid")
                .withProperty("APP_MAIL_PASSWORD", "static-secret");
        NotificationMailConfigValues values = values(environment);
        MailCredential credential = new MailCredential(
                UUID.fromString("de0a6fd9-f07d-4201-bf92-279b6c9f099d"),
                "credential@example.invalid",
                "credential-secret");

        NotificationMailConfigValues.MailConnectOptions options = values.smtpOptions(Optional.of(credential));

        assertEquals("credential@example.invalid", options.username());
        assertEquals("credential-secret", options.password());
    }

    @Test
    void mailCredentialOverridesSmtpConnectionOptions() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_MAIL_HOST", "smtp.global.example.invalid")
                .withProperty("APP_MAIL_PORT", "465")
                .withProperty("APP_MAIL_SSL_ENABLED", "true")
                .withProperty("APP_MAIL_STARTTLS_ENABLED", "false")
                .withProperty("APP_MAIL_TIMEOUT", "10000");
        NotificationMailConfigValues values = values(environment);
        MailCredential credential = new MailCredential(
                UUID.fromString("de0a6fd9-f07d-4201-bf92-279b6c9f099d"),
                "credential@example.invalid",
                "credential-secret",
                "smtp.credential.example.invalid",
                587,
                "smtp",
                true,
                true,
                false,
                7000);

        NotificationMailConfigValues.MailConnectOptions options = values.smtpOptions(Optional.of(credential));

        assertEquals("smtp.credential.example.invalid", options.host());
        assertEquals(587, options.port());
        assertEquals("smtp", options.protocol());
        assertTrue(options.auth());
        assertTrue(options.starttlsEnabled());
        assertFalse(options.sslEnabled());
        assertEquals(7000, options.timeout());
    }

    @Test
    void deliveryModeUsesRuntimeAliasBeforeBoundProperties() {
        NotificationMailProperties properties = new NotificationMailProperties();
        properties.setDeliveryMode(NotificationMailProperties.DeliveryMode.SMTP);
        NotificationMailConfigValues values = new NotificationMailConfigValues(
                new MockEnvironment().withProperty("APP_MAIL_DELIVERY_MODE", "sink"),
                repository,
                properties);

        assertEquals(NotificationMailProperties.DeliveryMode.SINK, values.deliveryMode());
    }

    @Test
    void mailRateLimitSettingsUseDbAndNormalizeMaxBelowMin() {
        when(repository.findValue(NotificationMailConfigValues.RATE_LIMIT_MIN_KEY)).thenReturn(Optional.of("3000"));
        when(repository.findValue(NotificationMailConfigValues.RATE_LIMIT_MAX_KEY)).thenReturn(Optional.of("1000"));
        when(repository.findValue(NotificationMailConfigValues.RATE_LIMIT_MAX_WAIT_KEY)).thenReturn(Optional.of("15000"));

        NotificationMailConfigValues values = values(new MockEnvironment());

        assertEquals(3000, values.rateLimitMinMs());
        assertEquals(3000, values.rateLimitMaxMs());
        assertEquals(15000, values.rateLimitMaxWaitMs());
    }

    private NotificationMailConfigValues values(MockEnvironment environment) {
        return new NotificationMailConfigValues(environment, repository, new NotificationMailProperties());
    }
}

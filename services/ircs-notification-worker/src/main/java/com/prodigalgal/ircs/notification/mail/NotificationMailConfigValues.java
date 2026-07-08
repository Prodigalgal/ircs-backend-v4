package com.prodigalgal.ircs.notification.mail;

import java.util.Arrays;
import java.util.Optional;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class NotificationMailConfigValues {

    static final String ENABLED_KEY = "app.mail.enabled";
    static final String ENABLED_ALIAS = "app.notification.mail.enabled";
    static final String FROM_KEY = "app.mail.from";
    static final String FROM_ALIAS = "app.notification.mail.from";
    static final String HOST_KEY = "app.mail.host";
    static final String PORT_KEY = "app.mail.port";
    static final String PROTOCOL_KEY = "app.mail.protocol";
    static final String AUTH_KEY = "app.mail.properties.auth";
    static final String STARTTLS_KEY = "app.mail.properties.starttls";
    static final String SSL_KEY = "app.mail.properties.ssl";
    static final String TIMEOUT_KEY = "app.mail.timeout";
    static final String DEBUG_KEY = "app.mail.debug";
    static final String RATE_LIMIT_MIN_KEY = "app.mail.rate-limit.min-ms";
    static final String RATE_LIMIT_MAX_KEY = "app.mail.rate-limit.max-ms";
    static final String RATE_LIMIT_MAX_WAIT_KEY = "app.mail.rate-limit.max-wait-ms";
    static final String DEFAULT_FROM = "HuaWai System <huawai.system@gmail.com>";
    static final String DEFAULT_HOST = "smtp.gmail.com";
    static final int DEFAULT_PORT = 465;
    static final String DEFAULT_PROTOCOL = "smtp";
    static final boolean DEFAULT_ENABLED = true;
    static final boolean DEFAULT_AUTH = true;
    static final boolean DEFAULT_STARTTLS = false;
    static final boolean DEFAULT_SSL = true;
    static final int DEFAULT_TIMEOUT = 10000;
    static final boolean DEFAULT_DEBUG = false;
    static final int DEFAULT_RATE_LIMIT_MIN_MS = 1000;
    static final int DEFAULT_RATE_LIMIT_MAX_MS = 2000;
    static final int DEFAULT_RATE_LIMIT_MAX_WAIT_MS = 60000;

    private final Environment environment;
    private final SystemConfigRepository repository;
    private final NotificationMailProperties properties;

    NotificationMailConfigValues(
            Environment environment,
            SystemConfigRepository repository,
            NotificationMailProperties properties) {
        this.environment = environment;
        this.repository = repository;
        this.properties = properties;
    }

    boolean enabled() {
        return booleanValue(DEFAULT_ENABLED, ENABLED_KEY, ENABLED_ALIAS);
    }

    String from() {
        return stringValue(DEFAULT_FROM, FROM_KEY, FROM_ALIAS, "mail.from");
    }

    NotificationMailProperties.DeliveryMode deliveryMode() {
        String raw = RuntimeInjectedConfig.find(
                        environment,
                        "app.notification.mail.delivery-mode",
                        "app.mail.delivery-mode",
                        "APP_MAIL_DELIVERY_MODE")
                .orElse(null);
        if (!StringUtils.hasText(raw)) {
            return properties.getDeliveryMode();
        }
        try {
            return NotificationMailProperties.DeliveryMode.valueOf(raw.trim().replace('-', '_').toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return properties.getDeliveryMode();
        }
    }

    int rateLimitMinMs() {
        return Math.max(0, intValue(DEFAULT_RATE_LIMIT_MIN_MS, RATE_LIMIT_MIN_KEY));
    }

    int rateLimitMaxMs() {
        int min = rateLimitMinMs();
        int max = Math.max(0, intValue(DEFAULT_RATE_LIMIT_MAX_MS, RATE_LIMIT_MAX_KEY));
        return Math.max(min, max);
    }

    int rateLimitMaxWaitMs() {
        return Math.max(0, intValue(DEFAULT_RATE_LIMIT_MAX_WAIT_MS, RATE_LIMIT_MAX_WAIT_KEY));
    }

    MailConnectOptions smtpOptions(Optional<MailCredential> credential) {
        String username = credential.map(MailCredential::username).orElseGet(this::staticUsername);
        String password = credential.map(MailCredential::password).orElseGet(this::staticPassword);
        return new MailConnectOptions(
                credential.flatMap(this::smtpHost).orElseGet(() ->
                        stringValue(DEFAULT_HOST, HOST_KEY, "spring.mail.host", "mail.host")),
                credential.flatMap(this::smtpPort).orElseGet(() ->
                        intValue(DEFAULT_PORT, PORT_KEY, "spring.mail.port", "mail.port")),
                username,
                password,
                credential.flatMap(this::smtpProtocol).orElseGet(() ->
                        stringValue(DEFAULT_PROTOCOL, PROTOCOL_KEY, "spring.mail.protocol")),
                credential.map(MailCredential::smtpAuth).orElseGet(() ->
                        booleanValue(DEFAULT_AUTH, AUTH_KEY, "APP_MAIL_SMTP_AUTH", "spring.mail.properties.mail.smtp.auth")),
                credential.map(MailCredential::smtpStarttlsEnabled).orElseGet(() ->
                        booleanValue(DEFAULT_STARTTLS, STARTTLS_KEY, "APP_MAIL_STARTTLS_ENABLED",
                                "spring.mail.properties.mail.smtp.starttls.enable")),
                credential.map(MailCredential::smtpSslEnabled).orElseGet(() ->
                        booleanValue(DEFAULT_SSL, SSL_KEY, "APP_MAIL_SSL_ENABLED",
                                "spring.mail.properties.mail.smtp.ssl.enable")),
                credential.flatMap(this::smtpTimeout).orElseGet(() ->
                        intValue(DEFAULT_TIMEOUT, TIMEOUT_KEY, "APP_MAIL_TIMEOUT",
                                "spring.mail.properties.mail.smtp.timeout")),
                booleanValue(DEFAULT_DEBUG, DEBUG_KEY, "APP_MAIL_DEBUG", "spring.mail.properties.mail.debug"));
    }

    private Optional<String> smtpHost(MailCredential credential) {
        return Optional.ofNullable(credential.smtpHost()).filter(StringUtils::hasText).map(String::trim);
    }

    private Optional<String> smtpProtocol(MailCredential credential) {
        return Optional.ofNullable(credential.smtpProtocol()).filter(StringUtils::hasText).map(String::trim);
    }

    private Optional<Integer> smtpPort(MailCredential credential) {
        Integer port = credential.smtpPort();
        return port == null || port <= 0 ? Optional.empty() : Optional.of(port);
    }

    private Optional<Integer> smtpTimeout(MailCredential credential) {
        Integer timeout = credential.smtpTimeoutMs();
        return timeout == null || timeout <= 0 ? Optional.empty() : Optional.of(timeout);
    }

    private String staticUsername() {
        return RuntimeInjectedConfig.find(
                        environment,
                        "app.mail.username",
                        "APP_MAIL_USERNAME",
                        "spring.mail.username")
                .orElse("");
    }

    private String staticPassword() {
        return RuntimeInjectedConfig.find(
                        environment,
                        "app.mail.password",
                        "APP_MAIL_PASSWORD",
                        "spring.mail.password")
                .orElse("");
    }

    private String stringValue(String defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        return StringUtils.hasText(raw) ? raw.trim() : defaultValue;
    }

    private int intValue(int defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private boolean booleanValue(boolean defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        String normalized = raw.trim().toLowerCase();
        if ("true".equals(normalized)) {
            return true;
        }
        if ("false".equals(normalized)) {
            return false;
        }
        return defaultValue;
    }

    private String value(String key, String... aliases) {
        String[] runtimeKeys = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(key),
                        Arrays.stream(aliases))
                .toArray(String[]::new);
        return RuntimeInjectedConfig.find(environment, runtimeKeys)
                .or(() -> repository.findValue(key))
                .orElse(null);
    }

    record MailConnectOptions(
            String host,
            int port,
            String username,
            String password,
            String protocol,
            boolean auth,
            boolean starttlsEnabled,
            boolean sslEnabled,
            int timeout,
            boolean debug) {
    }
}

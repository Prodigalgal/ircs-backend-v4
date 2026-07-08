package com.prodigalgal.ircs.normalization;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class NormalizationConfigValues {

    static final String MAX_RETRIES_KEY = "normalization.max-retries";
    static final String MAX_RETRIES_ALIAS = "app.normalization.max-retries";
    static final String BACKOFF_BASE_SECONDS_KEY = "normalization.backoff.base-seconds";
    static final String BACKOFF_BASE_SECONDS_ALIAS = "app.normalization.backoff-base-seconds";
    static final String LLM_ENABLED_KEY = "app.ai.llm.enabled";
    static final String LLM_CLEANING_MODE_KEY = "app.ai.llm.cleaning.mode";
    static final String LLM_MODEL_KEY = "app.ai.llm.model";
    static final String LLM_CREDENTIAL_SERVICE_BASE_URL_KEY = "app.normalization.llm.credential-service.base-url";
    static final String LLM_CREDENTIAL_SERVICE_BASE_URL_ALIAS = "app.credential-service.base-url";
    static final String LLM_CREDENTIAL_SERVICE_TOKEN_KEY = "app.normalization.llm.credential-service.token";
    static final String LLM_CREDENTIAL_SERVICE_ID_KEY = "app.normalization.llm.credential-service.service-id";
    static final String LLM_CREDENTIAL_SERVICE_SCOPES_KEY = "app.normalization.llm.credential-service.scopes";
    static final String LLM_REQUEST_TIMEOUT_SECONDS_KEY = "app.ai.llm.request-timeout-seconds";
    static final String LLM_API_KEY_KEY = "spring.ai.openai.api-key";
    static final String LLM_BASE_URL_KEY = "spring.ai.openai.base-url";
    static final String LLM_PROVIDER_KEY = "app.ai.llm.provider";
    static final String PENDING_WATCHDOG_ENABLED_KEY = "app.normalization.pending-watchdog.enabled";
    static final String PENDING_WATCHDOG_BATCH_SIZE_KEY = "app.normalization.pending-watchdog.batch-size";
    static final String PENDING_WATCHDOG_MIN_PENDING_AGE_KEY = "app.normalization.pending-watchdog.min-pending-age";
    static final String PENDING_WATCHDOG_LEASE_TTL_KEY = "app.normalization.pending-watchdog.lease-ttl";
    static final int DEFAULT_MAX_RETRIES = 5;
    static final long DEFAULT_BACKOFF_BASE_SECONDS = 60L;
    static final int DEFAULT_PENDING_WATCHDOG_BATCH_SIZE = 200;
    static final Duration DEFAULT_PENDING_WATCHDOG_MIN_PENDING_AGE = Duration.ofMinutes(5);
    static final Duration DEFAULT_PENDING_WATCHDOG_LEASE_TTL = Duration.ofSeconds(45);
    static final String DEFAULT_LLM_CLEANING_MODE = "dry-run";
    static final String DEFAULT_LLM_MODEL = "gemma-4-31b-it";
    static final String DEFAULT_LLM_PROVIDER = "OPENAI";
    static final String DEFAULT_SERVICE_ID = "normalization-worker";
    static final String DEFAULT_CREDENTIAL_SERVICE_SCOPES = "credential:lease";
    static final String DEFAULT_OPENAI_BASE_URL = "https://ai.mnnu.eu.org/v1";
    static final long DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS = 30L;

    private final Environment environment;
    private final SystemConfigRepository repository;

    NormalizationConfigValues(Environment environment, SystemConfigRepository repository) {
        this.environment = environment;
        this.repository = repository;
    }

    int maxRetries() {
        return intValue(DEFAULT_MAX_RETRIES, MAX_RETRIES_KEY, MAX_RETRIES_ALIAS);
    }

    long backoffBaseSeconds() {
        return longValue(DEFAULT_BACKOFF_BASE_SECONDS, BACKOFF_BASE_SECONDS_KEY, BACKOFF_BASE_SECONDS_ALIAS);
    }

    boolean llmCleaningEnabled() {
        return booleanValue(false, LLM_ENABLED_KEY, "app.normalization.llm-cleaning.enabled");
    }

    String llmCleaningMode() {
        String mode = value(LLM_CLEANING_MODE_KEY, "app.normalization.llm-cleaning.mode");
        return StringUtils.hasText(mode) ? mode.trim().toLowerCase(Locale.ROOT) : DEFAULT_LLM_CLEANING_MODE;
    }

    String llmModel() {
        String model = value(LLM_MODEL_KEY, "spring.ai.openai.chat.options.model");
        return StringUtils.hasText(model) ? model.trim() : DEFAULT_LLM_MODEL;
    }

    String llmProvider() {
        String provider = value(LLM_PROVIDER_KEY, "app.normalization.llm.provider");
        return StringUtils.hasText(provider) ? provider.trim().toUpperCase(Locale.ROOT) : DEFAULT_LLM_PROVIDER;
    }

    String llmRuntimeApiKey() {
        return valueFromRuntime(LLM_API_KEY_KEY);
    }

    String llmRuntimeBaseUrl() {
        String baseUrl = valueFromRuntime(LLM_BASE_URL_KEY);
        return StringUtils.hasText(baseUrl) ? baseUrl.trim() : DEFAULT_OPENAI_BASE_URL;
    }

    String llmCredentialServiceBaseUrl() {
        return value(LLM_CREDENTIAL_SERVICE_BASE_URL_KEY, LLM_CREDENTIAL_SERVICE_BASE_URL_ALIAS);
    }

    String llmCredentialServiceToken() {
        return valueFromRuntime(LLM_CREDENTIAL_SERVICE_TOKEN_KEY, "app.credential-service.token");
    }

    String llmCredentialServiceId() {
        String value = valueFromRuntime(
                LLM_CREDENTIAL_SERVICE_ID_KEY,
                "APP_NORMALIZATION_LLM_CREDENTIAL_SERVICE_ID",
                "app.credential-service.service-id");
        return StringUtils.hasText(value) ? value.trim() : DEFAULT_SERVICE_ID;
    }

    String llmCredentialServiceScopes() {
        String value = valueFromRuntime(
                LLM_CREDENTIAL_SERVICE_SCOPES_KEY,
                "APP_NORMALIZATION_LLM_CREDENTIAL_SERVICE_SCOPES",
                "app.credential-service.scopes");
        return StringUtils.hasText(value) ? value.trim() : DEFAULT_CREDENTIAL_SERVICE_SCOPES;
    }

    long llmRequestTimeoutSeconds() {
        return Math.max(1L, longValue(
                DEFAULT_LLM_REQUEST_TIMEOUT_SECONDS,
                LLM_REQUEST_TIMEOUT_SECONDS_KEY,
                "app.normalization.llm.request-timeout-seconds"));
    }

    boolean pendingWatchdogEnabled() {
        return booleanValue(true, PENDING_WATCHDOG_ENABLED_KEY);
    }

    int pendingWatchdogBatchSize() {
        return Math.max(1, intValue(
                DEFAULT_PENDING_WATCHDOG_BATCH_SIZE,
                PENDING_WATCHDOG_BATCH_SIZE_KEY,
                "APP_NORMALIZATION_PENDING_WATCHDOG_BATCH_SIZE"));
    }

    Duration pendingWatchdogMinPendingAge() {
        Duration value = durationValue(
                DEFAULT_PENDING_WATCHDOG_MIN_PENDING_AGE,
                PENDING_WATCHDOG_MIN_PENDING_AGE_KEY,
                "APP_NORMALIZATION_PENDING_WATCHDOG_MIN_PENDING_AGE");
        return value.isNegative() ? DEFAULT_PENDING_WATCHDOG_MIN_PENDING_AGE : value;
    }

    Duration pendingWatchdogLeaseTtl() {
        Duration value = durationValue(
                DEFAULT_PENDING_WATCHDOG_LEASE_TTL,
                PENDING_WATCHDOG_LEASE_TTL_KEY,
                "APP_NORMALIZATION_PENDING_WATCHDOG_LEASE_TTL");
        return value.isZero() || value.isNegative() ? DEFAULT_PENDING_WATCHDOG_LEASE_TTL : value;
    }

    private boolean booleanValue(boolean defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "y", "on", "enabled" -> true;
            case "false", "0", "no", "n", "off", "disabled" -> false;
            default -> defaultValue;
        };
    }

    private int intValue(int defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        return parseInt(raw, defaultValue);
    }

    private long longValue(long defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private Duration durationValue(Duration defaultValue, String key, String... aliases) {
        String raw = value(key, aliases);
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        try {
            return DurationStyle.detectAndParse(raw.trim());
        } catch (IllegalArgumentException ignored) {
            return defaultValue;
        }
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

    private String valueFromRuntime(String key) {
        return RuntimeInjectedConfig.find(environment, key).orElse(null);
    }

    private String valueFromRuntime(String key, String... aliases) {
        String[] runtimeKeys = java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(key),
                        Arrays.stream(aliases))
                .toArray(String[]::new);
        return RuntimeInjectedConfig.find(environment, runtimeKeys).orElse(null);
    }

    private long parseLong(String raw, long defaultValue) {
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private int parseInt(String raw, int defaultValue) {
        if (!StringUtils.hasText(raw)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }
}

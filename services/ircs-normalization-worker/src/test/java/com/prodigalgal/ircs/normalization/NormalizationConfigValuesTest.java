package com.prodigalgal.ircs.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class NormalizationConfigValuesTest {

    private final SystemConfigRepository repository = org.mockito.Mockito.mock(SystemConfigRepository.class);

    @Test
    void usesV1DefaultsWhenNoRuntimeOrDbConfigExists() {
        NormalizationConfigValues values = values(new MockEnvironment());

        assertEquals(5, values.maxRetries());
        assertEquals(60L, values.backoffBaseSeconds());
        assertEquals(true, values.pendingWatchdogEnabled());
        assertEquals(200, values.pendingWatchdogBatchSize());
        assertEquals(Duration.ofMinutes(5), values.pendingWatchdogMinPendingAge());
        assertEquals(Duration.ofSeconds(45), values.pendingWatchdogLeaseTtl());
    }

    @Test
    void dbCanonicalKeysOverrideDefaults() {
        when(repository.findValue("normalization.max-retries")).thenReturn(Optional.of("7"));
        when(repository.findValue("normalization.backoff.base-seconds")).thenReturn(Optional.of("90"));
        NormalizationConfigValues values = values(new MockEnvironment());

        assertEquals(7, values.maxRetries());
        assertEquals(90L, values.backoffBaseSeconds());
    }

    @Test
    void pendingWatchdogDbConfigOverridesDefaults() {
        when(repository.findValue("app.normalization.pending-watchdog.enabled")).thenReturn(Optional.of("false"));
        when(repository.findValue("app.normalization.pending-watchdog.batch-size")).thenReturn(Optional.of("50"));
        when(repository.findValue("app.normalization.pending-watchdog.min-pending-age")).thenReturn(Optional.of("PT2M"));
        when(repository.findValue("app.normalization.pending-watchdog.lease-ttl")).thenReturn(Optional.of("PT20S"));
        NormalizationConfigValues values = values(new MockEnvironment());

        assertEquals(false, values.pendingWatchdogEnabled());
        assertEquals(50, values.pendingWatchdogBatchSize());
        assertEquals(Duration.ofMinutes(2), values.pendingWatchdogMinPendingAge());
        assertEquals(Duration.ofSeconds(20), values.pendingWatchdogLeaseTtl());
    }

    @Test
    void runtimeAliasesOverrideDbConfigAndKeepAliasUnits() {
        when(repository.findValue("normalization.max-retries")).thenReturn(Optional.of("7"));
        when(repository.findValue("normalization.backoff.base-seconds")).thenReturn(Optional.of("90"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_NORMALIZATION_MAX_RETRIES", "3")
                .withProperty("APP_NORMALIZATION_BACKOFF_BASE_SECONDS", "12")
                .withProperty("APP_NORMALIZATION_PENDING_WATCHDOG_BATCH_SIZE", "25")
                .withProperty("APP_NORMALIZATION_PENDING_WATCHDOG_MIN_PENDING_AGE", "PT1M")
                .withProperty("APP_NORMALIZATION_PENDING_WATCHDOG_LEASE_TTL", "PT15S");
        NormalizationConfigValues values = values(environment);

        assertEquals(3, values.maxRetries());
        assertEquals(12L, values.backoffBaseSeconds());
        assertEquals(25, values.pendingWatchdogBatchSize());
        assertEquals(Duration.ofMinutes(1), values.pendingWatchdogMinPendingAge());
        assertEquals(Duration.ofSeconds(15), values.pendingWatchdogLeaseTtl());
    }

    @Test
    void invalidValuesFallBackWithoutThrowing() {
        when(repository.findValue("normalization.max-retries")).thenReturn(Optional.of("bad"));
        when(repository.findValue("normalization.backoff.base-seconds")).thenReturn(Optional.of("bad"));
        NormalizationConfigValues values = values(new MockEnvironment());

        assertEquals(5, values.maxRetries());
        assertEquals(60L, values.backoffBaseSeconds());
    }

    @Test
    void llmCleaningDefaultsStaySafeAndDisabled() {
        NormalizationConfigValues values = values(new MockEnvironment());

        assertEquals(false, values.llmCleaningEnabled());
        assertEquals("dry-run", values.llmCleaningMode());
        assertEquals("gemma-4-31b-it", values.llmModel());
        assertEquals("OPENAI", values.llmProvider());
    }

    @Test
    void llmRuntimeConfigOverridesDbSeedAndKeepsSecretRuntimeOnly() {
        when(repository.findValue("app.ai.llm.enabled")).thenReturn(Optional.of("false"));
        when(repository.findValue("app.ai.llm.cleaning.mode")).thenReturn(Optional.of("dry-run"));
        when(repository.findValue("app.ai.llm.model")).thenReturn(Optional.of("db-model"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("APP_AI_LLM_ENABLED", "true")
                .withProperty("APP_AI_LLM_CLEANING_MODE", "fake")
                .withProperty("APP_AI_LLM_MODEL", "runtime-model")
                .withProperty("SPRING_AI_OPENAI_API_KEY", "sk-runtime")
                .withProperty("APP_NORMALIZATION_LLM_CREDENTIAL_SERVICE_TOKEN", "internal-token")
                .withProperty("APP_NORMALIZATION_LLM_CREDENTIAL_SERVICE_ID", "normalizer-a")
                .withProperty("APP_NORMALIZATION_LLM_CREDENTIAL_SERVICE_SCOPES", "credential:lease custom");
        NormalizationConfigValues values = values(environment);

        assertEquals(true, values.llmCleaningEnabled());
        assertEquals("fake", values.llmCleaningMode());
        assertEquals("runtime-model", values.llmModel());
        assertEquals("sk-runtime", values.llmRuntimeApiKey());
        assertEquals("internal-token", values.llmCredentialServiceToken());
        assertEquals("normalizer-a", values.llmCredentialServiceId());
        assertEquals("credential:lease custom", values.llmCredentialServiceScopes());
    }

    private NormalizationConfigValues values(MockEnvironment environment) {
        return new NormalizationConfigValues(environment, repository);
    }
}

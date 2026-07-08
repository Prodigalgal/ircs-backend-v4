package com.prodigalgal.ircs.config.application;


import com.prodigalgal.ircs.config.domain.SystemConfigRecord;
import com.prodigalgal.ircs.config.dto.SystemConfigSummary;
import com.prodigalgal.ircs.config.dto.SystemConfigWriteRequest;
import com.prodigalgal.ircs.config.infrastructure.JdbcConfigRepository;
import com.prodigalgal.ircs.config.infrastructure.SystemConfigChangePublisher;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

class ConfigServiceTest {

    private final JdbcConfigRepository repository = org.mockito.Mockito.mock(JdbcConfigRepository.class);
    private final ConfigValueRedactor redactor = new ConfigValueRedactor();
    private final ConfigConnectivityService connectivityService = org.mockito.Mockito.mock(ConfigConnectivityService.class);
    private final SystemConfigDefaults defaults = new SystemConfigDefaults(NoOpPasswordEncoder.getInstance());
    private final MockEnvironment environment = new MockEnvironment();
    private final SystemConfigChangePublisher changePublisher = org.mockito.Mockito.mock(SystemConfigChangePublisher.class);
    private final ConfigService service =
            new ConfigService(repository, redactor, connectivityService, defaults, environment, changePublisher);

    @Test
    void createsConfigWhenKeyIsNew() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest("codex.config", "enabled", "Codex config");
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "codex.config",
                "enabled",
                "Codex config",
                1L,
                Instant.parse("2026-06-03T00:00:00Z"));
        when(repository.existsByKey("codex.config")).thenReturn(false);
        when(repository.create(request)).thenReturn(record);

        SystemConfigSummary result = service.createConfig(request);

        assertEquals("enabled", result.value());
        verify(repository).create(request);
        verify(changePublisher).publish("codex.config", com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent.Action.CREATED, "DB", 1L, 0L);
    }

    @Test
    void createSummaryKeepsDbValueAboveRuntimeInjection() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest("app.mail.enabled", "false", "Mail enabled");
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "app.mail.enabled",
                "false",
                "Mail enabled",
                1L,
                Instant.parse("2026-06-03T00:00:00Z"));
        environment.setProperty("app.mail.enabled", "true");
        when(repository.existsByKey("app.mail.enabled")).thenReturn(false);
        when(repository.create(request)).thenReturn(record);

        SystemConfigSummary result = service.createConfig(request);

        assertEquals("false", result.value());
        assertEquals("false", result.effectiveValue());
        assertEquals("DB", result.effectiveSource());
        verify(changePublisher).publish(
                "app.mail.enabled",
                com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent.Action.CREATED,
                "DB",
                1L,
                0L);
    }

    @Test
    void rejectsDuplicateConfigKey() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest("codex.config", "enabled", "Codex config");
        when(repository.existsByKey("codex.config")).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.createConfig(request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        verify(changePublisher, never()).publish(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rejectsMismatchedUpdateKey() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest("other.config", "enabled", "Codex config");

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.updateConfig("codex.config", request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void updatesConfigWhenRepositoryFindsRow() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest("codex.config", "disabled", "Codex config");
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "codex.config",
                "disabled",
                "Codex config",
                2L,
                Instant.parse("2026-06-03T00:00:00Z"));
        when(repository.update("codex.config", request)).thenReturn(Optional.of(record));

        Optional<SystemConfigSummary> result = service.updateConfig("codex.config", request);

        assertEquals("disabled", result.orElseThrow().value());
        verify(changePublisher).publish("codex.config", com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent.Action.UPDATED, "DB", 2L, 1L);
    }

    @Test
    void updateSummaryKeepsDbValueAboveRuntimeInjection() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest("app.mail.enabled", "false", "Mail enabled");
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "app.mail.enabled",
                "false",
                "Mail enabled",
                2L,
                Instant.parse("2026-06-03T00:00:00Z"));
        environment.setProperty("app.mail.enabled", "true");
        when(repository.update("app.mail.enabled", request)).thenReturn(Optional.of(record));

        SystemConfigSummary result = service.updateConfig("app.mail.enabled", request).orElseThrow();

        assertEquals("false", result.value());
        assertEquals("false", result.effectiveValue());
        assertEquals("DB", result.effectiveSource());
        verify(changePublisher).publish(
                "app.mail.enabled",
                com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent.Action.UPDATED,
                "DB",
                2L,
                1L);
    }

    @Test
    void publishesDeleteEventWhenConfigRowIsDeleted() {
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "codex.config",
                "enabled",
                "Codex config",
                3L,
                Instant.parse("2026-06-03T00:00:00Z"));
        when(repository.findByKey("codex.config")).thenReturn(Optional.of(record));
        when(repository.deleteByKey("codex.config")).thenReturn(1);

        org.junit.jupiter.api.Assertions.assertEquals(true, service.deleteConfig("codex.config"));

        verify(changePublisher).publish("codex.config", com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent.Action.DELETED, "DB", 4L, 3L);
    }

    @Test
    void exposesDbEffectiveSourceWhenRuntimeInjectionAlsoExists() {
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "app.mail.enabled",
                "false",
                "Mail enabled",
                Instant.parse("2026-06-03T00:00:00Z"));
        environment.setProperty("app.mail.enabled", "true");
        when(repository.findByKey("app.mail.enabled")).thenReturn(Optional.of(record));

        SystemConfigSummary result = service.findConfig("app.mail.enabled").orElseThrow();

        assertEquals("false", result.value());
        assertEquals("false", result.effectiveValue());
        assertEquals("DB", result.effectiveSource());
    }

    @Test
    void exposesDbEffectiveSourceWhenK8sEnvironmentNameAlsoExists() {
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "app.mail.enabled",
                "false",
                "Mail enabled",
                Instant.parse("2026-06-03T00:00:00Z"));
        environment.setProperty("APP_MAIL_ENABLED", "true");
        when(repository.findByKey("app.mail.enabled")).thenReturn(Optional.of(record));

        SystemConfigSummary result = service.findConfig("app.mail.enabled").orElseThrow();

        assertEquals("false", result.value());
        assertEquals("false", result.effectiveValue());
        assertEquals("DB", result.effectiveSource());
    }

    @Test
    void configTreeSecretIsFallbackWhenStoredConfigIsBlank() {
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "app.mail.enabled",
                "",
                "Mail enabled",
                Instant.parse("2026-06-03T00:00:00Z"));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "Config tree '/etc/secrets/ircs/'",
                Map.of("app.mail.enabled", "true")));
        when(repository.findByKey("app.mail.enabled")).thenReturn(Optional.of(record));

        SystemConfigSummary result = service.findConfig("app.mail.enabled").orElseThrow();

        assertEquals("", result.value());
        assertEquals("true", result.effectiveValue());
        assertEquals("INJECTED", result.effectiveSource());
    }

    @Test
    void kubernetesConfigMapIsFallbackWhenStoredConfigIsBlank() {
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "app.mail.enabled",
                "",
                "Mail enabled",
                Instant.parse("2026-06-03T00:00:00Z"));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "Kubernetes ConfigMap ircs-dev/runtime",
                Map.of("APP_MAIL_ENABLED", "true")));
        when(repository.findByKey("app.mail.enabled")).thenReturn(Optional.of(record));

        SystemConfigSummary result = service.findConfig("app.mail.enabled").orElseThrow();

        assertEquals("", result.value());
        assertEquals("true", result.effectiveValue());
        assertEquals("INJECTED", result.effectiveSource());
    }

    @Test
    void kubernetesSecretIsFallbackWhenStoredConfigIsBlank() {
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "app.mail.enabled",
                "",
                "Mail enabled",
                Instant.parse("2026-06-03T00:00:00Z"));
        environment.getPropertySources().addFirst(new MapPropertySource(
                "Kubernetes Secret ircs-dev/runtime",
                Map.of("APP_MAIL_ENABLED", "true")));
        when(repository.findByKey("app.mail.enabled")).thenReturn(Optional.of(record));

        SystemConfigSummary result = service.findConfig("app.mail.enabled").orElseThrow();

        assertEquals("", result.value());
        assertEquals("true", result.effectiveValue());
        assertEquals("INJECTED", result.effectiveSource());
    }

    @Test
    void classpathYamlDefaultDoesNotOverrideStoredConfig() {
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "app.mail.enabled",
                "false",
                "Mail enabled",
                Instant.parse("2026-06-03T00:00:00Z"));
        environment.getPropertySources().addLast(new MapPropertySource(
                "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'",
                Map.of("app.mail.enabled", "true")));
        when(repository.findByKey("app.mail.enabled")).thenReturn(Optional.of(record));

        SystemConfigSummary result = service.findConfig("app.mail.enabled").orElseThrow();

        assertEquals("false", result.value());
        assertEquals("false", result.effectiveValue());
        assertEquals("DB", result.effectiveSource());
    }

    @Test
    void exposesAliasInjectedEffectiveSourceForV3RuntimeProperty() {
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "security.jwt.secret",
                "db-secret",
                "JWT secret",
                Instant.parse("2026-06-03T00:00:00Z"));
        environment.setProperty("app.identity.jwt.secret", "env-secret");
        when(repository.findByKey("security.jwt.secret")).thenReturn(Optional.of(record));

        SystemConfigSummary result = service.findConfig("security.jwt.secret").orElseThrow();

        assertEquals("DB", result.effectiveSource());
        assertEquals(null, result.value());
        assertEquals(null, result.effectiveValue());
        assertEquals("RESTART_REQUIRED", result.activationMode());
        org.junit.jupiter.api.Assertions.assertTrue(result.restartServices().contains("ircs-identity-service"));
    }

    @Test
    void exposesAliasInjectedEffectiveSourceForV3K8sEnvironmentName() {
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "security.jwt.secret",
                "db-secret",
                "JWT secret",
                Instant.parse("2026-06-03T00:00:00Z"));
        environment.setProperty("APP_IDENTITY_JWT_SECRET", "env-secret");
        when(repository.findByKey("security.jwt.secret")).thenReturn(Optional.of(record));

        SystemConfigSummary result = service.findConfig("security.jwt.secret").orElseThrow();

        assertEquals("DB", result.effectiveSource());
        assertEquals(null, result.value());
        assertEquals(null, result.effectiveValue());
    }

    @Test
    void exposesDefaultEffectiveSourceWhenStoredValueIsBlank() {
        SystemConfigRecord record = new SystemConfigRecord(
                UUID.randomUUID(),
                "member.message.daily-limit",
                "",
                "Daily limit",
                Instant.parse("2026-06-03T00:00:00Z"));
        when(repository.findByKey("member.message.daily-limit")).thenReturn(Optional.of(record));

        SystemConfigSummary result = service.findConfig("member.message.daily-limit").orElseThrow();

        assertEquals("", result.value());
        assertEquals("5", result.effectiveValue());
        assertEquals("DEFAULT", result.effectiveSource());
    }

    @Test
    void rejectsLlmPromptWithoutRequiredPlaceholders() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest(
                "app.ai.llm.prompt.language",
                "missing placeholders",
                "LLM prompt");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.createConfig(request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void rejectsStartTlsWhenSslIsAlreadyEnabled() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest(
                "app.mail.properties.starttls",
                "true",
                "STARTTLS");
        when(repository.findValue("app.mail.properties.ssl")).thenReturn(Optional.of("true"));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.updateConfig(
                "app.mail.properties.starttls",
                request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void validatesAgainstStoredConfigBeforeInjectedEnvironment() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest(
                "app.mail.properties.starttls",
                "true",
                "STARTTLS");
        environment.setProperty("app.mail.properties.ssl", "true");
        when(repository.findValue("app.mail.properties.ssl")).thenReturn(Optional.of("false"));

        SystemConfigRecord updated = new SystemConfigRecord(
                UUID.randomUUID(),
                "app.mail.properties.starttls",
                "true",
                "STARTTLS",
                2L,
                Instant.parse("2026-06-03T00:00:00Z"));
        when(repository.update("app.mail.properties.starttls", request)).thenReturn(Optional.of(updated));

        SystemConfigSummary result = service.updateConfig("app.mail.properties.starttls", request).orElseThrow();

        assertEquals("DB", result.effectiveSource());
    }

    @Test
    void validatesAgainstK8sEnvironmentNameWhenStoredConfigIsBlank() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest(
                "app.mail.properties.starttls",
                "true",
                "STARTTLS");
        environment.setProperty("APP_MAIL_PROPERTIES_SSL", "true");
        when(repository.findValue("app.mail.properties.ssl")).thenReturn(Optional.of(""));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.updateConfig(
                "app.mail.properties.starttls",
                request));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void classpathYamlDefaultDoesNotTriggerRuntimeSslStartTlsConflict() {
        SystemConfigWriteRequest request = new SystemConfigWriteRequest(
                "app.mail.properties.starttls",
                "true",
                "STARTTLS");
        environment.getPropertySources().addLast(new MapPropertySource(
                "Config resource 'class path resource [application.yaml]' via location 'optional:classpath:/'",
                Map.of("app.mail.properties.ssl", "true")));
        when(repository.findValue("app.mail.properties.ssl")).thenReturn(Optional.of("false"));
        SystemConfigRecord updated = new SystemConfigRecord(
                UUID.randomUUID(),
                "app.mail.properties.starttls",
                "true",
                "STARTTLS",
                2L,
                Instant.parse("2026-06-03T00:00:00Z"));
        when(repository.update("app.mail.properties.starttls", request)).thenReturn(Optional.of(updated));

        SystemConfigSummary result = service.updateConfig("app.mail.properties.starttls", request).orElseThrow();

        assertEquals("true", result.value());
        assertEquals("DB", result.effectiveSource());
        verify(changePublisher).publish(
                "app.mail.properties.starttls",
                com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent.Action.UPDATED,
                "DB",
                2L,
                1L);
    }

    @Test
    void rejectsDeletingCoreConfig() {
        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> service.deleteConfig("security.jwt.secret"));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void delegatesDryRunConnectionTest() {
        Map<String, Object> params = Map.of("api_key", "secret");
        Map<String, String> result = Map.of("message", "连接成功");
        when(connectivityService.testConnection("LLM", params)).thenReturn(result);

        assertEquals(result, service.testConnection("LLM", params));
    }
}

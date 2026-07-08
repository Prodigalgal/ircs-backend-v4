package com.prodigalgal.ircs.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.cache.CacheRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class CredentialServiceTest {

    private final CredentialRepository repository = org.mockito.Mockito.mock(CredentialRepository.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CacheRegistry cacheRegistry = new CacheRegistry();
    private final CredentialLeaseCache leaseCache =
            new CredentialLeaseCache(cacheRegistry, Duration.ofSeconds(30));
    private final CredentialService service = new CredentialService(
            repository,
            new CredentialSanitizer(objectMapper),
            new CredentialLeaseMapper(objectMapper),
            objectMapper,
            new CredentialProviderCatalog(),
            leaseCache,
            CredentialReadModelCache.disabled());

    @Test
    void cachesRedactedCredentialSummaryListWithoutSecrets() {
        CredentialRecord record = record(UUID.randomUUID(), "TMDB");
        when(repository.findAll("TMDB", true, 25)).thenReturn(List.of(record));
        CredentialService cachedService = cachedService();

        List<CredentialSummary> first = cachedService.list("tmdb", true, 25);
        List<CredentialSummary> second = cachedService.list("tmdb", true, 25);

        assertEquals(first, second);
        assertThat(second.getFirst().payload().toString()).doesNotContain("secret");
        verify(repository, times(1)).findAll("TMDB", true, 25);
    }

    @Test
    void createEvictsCachedCredentialSummaryList() {
        CredentialRecord before = record(UUID.randomUUID(), "TMDB");
        CredentialRecord created = record(UUID.randomUUID(), "TMDB");
        when(repository.findAll("TMDB", true, 25)).thenReturn(List.of(before), List.of(before, created));
        when(repository.existsByFingerprint(any())).thenReturn(false);
        when(repository.create(any())).thenReturn(created);
        CredentialService cachedService = cachedService();

        assertEquals(1, cachedService.list("tmdb", true, 25).size());
        cachedService.create(request());
        assertEquals(2, cachedService.list("tmdb", true, 25).size());

        verify(repository, times(2)).findAll("TMDB", true, 25);
    }

    @Test
    void refreshPoolEvictsCredentialSummaryReadModel() {
        CredentialRecord record = record(UUID.randomUUID(), "TMDB");
        when(repository.findAll("TMDB", true, 25)).thenReturn(List.of(record));
        CredentialService cachedService = cachedService();

        cachedService.list("tmdb", true, 25);
        cachedService.refreshPool();
        cachedService.list("tmdb", true, 25);

        verify(repository, times(2)).findAll("TMDB", true, 25);
    }

    @Test
    void normalizesProviderBeforeQuerying() {
        CredentialRecord record = record(UUID.randomUUID(), "TMDB");
        when(repository.findAll("TMDB", true, 25)).thenReturn(List.of(record));

        List<CredentialSummary> result = service.list(" tmdb ", true, 25);

        assertEquals(1, result.size());
        assertEquals("TMDB", result.getFirst().provider());
        verify(repository).findAll("TMDB", true, 25);
    }

    @Test
    void returnsSanitizedCredentialById() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(record(id, "OPENAI")));

        Optional<CredentialSummary> result = service.findById(id);

        assertEquals("OPENAI", result.orElseThrow().provider());
        assertEquals(List.of("api_key"), result.orElseThrow().payloadKeys());
        assertThat(result.orElseThrow().payload().toString()).doesNotContain("secret");
        assertThat(result.orElseThrow().payload()).containsEntry(
                "api_key",
                Map.of("present", true, "redacted", true));
    }

    @Test
    void leasesEnabledProviderCredentialsWithSecretPayload() {
        CredentialRecord record = record(UUID.randomUUID(), "TMDB");
        when(repository.findEnabledProviderCredentials("TMDB", "api_key", 20)).thenReturn(List.of(record));

        var result = service.leaseProviderCredentials("tmdb", " api_key ", 20);

        assertEquals(1, result.size());
        assertEquals(0L, result.getFirst().getRevision());
        org.assertj.core.api.Assertions.assertThat(result.getFirst().getLeasedAt()).isNotNull();
        org.assertj.core.api.Assertions.assertThat(result.getFirst().getExpiresAt()).isAfter(result.getFirst().getLeasedAt());
        assertEquals("secret", result.getFirst().getSecretPayload().get("api_key"));
        verify(repository).findEnabledProviderCredentials("TMDB", "api_key", 20);
    }

    @Test
    void cachesProviderCredentialLeasesUntilEvicted() {
        CredentialRecord record = record(UUID.randomUUID(), "TMDB");
        when(repository.findEnabledProviderCredentials("TMDB", "api_key", 20)).thenReturn(List.of(record));

        service.leaseProviderCredentials("tmdb", "api_key", 20);
        service.leaseProviderCredentials("tmdb", "api_key", 20);

        verify(repository, times(1)).findEnabledProviderCredentials("TMDB", "api_key", 20);
        assertThat(cacheRegistry.summary(CredentialLeaseCache.CACHE_NAME).orElseThrow().hits()).isEqualTo(1L);
        assertThat(cacheRegistry.summary(CredentialLeaseCache.CACHE_NAME).orElseThrow().misses()).isEqualTo(1L);
    }

    @Test
    void createEvictsCachedProviderLeases() {
        CredentialRecord record = record(UUID.randomUUID(), "TMDB");
        when(repository.findEnabledProviderCredentials("TMDB", "api_key", 20)).thenReturn(List.of(record));
        when(repository.existsByFingerprint(any())).thenReturn(false);
        when(repository.create(any())).thenReturn(record(UUID.randomUUID(), "TMDB"));

        service.leaseProviderCredentials("tmdb", "api_key", 20);
        service.create(request());
        service.leaseProviderCredentials("tmdb", "api_key", 20);

        verify(repository, times(2)).findEnabledProviderCredentials("TMDB", "api_key", 20);
        assertThat(cacheRegistry.summary(CredentialLeaseCache.CACHE_NAME).orElseThrow().evictions()).isEqualTo(1L);
    }

    @Test
    void leasesDoubanCredentialsWithoutLoggingSecrets() {
        CredentialRecord record = record(
                UUID.randomUUID(),
                "DOUBAN",
                "{\"cookie\":\"dbcl2=secret\",\"user_agent\":\"Mozilla\"}");
        when(repository.findEnabledProviderCredentials("DOUBAN", null, 20)).thenReturn(List.of(record));

        var result = service.leaseProviderCredentials("douban", null, 20);

        assertEquals(1, result.size());
        assertEquals("dbcl2=secret", result.getFirst().getSecretPayload().get("cookie"));
        assertEquals("Mozilla", result.getFirst().getSecretPayload().get("user_agent"));
        verify(repository).findEnabledProviderCredentials("DOUBAN", null, 20);
    }

    @Test
    void leasesRottenTomatoesCredentialsWithoutLoggingSecrets() {
        CredentialRecord record = record(
                UUID.randomUUID(),
                "ROTTEN_TOMATOES",
                "{\"cookie\":\"rt=secret\",\"user_agent\":\"Mozilla\"}");
        when(repository.findEnabledProviderCredentials("ROTTEN_TOMATOES", null, 20)).thenReturn(List.of(record));

        var result = service.leaseProviderCredentials("rotten_tomatoes", null, 20);

        assertEquals(1, result.size());
        assertEquals("rt=secret", result.getFirst().getSecretPayload().get("cookie"));
        assertEquals("Mozilla", result.getFirst().getSecretPayload().get("user_agent"));
        verify(repository).findEnabledProviderCredentials("ROTTEN_TOMATOES", null, 20);
    }

    @Test
    void capsProviderCredentialLeaseLimitAtServiceBoundary() {
        CredentialRecord record = record(UUID.randomUUID(), "TMDB");
        when(repository.findEnabledProviderCredentials("TMDB", "api_key", 100)).thenReturn(List.of(record));

        var result = service.leaseProviderCredentials("tmdb", "api_key", 250);

        assertEquals(1, result.size());
        verify(repository).findEnabledProviderCredentials("TMDB", "api_key", 100);
    }

    @Test
    void zeroProviderCredentialLeaseLimitSkipsRepository() {
        var result = service.leaseProviderCredentials("tmdb", "api_key", 0);

        assertEquals(List.of(), result);
        verify(repository, never()).findEnabledProviderCredentials(any(), any(), any(Integer.class));
    }

    @Test
    void leasesMailCredentialsOnlyWhenUsernameAndPasswordExist() {
        CredentialRecord complete = record(
                UUID.randomUUID(),
                "MAIL",
                "{\"username\":\"mail@example.invalid\",\"password\":\"secret\",\"smtp_host\":\"smtp.mail.example.invalid\"}");
        CredentialRecord missingPassword = record(
                UUID.randomUUID(),
                "MAIL",
                "{\"username\":\"mail@example.invalid\"}");
        when(repository.findEnabledProviderCredentials("MAIL", "username", 100))
                .thenReturn(List.of(complete, missingPassword));

        var result = service.leaseMailCredentials(250);

        assertEquals(1, result.size());
        assertEquals("mail@example.invalid", result.getFirst().getSecretPayload().get("username"));
        assertEquals("secret", result.getFirst().getSecretPayload().get("password"));
        assertEquals("smtp.mail.example.invalid", result.getFirst().getSecretPayload().get("smtp_host"));
        verify(repository).findEnabledProviderCredentials("MAIL", "username", 100);
    }

    @Test
    void returnsProviderTemplates() {
        List<CredentialTemplateField> result = service.templates("openai");

        assertEquals(List.of("api_key", "base_url"), result.stream().map(CredentialTemplateField::key).toList());
    }

    @Test
    void returnsDoubanProviderTemplate() {
        List<CredentialTemplateField> result = service.templates("douban");

        assertEquals(List.of("cookie", "user_agent"), result.stream().map(CredentialTemplateField::key).toList());
    }

    @Test
    void returnsRottenTomatoesProviderTemplate() {
        List<CredentialTemplateField> result = service.templates("rotten_tomatoes");

        assertEquals(List.of("cookie", "user_agent"), result.stream().map(CredentialTemplateField::key).toList());
    }

    @Test
    void returnsMailProviderTemplateWithBoundSmtpFields() {
        List<CredentialTemplateField> result = service.templates("mail");

        assertEquals(
                List.of(
                        "username",
                        "password",
                        "smtp_host",
                        "smtp_port",
                        "smtp_protocol",
                        "smtp_auth",
                        "smtp_ssl_enabled",
                        "smtp_starttls_enabled",
                        "smtp_timeout_ms"),
                result.stream().map(CredentialTemplateField::key).toList());
    }

    @Test
    void mailSummaryExposesOnlyNonSecretSmtpPayload() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.of(record(
                id,
                "MAIL",
                """
                {"username":"mail@example.invalid","password":"secret","smtp_host":"smtp.mail.example.invalid","smtp_port":587}
                """)));

        CredentialSummary summary = service.findById(id).orElseThrow();

        assertThat(summary.payload()).containsEntry("username", "mail@example.invalid")
                .containsEntry("smtp_host", "smtp.mail.example.invalid")
                .containsEntry("smtp_port", 587);
        assertThat(summary.payload().get("password")).isEqualTo(Map.of("present", true, "redacted", true));
    }

    @Test
    void createsCredentialWithNormalizedProviderAndFingerprint() {
        CredentialWriteRequest request = request();
        UUID savedId = UUID.randomUUID();
        when(repository.existsByFingerprint(any())).thenReturn(false);
        when(repository.create(any())).thenReturn(record(savedId, "TMDB"));

        CredentialSummary result = service.create(request);

        assertEquals(savedId, result.id());
        assertThat(result.payload().toString()).doesNotContain("secret");
        ArgumentCaptor<CredentialDraft> captor = ArgumentCaptor.forClass(CredentialDraft.class);
        verify(repository).create(captor.capture());
        CredentialDraft draft = captor.getValue();
        assertEquals("TMDB", draft.provider());
        assertEquals("MINUTE", draft.rateLimitUnit());
        Assertions.assertTrue(draft.payloadJson().contains("api_key"));
        Assertions.assertEquals(64, draft.fingerprint().length());
    }

    @Test
    void createsDoubanCredentialWithOptionalHeaderFingerprint() {
        CredentialWriteRequest request = new CredentialWriteRequest(
                " douban ",
                "dev",
                Map.of("cookie", "dbcl2=secret", "user_agent", "Mozilla"),
                true,
                1,
                1,
                "minute",
                0L,
                0L,
                0L,
                0L,
                "remark");
        UUID savedId = UUID.randomUUID();
        when(repository.existsByFingerprint(any())).thenReturn(false);
        when(repository.create(any())).thenReturn(record(
                savedId,
                "DOUBAN",
                "{\"cookie\":\"dbcl2=secret\",\"user_agent\":\"Mozilla\"}"));

        CredentialSummary result = service.create(request);

        assertEquals(savedId, result.id());
        assertThat(result.payload().toString()).doesNotContain("dbcl2=secret");
        ArgumentCaptor<CredentialDraft> captor = ArgumentCaptor.forClass(CredentialDraft.class);
        verify(repository).create(captor.capture());
        CredentialDraft draft = captor.getValue();
        assertEquals("DOUBAN", draft.provider());
        assertEquals("MINUTE", draft.rateLimitUnit());
        Assertions.assertEquals(64, draft.fingerprint().length());
    }

    @Test
    void createsRottenTomatoesCredentialWithOptionalHeaderFingerprint() {
        CredentialWriteRequest request = new CredentialWriteRequest(
                " rotten_tomatoes ",
                "dev",
                Map.of("cookie", "rt=secret", "user_agent", "Mozilla"),
                true,
                1,
                1,
                "minute",
                0L,
                0L,
                0L,
                0L,
                "remark");
        UUID savedId = UUID.randomUUID();
        when(repository.existsByFingerprint(any())).thenReturn(false);
        when(repository.create(any())).thenReturn(record(
                savedId,
                "ROTTEN_TOMATOES",
                "{\"cookie\":\"rt=secret\",\"user_agent\":\"Mozilla\"}"));

        CredentialSummary result = service.create(request);

        assertEquals(savedId, result.id());
        assertThat(result.payload().toString()).doesNotContain("rt=secret");
        ArgumentCaptor<CredentialDraft> captor = ArgumentCaptor.forClass(CredentialDraft.class);
        verify(repository).create(captor.capture());
        CredentialDraft draft = captor.getValue();
        assertEquals("ROTTEN_TOMATOES", draft.provider());
        assertEquals("MINUTE", draft.rateLimitUnit());
        Assertions.assertEquals(64, draft.fingerprint().length());
    }

    @Test
    void rejectsEmptyDoubanCredentialPayload() {
        CredentialWriteRequest request = new CredentialWriteRequest(
                "DOUBAN",
                "dev",
                Map.of("cookie", " ", "user_agent", ""),
                true,
                1,
                1,
                "MINUTE",
                0L,
                0L,
                0L,
                0L,
                "remark");

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
        verify(repository, never()).create(any());
    }

    @Test
    void rejectsEmptyRottenTomatoesCredentialPayload() {
        CredentialWriteRequest request = new CredentialWriteRequest(
                "ROTTEN_TOMATOES",
                "dev",
                Map.of("cookie", " ", "user_agent", ""),
                true,
                1,
                1,
                "MINUTE",
                0L,
                0L,
                0L,
                0L,
                "remark");

        assertThrows(IllegalArgumentException.class, () -> service.create(request));
        verify(repository, never()).create(any());
    }

    @Test
    void rejectsDuplicateCredentialOnCreate() {
        when(repository.existsByFingerprint(any())).thenReturn(true);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> service.create(request()));

        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void createIfAbsentSkipsDuplicateFingerprintWithoutWriting() {
        when(repository.existsByFingerprint(any())).thenReturn(true);

        boolean inserted = service.createIfAbsent(request());

        org.assertj.core.api.Assertions.assertThat(inserted).isFalse();
        verify(repository, never()).create(any());
    }

    @Test
    void createIfAbsentCreatesWhenFingerprintIsMissing() {
        when(repository.existsByFingerprint(any())).thenReturn(false);
        when(repository.create(any())).thenReturn(record(UUID.randomUUID(), "TMDB"));

        boolean inserted = service.createIfAbsent(request());

        org.assertj.core.api.Assertions.assertThat(inserted).isTrue();
        verify(repository).create(any());
    }

    @Test
    void updatesCredentialWhenIdExists() {
        UUID id = UUID.randomUUID();
        when(repository.existsByFingerprintAndIdNot(any(), any())).thenReturn(false);
        when(repository.update(any(), any())).thenReturn(Optional.of(record(id, "TMDB")));

        Optional<CredentialSummary> result = service.update(id, request());

        assertEquals(id, result.orElseThrow().id());
        verify(repository).update(any(), any());
    }

    private CredentialRecord record(UUID id, String provider) {
        return record(id, provider, "{\"api_key\":\"secret\"}");
    }

    private CredentialRecord record(UUID id, String provider, String payloadJson) {
        return new CredentialRecord(
                id,
                Instant.parse("2026-06-03T00:00:00Z"),
                Instant.parse("2026-06-03T00:00:00Z"),
                provider,
                "dev",
                payloadJson,
                "abcdef1234567890",
                true,
                1,
                30,
                "MINUTE",
                0L,
                0L,
                0L,
                0L,
                "remark");
    }

    private CredentialWriteRequest request() {
        return new CredentialWriteRequest(
                " tmdb ",
                "dev",
                Map.of("api_key", "secret"),
                true,
                1,
                30,
                "minute",
                0L,
                0L,
                0L,
                0L,
                "remark");
    }

    private CredentialService cachedService() {
        ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
        CacheRegistry registry = new CacheRegistry();
        CredentialLeaseCache cachedLeaseCache =
                new CredentialLeaseCache(registry, Duration.ofSeconds(30));
        CredentialReadModelCache readModelCache = new CredentialReadModelCache(
                mapper,
                registry,
                null,
                null,
                true,
                Duration.ofSeconds(60));
        return new CredentialService(
                repository,
                new CredentialSanitizer(mapper),
                new CredentialLeaseMapper(mapper),
                mapper,
                new CredentialProviderCatalog(),
                cachedLeaseCache,
                readModelCache);
    }
}

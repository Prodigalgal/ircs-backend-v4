package com.prodigalgal.ircs.normalization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class LlmCleaningServiceTest {

    @Mock
    private NormalizationConfigValues configValues;

    @Mock
    private LlmCleaningRepository repository;

    @Mock
    private OpenAiCompatibleLlmCleaningClient liveClient;

    @Mock
    private LlmCredentialResolver credentialResolver;

    @Test
    void disabledGateSkipsBeforeReadingQueuedCandidates() {
        LlmCleaningService service = service(new FakeLlmCleaningClient());

        LlmCleaningService.LlmCleaningResult result =
                service.cleanQueued(LlmCleaningKind.GENRE, List.of(UUID.randomUUID()));

        assertEquals("SKIPPED", result.status());
        assertEquals("PROVIDER_DISABLED", result.reason());
        verify(repository, never()).findCandidate(any(), any());
    }

    @Test
    void dryRunSkipsWithoutTouchingQueuedCandidates() {
        when(configValues.llmCleaningEnabled()).thenReturn(true);
        when(configValues.llmCleaningMode()).thenReturn("dry-run");
        LlmCleaningService service = service(new FakeLlmCleaningClient());

        LlmCleaningService.LlmCleaningResult result =
                service.cleanQueued(LlmCleaningKind.LANGUAGE, List.of(UUID.randomUUID()));

        assertEquals("SKIPPED", result.status());
        assertEquals("DRY_RUN_SKIPPED", result.reason());
        verify(repository, never()).findCandidate(any(), any());
    }

    @Test
    void fakeModeTouchesQueuedCandidatesMapsStandardsAndHandlesNoise() {
        UUID actionId = UUID.randomUUID();
        UUID noiseId = UUID.randomUUID();
        UUID standardId = UUID.randomUUID();
        when(configValues.llmCleaningEnabled()).thenReturn(true);
        when(configValues.llmCleaningMode()).thenReturn("fake");
        when(configValues.llmModel()).thenReturn("fake-model");
        when(repository.findCandidate(LlmCleaningKind.GENRE, actionId))
                .thenReturn(Optional.of(new LlmCleaningCandidate(actionId, "动作")));
        when(repository.findCandidate(LlmCleaningKind.GENRE, noiseId))
                .thenReturn(Optional.of(new LlmCleaningCandidate(noiseId, "unknown-noise")));
        when(repository.findStandards(LlmCleaningKind.GENRE))
                .thenReturn(List.of(new LlmCleaningStandard(standardId, "动作")));
        when(repository.applyMatch(LlmCleaningKind.GENRE, actionId, standardId)).thenReturn(true);
        when(repository.applyNoise(LlmCleaningKind.GENRE, noiseId)).thenReturn(true);
        LlmCleaningService service = service(new FakeLlmCleaningClient());

        LlmCleaningService.LlmCleaningResult result =
                service.cleanQueued(LlmCleaningKind.GENRE, List.of(actionId, noiseId, actionId));

        assertEquals("SUCCESS", result.status());
        assertEquals(2, result.candidates());
        assertEquals(1, result.mapped());
        assertEquals(1, result.noise());
        verify(repository).applyMatch(LlmCleaningKind.GENRE, actionId, standardId);
        verify(repository).applyNoise(LlmCleaningKind.GENRE, noiseId);
        verify(credentialResolver, never()).resolve();
        verify(liveClient, never()).analyzeAndMap(any(), any(), any(), any());
    }

    @Test
    void fakeModeDoesNotCountRepositoryRefusedManualMappingOverwrite() {
        UUID rawId = UUID.randomUUID();
        UUID standardId = UUID.randomUUID();
        when(configValues.llmCleaningEnabled()).thenReturn(true);
        when(configValues.llmCleaningMode()).thenReturn("fake");
        when(configValues.llmModel()).thenReturn("fake-model");
        when(repository.findCandidate(LlmCleaningKind.AREA, rawId))
                .thenReturn(Optional.of(new LlmCleaningCandidate(rawId, "中国大陆")));
        when(repository.findStandards(LlmCleaningKind.AREA))
                .thenReturn(List.of(new LlmCleaningStandard(standardId, "中国大陆")));
        when(repository.applyMatch(LlmCleaningKind.AREA, rawId, standardId)).thenReturn(false);
        LlmCleaningService service = service(new FakeLlmCleaningClient());

        LlmCleaningService.LlmCleaningResult result = service.cleanQueued(LlmCleaningKind.AREA, List.of(rawId));

        assertEquals("SUCCESS", result.status());
        assertEquals(1, result.candidates());
        assertEquals(0, result.mapped());
        assertEquals(0, result.noise());
    }

    @Test
    void liveModeReportsMissingCredentialBeforeProviderCall() {
        UUID rawId = UUID.randomUUID();
        when(configValues.llmCleaningEnabled()).thenReturn(true);
        when(configValues.llmCleaningMode()).thenReturn("live");
        when(repository.findCandidate(LlmCleaningKind.AREA, rawId))
                .thenReturn(Optional.of(new LlmCleaningCandidate(rawId, "中国大陆")));
        when(repository.findStandards(LlmCleaningKind.AREA))
                .thenReturn(List.of(new LlmCleaningStandard(UUID.randomUUID(), "中国大陆")));
        when(credentialResolver.resolve()).thenReturn(Optional.empty());
        LlmCleaningService service = service(new FakeLlmCleaningClient());

        LlmCleaningService.LlmCleaningResult result = service.cleanQueued(LlmCleaningKind.AREA, List.of(rawId));

        assertEquals("SKIPPED", result.status());
        assertEquals("CREDENTIAL_MISSING", result.reason());
        verify(liveClient, never()).analyzeAndMap(any(), any(), any(), any());
    }

    @Test
    void liveModeMapsProviderTimeoutToFixedReason() {
        UUID rawId = UUID.randomUUID();
        LlmCredential credential = new LlmCredential("sk-test", "https://llm.example.test/v1", "model", "runtime");
        when(configValues.llmCleaningEnabled()).thenReturn(true);
        when(configValues.llmCleaningMode()).thenReturn("live");
        when(repository.findCandidate(LlmCleaningKind.CATEGORY, rawId))
                .thenReturn(Optional.of(new LlmCleaningCandidate(rawId, "电影")));
        when(repository.findStandards(LlmCleaningKind.CATEGORY))
                .thenReturn(List.of(new LlmCleaningStandard(UUID.randomUUID(), "电影")));
        when(credentialResolver.resolve()).thenReturn(Optional.of(credential));
        when(liveClient.analyzeAndMap(
                        any(),
                        org.mockito.ArgumentMatchers.<Set<String>>any(),
                        eq(LlmCleaningKind.CATEGORY),
                        eq(credential)))
                .thenThrow(new LlmCleaningException.ProviderTimeout("timeout", null));
        LlmCleaningService service = service(new FakeLlmCleaningClient());

        LlmCleaningService.LlmCleaningResult result = service.cleanQueued(LlmCleaningKind.CATEGORY, List.of(rawId));

        assertEquals("SKIPPED", result.status());
        assertEquals("PROVIDER_TIMEOUT", result.reason());
    }

    @Test
    void credentialToStringRedactsApiKey() {
        LlmCredential credential = new LlmCredential("sk-secret-value", "https://llm.example.test/v1", "model", "runtime");

        String printed = credential.toString();

        org.assertj.core.api.Assertions.assertThat(printed).doesNotContain("sk-secret-value");
        org.assertj.core.api.Assertions.assertThat(printed).contains("[redacted]");
    }

    private LlmCleaningService service(FakeLlmCleaningClient fakeClient) {
        return new LlmCleaningService(
                configValues,
                repository,
                fakeClient,
                liveClient,
                credentialResolver);
    }
}

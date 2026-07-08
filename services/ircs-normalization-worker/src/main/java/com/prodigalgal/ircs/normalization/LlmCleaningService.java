package com.prodigalgal.ircs.normalization;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@Slf4j
class LlmCleaningService {

    private final NormalizationConfigValues configValues;
    private final LlmCleaningRepository repository;
    private final FakeLlmCleaningClient fakeClient;
    private final OpenAiCompatibleLlmCleaningClient liveClient;
    private final LlmCredentialResolver credentialResolver;

    LlmCleaningService(
            NormalizationConfigValues configValues,
            LlmCleaningRepository repository,
            FakeLlmCleaningClient fakeClient,
            OpenAiCompatibleLlmCleaningClient liveClient,
            LlmCredentialResolver credentialResolver) {
        this.configValues = configValues;
        this.repository = repository;
        this.fakeClient = fakeClient;
        this.liveClient = liveClient;
        this.credentialResolver = credentialResolver;
    }

    LlmCleaningResult cleanQueued(LlmCleaningKind kind, List<UUID> rawIds) {
        if (!configValues.llmCleaningEnabled()) {
            return LlmCleaningResult.skipped(kind, "PROVIDER_DISABLED");
        }

        String mode = configValues.llmCleaningMode();
        if ("dry-run".equals(mode) || "dryrun".equals(mode)) {
            return LlmCleaningResult.skipped(kind, "DRY_RUN_SKIPPED");
        }

        List<LlmCleaningCandidate> candidates = rawIds == null
                ? List.of()
                : rawIds.stream()
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .map(rawId -> repository.findCandidate(kind, rawId))
                        .flatMap(Optional::stream)
                        .toList();
        if (candidates.isEmpty()) {
            return new LlmCleaningResult(kind, "SUCCESS", 0, 0, 0, "NO_CANDIDATES");
        }

        List<LlmCleaningStandard> standards = repository.findStandards(kind);
        Map<String, LlmCleaningStandard> standardsByName = standards.stream()
                .filter(standard -> StringUtils.hasText(standard.name()))
                .collect(Collectors.toMap(
                        standard -> standard.name().trim(),
                        Function.identity(),
                        (left, right) -> left));
        Map<String, LlmCleaningCandidate> candidatesByRaw = candidates.stream()
                .filter(candidate -> StringUtils.hasText(candidate.rawValue()))
                .collect(Collectors.toMap(
                        candidate -> candidate.rawValue().trim(),
                        Function.identity(),
                        (left, right) -> left));

        try {
            LlmCleaningClient client = resolveClient(mode);
            LlmCredential credential = credential(mode).orElse(null);
            if ("live".equals(mode) && credential == null) {
                return LlmCleaningResult.skipped(kind, "CREDENTIAL_MISSING");
            }
            List<LlmCleaningDecision> decisions = client.analyzeAndMap(
                    candidates.stream().map(LlmCleaningCandidate::rawValue).toList(),
                    standardsByName.keySet(),
                    kind,
                    credential);
            return applyDecisions(kind, candidates.size(), candidatesByRaw, standardsByName, decisions);
        } catch (LlmCleaningException.ProviderTimeout ex) {
            log.warn("LLM cleaning {} skipped by provider timeout: {}", kind, ex.getMessage());
            return LlmCleaningResult.skipped(kind, "PROVIDER_TIMEOUT");
        } catch (LlmCleaningException.ProviderError | IllegalArgumentException ex) {
            log.warn("LLM cleaning {} skipped by provider error: {}", kind, ex.getMessage());
            return LlmCleaningResult.skipped(kind, "PROVIDER_ERROR");
        }
    }

    private LlmCleaningClient resolveClient(String mode) {
        return switch (mode) {
            case "fake" -> fakeClient;
            case "live" -> liveClient;
            default -> throw new IllegalArgumentException("Unsupported LLM cleaning mode: " + mode);
        };
    }

    private Optional<LlmCredential> credential(String mode) {
        if (!"live".equals(mode)) {
            return Optional.of(new LlmCredential(null, null, configValues.llmModel(), "fake"));
        }
        return credentialResolver.resolve();
    }

    private LlmCleaningResult applyDecisions(
            LlmCleaningKind kind,
            int candidates,
            Map<String, LlmCleaningCandidate> candidatesByRaw,
            Map<String, LlmCleaningStandard> standardsByName,
            List<LlmCleaningDecision> decisions) {
        int mapped = 0;
        int noise = 0;
        for (LlmCleaningDecision decision : decisions) {
            LlmCleaningCandidate candidate = resolveCandidate(candidatesByRaw, decision.raw());
            if (candidate == null) {
                continue;
            }
            if (decision.noise()) {
                if (repository.applyNoise(kind, candidate.id())) {
                    noise++;
                }
                continue;
            }
            if (!StringUtils.hasText(decision.standard())) {
                continue;
            }
            LlmCleaningStandard standard = standardsByName.get(decision.standard().trim());
            if (standard == null) {
                continue;
            }
            if (repository.applyMatch(kind, candidate.id(), standard.id())) {
                mapped++;
            }
        }
        return new LlmCleaningResult(kind, "SUCCESS", candidates, mapped, noise, "CLEANED");
    }

    private LlmCleaningCandidate resolveCandidate(Map<String, LlmCleaningCandidate> candidatesByRaw, String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        LlmCleaningCandidate exact = candidatesByRaw.get(raw);
        if (exact != null) {
            return exact;
        }
        return candidatesByRaw.get(raw.trim());
    }

    record LlmCleaningResult(
            LlmCleaningKind kind,
            String status,
            int candidates,
            int mapped,
            int noise,
            String reason) {

        static LlmCleaningResult skipped(LlmCleaningKind kind, String reason) {
            return new LlmCleaningResult(kind, "SKIPPED", 0, 0, 0, reason);
        }
    }
}

package com.prodigalgal.ircs.normalization;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class FakeLlmCleaningClient implements LlmCleaningClient {

    @Override
    public List<LlmCleaningDecision> analyzeAndMap(
            List<String> rawItems,
            Set<String> validStandardItems,
            LlmCleaningKind kind,
            LlmCredential credential) {
        Map<String, String> normalizedStandards = validStandardItems.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.toMap(this::normalize, Function.identity(), (left, right) -> left));
        return rawItems.stream()
                .filter(StringUtils::hasText)
                .map(raw -> decision(raw, normalizedStandards))
                .toList();
    }

    private LlmCleaningDecision decision(String raw, Map<String, String> normalizedStandards) {
        String normalized = normalize(raw);
        if (isNoise(normalized)) {
            return new LlmCleaningDecision(raw, null, true);
        }
        String standard = normalizedStandards.get(normalized);
        if (standard != null) {
            return new LlmCleaningDecision(raw, standard, false);
        }
        return new LlmCleaningDecision(raw, null, false);
    }

    private boolean isNoise(String normalized) {
        return normalized.isBlank()
                || normalized.contains("noise")
                || normalized.contains("unknown")
                || normalized.contains("广告")
                || normalized.contains("其他片源")
                || normalized.equals("n/a")
                || normalized.equals("na");
    }

    private String normalize(String value) {
        String text = Normalizer.normalize(value == null ? "" : value.trim(), Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return text.replaceAll("[\\s_\\-:/|，,。·.]+", "");
    }
}

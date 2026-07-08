package com.prodigalgal.ircs.normalization;

import java.util.Arrays;
import java.util.Locale;

enum LlmCleaningKind {
    LANGUAGE,
    AREA,
    GENRE,
    CATEGORY;

    static LlmCleaningKind parse(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("LLM cleaning kind must not be blank");
        }
        String normalized = value.trim()
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(kind -> kind.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unsupported LLM cleaning kind: " + value));
    }
}

package com.prodigalgal.ircs.common.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.util.StringUtils;

public final class JsonStringArrays {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private JsonStringArrays() {
    }

    public static Set<String> readSet(ObjectMapper objectMapper, String json) {
        return readSet(objectMapper, json, 0);
    }

    private static Set<String> readSet(ObjectMapper objectMapper, String json, int depth) {
        if (!StringUtils.hasText(json)) {
            return Set.of();
        }
        try {
            return normalize(objectMapper.readValue(json, STRING_LIST));
        } catch (Exception ignored) {
            if (depth == 0) {
                try {
                    String nestedJson = objectMapper.readValue(json, String.class);
                    return readSet(objectMapper, nestedJson, depth + 1);
                } catch (Exception nestedIgnored) {
                    return Set.of();
                }
            }
            return Set.of();
        }
    }

    public static List<String> readList(ObjectMapper objectMapper, String json) {
        return List.copyOf(readSet(objectMapper, json));
    }

    public static String write(ObjectMapper objectMapper, Collection<String> values) {
        try {
            return objectMapper.writeValueAsString(normalize(values));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize JSON string array", ex);
        }
    }

    public static Set<String> normalize(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                normalized.add(value.trim());
            }
        }
        return Collections.unmodifiableSet(normalized);
    }
}

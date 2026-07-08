package com.prodigalgal.ircs.common.normalization;

import java.util.Locale;
import java.util.UUID;
import org.springframework.util.StringUtils;

public final class LlmCleaningWorkTypes {

    public static final String RAW_TERM = "normalization.llm-cleaning";

    private LlmCleaningWorkTypes() {
    }

    public static String taskId(String kind, UUID rawId) {
        if (!StringUtils.hasText(kind)) {
            throw new IllegalArgumentException("kind is required");
        }
        if (rawId == null) {
            throw new IllegalArgumentException("rawId is required");
        }
        return kind.trim().toLowerCase(Locale.ROOT) + ":" + rawId;
    }
}

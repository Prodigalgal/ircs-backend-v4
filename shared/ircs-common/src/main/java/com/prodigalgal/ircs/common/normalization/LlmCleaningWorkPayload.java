package com.prodigalgal.ircs.common.normalization;

import java.util.UUID;

public record LlmCleaningWorkPayload(
        String kind,
        UUID rawId,
        String rawValue,
        String sourceService,
        String reason) {
}

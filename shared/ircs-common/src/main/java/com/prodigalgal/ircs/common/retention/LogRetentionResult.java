package com.prodigalgal.ircs.common.retention;

import java.time.Duration;
import java.time.Instant;

public record LogRetentionResult(
        String targetId,
        Instant cutoff,
        Duration retention,
        long deletedCount) {
}

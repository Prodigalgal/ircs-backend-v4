package com.prodigalgal.ircs.ops.restart.dto;

import java.time.Instant;
import java.util.List;

public record ServiceRestartResponse(
        Instant requestedAt,
        String namespace,
        List<ServiceRestartResult> results) {

    public boolean accepted() {
        return results.stream().allMatch(ServiceRestartResult::accepted);
    }
}

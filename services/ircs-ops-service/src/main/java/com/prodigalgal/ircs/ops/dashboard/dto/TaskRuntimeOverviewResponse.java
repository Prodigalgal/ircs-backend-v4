package com.prodigalgal.ircs.ops.dashboard.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record TaskRuntimeOverviewResponse(
        Instant refreshedAt,
        int requestedLimit,
        int activeMasterCount,
        int returnedMasterCount,
        long dirtyMasterCount,
        long pageScheduled,
        long pageDiscovered,
        long pageCompleted,
        long pageFailed,
        long detailScheduled,
        long detailCompleted,
        long detailSucceeded,
        long detailFailed,
        long detailBacklog,
        Map<String, Long> statusCounts,
        Map<String, Long> attentionCounts,
        List<TaskRuntimeOverviewItemResponse> activeMasters
) {
}

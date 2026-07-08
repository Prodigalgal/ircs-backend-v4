package com.prodigalgal.ircs.task.dto;

import com.prodigalgal.ircs.contracts.task.TaskMasterSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TaskRuntimeDetailResponse(
        UUID masterTaskId,
        TaskDetailSummary task,
        TaskMasterSnapshot snapshot,
        TaskMasterRuntimeSummary master,
        List<TaskPageRuntimeSummary> pages,
        int pageOffset,
        int pageLimit,
        int visiblePageCount,
        int totalPageCount,
        boolean redisSnapshotPresent,
        boolean redisStatePresent,
        Instant refreshedAt
) {
}

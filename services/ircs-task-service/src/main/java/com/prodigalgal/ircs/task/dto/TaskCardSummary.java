package com.prodigalgal.ircs.task.dto;

import java.time.Instant;
import java.util.UUID;

public record TaskCardSummary(
        UUID id,
        String name,
        String status,
        Boolean enabled,
        String dataSourceName,
        String cronExpression,
        String timeZone,
        String filterType,
        Integer filterHours,
        String filterKeywords,
        Integer startPage,
        Integer endPage,
        Integer currentPage,
        Instant lastExecutionTime,
        Long statProcessed,
        Long statTotalFound,
        Long statSuccess,
        Long statFailed,
        Long statInserted,
        Long statUpdated,
        Long statIgnored,
        Instant statStartTime,
        Instant statEndTime
) {
}

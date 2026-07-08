package com.prodigalgal.ircs.task.infrastructure;

import java.util.List;

public record ScraperTaskExecutionResult(
        String status,
        Integer publishedCount,
        Integer failedCount,
        List<ScraperTaskExecutionLog> logs) {

    public boolean successful() {
        return "COMPLETED".equalsIgnoreCase(status) && failed() == 0;
    }

    public int published() {
        return publishedCount == null ? 0 : publishedCount;
    }

    public int failed() {
        return failedCount == null ? 0 : failedCount;
    }
}

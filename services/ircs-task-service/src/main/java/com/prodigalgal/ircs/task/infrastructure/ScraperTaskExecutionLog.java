package com.prodigalgal.ircs.task.infrastructure;

public record ScraperTaskExecutionLog(
        String timestamp,
        String level,
        String sourceVid,
        String message) {
}

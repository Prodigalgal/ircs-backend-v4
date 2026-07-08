package com.prodigalgal.ircs.task.dto;

public record TaskItemLogResponse(
        String timestamp,
        String level,
        String sourceVid,
        String message
) {
}

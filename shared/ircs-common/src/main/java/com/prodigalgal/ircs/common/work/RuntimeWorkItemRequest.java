package com.prodigalgal.ircs.common.work;

public record RuntimeWorkItemRequest(
        String taskType,
        String taskId,
        String aggregateId,
        String version,
        String payload) {
}

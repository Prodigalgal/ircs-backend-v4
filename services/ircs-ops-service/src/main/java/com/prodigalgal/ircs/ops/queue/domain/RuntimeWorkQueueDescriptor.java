package com.prodigalgal.ircs.ops.queue.domain;

public record RuntimeWorkQueueDescriptor(
        String key,
        String label,
        String taskType,
        String color,
        String inflightColor) {
}

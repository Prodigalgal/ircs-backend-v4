package com.prodigalgal.ircs.ops.queue.dlq.persistence;

import java.time.Instant;
import java.util.UUID;

public record FailedMessageResponse(
        UUID id,
        Instant createdAt,
        Instant updatedAt,
        Long version,
        String queueName,
        String routingKey,
        String exchange,
        String payload,
        String exceptionStack,
        Integer retryCount,
        String status) {
}


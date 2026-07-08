package com.prodigalgal.ircs.ops.queue.dlq.rabbit;

import java.util.List;

public record RabbitDlqQueueResponse(
        String topic,
        String displayName,
        String queueName,
        String sourceQueueName,
        String exchange,
        String routingKey,
        int messagesReady,
        int messagesUnacknowledged,
        int messagesTotal,
        int consumers,
        boolean actionSupported,
        List<RabbitDlqMessageSample> samples
) {
}

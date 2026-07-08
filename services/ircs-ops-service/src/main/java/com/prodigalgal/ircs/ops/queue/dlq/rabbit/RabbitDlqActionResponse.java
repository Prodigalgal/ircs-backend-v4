package com.prodigalgal.ircs.ops.queue.dlq.rabbit;

import java.util.List;

public record RabbitDlqActionResponse(
        String queueName,
        String action,
        int requested,
        int affected,
        RabbitDlqQueueResponse after,
        List<RabbitDlqMessageSample> samples
) {
}

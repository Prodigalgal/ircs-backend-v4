package com.prodigalgal.ircs.ops.dashboard.dto;

import java.util.List;

public record QueueGroupResponse(
        String title,
        boolean dlq,
        List<QueueMetricResponse> queues
) {
}

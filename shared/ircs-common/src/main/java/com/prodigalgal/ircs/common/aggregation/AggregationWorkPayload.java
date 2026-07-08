package com.prodigalgal.ircs.common.aggregation;

import java.util.UUID;

public record AggregationWorkPayload(
        UUID rawVideoId,
        String pipelineVersion,
        String sourceService,
        String reason) {
}

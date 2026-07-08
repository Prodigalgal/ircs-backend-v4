package com.prodigalgal.ircs.common.aggregation;

import java.util.UUID;

public final class AggregationWorkTypes {

    public static final String RAW_VIDEO = "aggregation.raw-video";

    private AggregationWorkTypes() {
    }

    public static String taskId(UUID rawVideoId) {
        if (rawVideoId == null) {
            throw new IllegalArgumentException("rawVideoId is required");
        }
        return "raw_video:" + rawVideoId;
    }
}

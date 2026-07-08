package com.prodigalgal.ircs.common.work;

public record RuntimeWorkQueueCounts(
        long pending,
        long inflight,
        long dlq) {
}

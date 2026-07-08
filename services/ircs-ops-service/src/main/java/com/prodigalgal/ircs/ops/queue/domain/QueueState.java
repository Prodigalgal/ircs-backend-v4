package com.prodigalgal.ircs.ops.queue.domain;

public record QueueState(int messageCount, int consumerCount) {
    public static QueueState empty() {
        return new QueueState(0, 0);
    }
}

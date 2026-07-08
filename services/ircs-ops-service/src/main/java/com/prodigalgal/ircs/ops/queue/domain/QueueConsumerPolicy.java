package com.prodigalgal.ircs.ops.queue.domain;

import com.prodigalgal.ircs.common.work.SystemConfigWorkSubmissionGate;
import java.util.List;

public record QueueConsumerPolicy(
        boolean hasConsumer,
        List<SystemConfigWorkSubmissionGate.ConfigFlag> enabledFlags) {

    public QueueConsumerPolicy {
        enabledFlags = enabledFlags == null ? List.of() : List.copyOf(enabledFlags);
    }

    public static QueueConsumerPolicy enabled() {
        return new QueueConsumerPolicy(true, List.of());
    }

    public static QueueConsumerPolicy enabled(List<SystemConfigWorkSubmissionGate.ConfigFlag> enabledFlags) {
        return new QueueConsumerPolicy(true, enabledFlags);
    }

    public static QueueConsumerPolicy missing() {
        return new QueueConsumerPolicy(false, List.of());
    }
}

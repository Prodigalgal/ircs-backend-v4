package com.prodigalgal.ircs.ops.infrastructure.rabbit;

public record RabbitManagementQueueSnapshot(
        String name,
        int messagesReady,
        int messagesUnacknowledged,
        int messagesTotal,
        int consumers
) {
    public static RabbitManagementQueueSnapshot empty(String name) {
        return new RabbitManagementQueueSnapshot(name, 0, 0, 0, 0);
    }
}

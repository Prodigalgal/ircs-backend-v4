package com.prodigalgal.ircs.content.maintenance.messaging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateChangedEvent;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

class ContentMaintenanceGateEventTopologyTest {

    @Test
    void bindsContentMaintenanceGateQueueToDomainEventRoutingKey() {
        var declarables = new ContentMaintenanceGateEventTopology()
                .contentMaintenanceGateChangedTopology()
                .getDeclarables();

        assertTrue(declarables.stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .anyMatch(queue -> MaintenanceGateChangedListener.QUEUE_NAME.equals(queue.getName())));
        assertTrue(declarables.stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .anyMatch(binding -> MaintenanceGateChangedListener.QUEUE_NAME.equals(binding.getDestination())
                        && QueueTopic.Names.DOMAIN_EVENT_X.equals(binding.getExchange())
                        && MaintenanceGateChangedEvent.ROUTING_KEY.equals(binding.getRoutingKey())));
    }
}

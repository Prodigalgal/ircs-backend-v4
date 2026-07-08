package com.prodigalgal.ircs.ops.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;

class OpsConfigEventTopologyTest {

    @Test
    void declaresOpsConfigQueueAndBindings() {
        Declarables declarables = new OpsConfigEventTopology().opsConfigChangedTopology();

        assertTrue(declarables.getDeclarablesByType(Queue.class).stream()
                .anyMatch(queue -> SystemConfigChangedListener.QUEUE_NAME.equals(queue.getName())));
        assertTrue(declarables.getDeclarablesByType(Queue.class).stream()
                .anyMatch(queue -> (SystemConfigChangedListener.QUEUE_NAME + ".dlq").equals(queue.getName())));
        assertTrue(declarables.getDeclarablesByType(Binding.class).stream()
                .anyMatch(binding -> SystemConfigChangedListener.QUEUE_NAME.equals(binding.getDestination())
                        && SystemConfigChangedEvent.ROUTING_KEY.equals(binding.getRoutingKey())));
    }
}

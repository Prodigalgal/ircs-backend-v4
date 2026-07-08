package com.prodigalgal.ircs.normalization;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

class NormalizationConfigEventTopologyTest {

    @Test
    void declaresDedicatedNormalizationConfigChangedQueue() {
        Declarables declarables = new NormalizationConfigEventTopology().normalizationConfigChangedTopology();

        assertTrue(declarables.getDeclarables().stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .anyMatch(queue -> SystemConfigChangedListener.QUEUE_NAME.equals(queue.getName())));
        assertTrue(declarables.getDeclarables().stream()
                .filter(TopicExchange.class::isInstance)
                .map(TopicExchange.class::cast)
                .anyMatch(exchange -> QueueTopic.Names.DOMAIN_EVENT_X.equals(exchange.getName())));
        assertTrue(declarables.getDeclarables().stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .anyMatch(binding -> SystemConfigChangedEvent.ROUTING_KEY.equals(binding.getRoutingKey())));
    }
}

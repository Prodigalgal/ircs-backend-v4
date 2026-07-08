package com.prodigalgal.ircs.scraper;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

class ScraperConfigEventTopologyTest {

    @Test
    void bindsScraperConfigQueueToDomainEventRoutingKey() {
        var declarables = new ScraperConfigEventTopology().scraperConfigChangedTopology().getDeclarables();

        assertTrue(declarables.stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .anyMatch(queue -> SystemConfigChangedListener.QUEUE_NAME.equals(queue.getName())));
        assertTrue(declarables.stream()
                .filter(Binding.class::isInstance)
                .map(Binding.class::cast)
                .anyMatch(binding -> SystemConfigChangedListener.QUEUE_NAME.equals(binding.getDestination())
                        && QueueTopic.Names.DOMAIN_EVENT_X.equals(binding.getExchange())
                        && SystemConfigChangedEvent.ROUTING_KEY.equals(binding.getRoutingKey())));
    }
}

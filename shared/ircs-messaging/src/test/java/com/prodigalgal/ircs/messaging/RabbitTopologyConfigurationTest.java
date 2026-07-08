package com.prodigalgal.ircs.messaging;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import org.junit.jupiter.api.Assertions;
import org.springframework.amqp.core.Queue;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.TopicExchange;

class RabbitTopologyConfigurationTest {

    @Test
    void declaresDomainEventExchangeForBroadcastEvents() {
        var declarables = new RabbitTopologyConfiguration().ircsRabbitTopology();

        boolean found = declarables.getDeclarables().stream()
                .filter(TopicExchange.class::isInstance)
                .map(TopicExchange.class::cast)
                .anyMatch(exchange -> QueueTopic.Names.DOMAIN_EVENT_X.equals(exchange.getName()));

        assertTrue(found);
    }

    @Test
    void declaresTaskExchangeAndMtPtDtQueues() {
        var declarables = new RabbitTopologyConfiguration().ircsRabbitTopology().getDeclarables();

        assertTrue(declarables.stream()
                .filter(TopicExchange.class::isInstance)
                .map(TopicExchange.class::cast)
                .anyMatch(exchange -> QueueTopic.Names.TASK_X.equals(exchange.getName())));
        Assertions.assertAll(
                () -> assertQueue(declarables, QueueTopic.Names.TASK_Q_PAGE),
                () -> assertQueue(declarables, QueueTopic.Names.TASK_Q_DETAIL),
                () -> assertQueue(declarables, QueueTopic.Names.TASK_Q_PAGE_DISCOVERED),
                () -> assertQueue(declarables, QueueTopic.Names.TASK_Q_PAGE_FAILED),
                () -> assertQueue(declarables, QueueTopic.Names.TASK_Q_DETAIL_DONE),
                () -> assertQueue(declarables, QueueTopic.Names.TASK_Q_MASTER_DONE));
    }

    @Test
    void declaresRetryQueuesForTaskQueues() {
        var declarables = new RabbitTopologyConfiguration().ircsRabbitTopology().getDeclarables();

        Assertions.assertAll(
                () -> assertRetryQueue(declarables, QueueTopic.TASK_PAGE),
                () -> assertRetryQueue(declarables, QueueTopic.TASK_DETAIL),
                () -> assertRetryQueue(declarables, QueueTopic.TASK_PAGE_DISCOVERED),
                () -> assertRetryQueue(declarables, QueueTopic.TASK_PAGE_FAILED),
                () -> assertRetryQueue(declarables, QueueTopic.TASK_DETAIL_DONE),
                () -> assertRetryQueue(declarables, QueueTopic.TASK_MASTER_DONE));
    }

    private void assertQueue(java.util.Collection<?> declarables, String queueName) {
        assertTrue(declarables.stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .anyMatch(queue -> queueName.equals(queue.getName())), queueName);
    }

    private void assertRetryQueue(java.util.Collection<?> declarables, QueueTopic topic) {
        Queue retryQueue = declarables.stream()
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast)
                .filter(queue -> topic.retryName().equals(queue.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(topic.retryName()));
        Assertions.assertEquals(30000L, retryQueue.getArguments().get("x-message-ttl"));
        Assertions.assertEquals(topic.exchange(), retryQueue.getArguments().get("x-dead-letter-exchange"));
        Assertions.assertEquals(topic.routingKey(), retryQueue.getArguments().get("x-dead-letter-routing-key"));
    }
}

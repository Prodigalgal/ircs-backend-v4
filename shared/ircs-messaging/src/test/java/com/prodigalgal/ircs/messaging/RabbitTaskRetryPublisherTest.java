package com.prodigalgal.ircs.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class RabbitTaskRetryPublisherTest {

    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final RabbitTaskRetryPublisher publisher = new RabbitTaskRetryPublisher(rabbitTemplate);

    @Test
    void publishesRetryMessageWithIncrementedRetryCount() {
        Message message = message("{}", 1);

        int retryCount = publisher.publishRetry(QueueTopic.TASK_PAGE, message, new IllegalStateException("temporary"));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(QueueTopic.Names.TASK_X), eq(QueueTopic.TASK_PAGE.retryRoutingKey()), messageCaptor.capture());
        assertThat(retryCount).isEqualTo(2);
        assertThat(messageCaptor.getValue().getMessageProperties().getHeaders())
                .containsEntry(RabbitTaskRetryPublisher.HEADER_RETRY_COUNT, 2)
                .containsEntry(RabbitTaskRetryPublisher.HEADER_DISPOSITION, RabbitTaskRetryPublisher.DISPOSITION_RETRY)
                .containsEntry(RabbitTaskRetryPublisher.HEADER_ORIGINAL_ROUTING_KEY, QueueTopic.TASK_PAGE.routingKey());
    }

    @Test
    void publishesDlqMessageToSharedDlx() {
        Message message = message("{}", 3);

        publisher.publishDlq(QueueTopic.TASK_DETAIL, message, new IllegalArgumentException("fatal"));

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(eq(QueueTopic.Names.DLX), eq(QueueTopic.TASK_DETAIL.dlqName()), messageCaptor.capture());
        assertThat(messageCaptor.getValue().getMessageProperties().getHeaders())
                .containsEntry(RabbitTaskRetryPublisher.HEADER_RETRY_COUNT, 3)
                .containsEntry(RabbitTaskRetryPublisher.HEADER_DISPOSITION, RabbitTaskRetryPublisher.DISPOSITION_DLQ);
    }

    private Message message(String payload, int retryCount) {
        MessageProperties properties = new MessageProperties();
        properties.setHeader(RabbitTaskRetryPublisher.HEADER_RETRY_COUNT, retryCount);
        return new Message(payload.getBytes(StandardCharsets.UTF_8), properties);
    }
}

package com.prodigalgal.ircs.messaging;

import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.time.Instant;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnClass(RabbitTemplate.class)
public class RabbitTaskRetryPublisher {

    public static final String HEADER_RETRY_COUNT = "x-ircs-retry-count";
    public static final String HEADER_DISPOSITION = "x-ircs-disposition";
    public static final String HEADER_ORIGINAL_EXCHANGE = "x-ircs-original-exchange";
    public static final String HEADER_ORIGINAL_ROUTING_KEY = "x-ircs-original-routing-key";
    public static final String HEADER_ERROR_CLASS = "x-ircs-error-class";
    public static final String HEADER_ERROR_MESSAGE = "x-ircs-error-message";
    public static final String HEADER_FAILED_AT = "x-ircs-failed-at";
    public static final String DISPOSITION_RETRY = "retry";
    public static final String DISPOSITION_DLQ = "dlq";

    private static final int ERROR_MESSAGE_LIMIT = 500;

    private final RabbitTemplate rabbitTemplate;

    public RabbitTaskRetryPublisher(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    public int retryCount(Message message) {
        if (message == null || message.getMessageProperties() == null) {
            return 0;
        }
        Object value = message.getMessageProperties().getHeaders().get(HEADER_RETRY_COUNT);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value instanceof String text) {
            try {
                return Math.max(0, Integer.parseInt(text));
            } catch (NumberFormatException ignored) {
                return 0;
            }
        }
        return 0;
    }

    public int publishRetry(QueueTopic topic, Message originalMessage, Throwable error) {
        int retryCount = retryCount(originalMessage) + 1;
        Message retryMessage = copyWithFailureHeaders(topic, originalMessage, error, retryCount, DISPOSITION_RETRY);
        rabbitTemplate.send(topic.exchange(), topic.retryRoutingKey(), retryMessage);
        return retryCount;
    }

    public void publishDlq(QueueTopic topic, Message originalMessage, Throwable error) {
        int retryCount = retryCount(originalMessage);
        Message dlqMessage = copyWithFailureHeaders(topic, originalMessage, error, retryCount, DISPOSITION_DLQ);
        rabbitTemplate.send(QueueTopic.Names.DLX, topic.dlqName(), dlqMessage);
    }

    private Message copyWithFailureHeaders(
            QueueTopic topic,
            Message originalMessage,
            Throwable error,
            int retryCount,
            String disposition) {
        return MessageBuilder.fromMessage(originalMessage)
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setHeader(HEADER_RETRY_COUNT, Math.max(0, retryCount))
                .setHeader(HEADER_DISPOSITION, disposition)
                .setHeader(HEADER_ORIGINAL_EXCHANGE, topic.exchange())
                .setHeader(HEADER_ORIGINAL_ROUTING_KEY, topic.routingKey())
                .setHeader(HEADER_ERROR_CLASS, error == null ? null : error.getClass().getName())
                .setHeader(HEADER_ERROR_MESSAGE, safeMessage(error))
                .setHeader(HEADER_FAILED_AT, Instant.now().toString())
                .build();
    }

    private String safeMessage(Throwable error) {
        if (error == null || error.getMessage() == null || error.getMessage().isBlank()) {
            return null;
        }
        String message = error.getMessage();
        if (message.length() <= ERROR_MESSAGE_LIMIT) {
            return message;
        }
        return message.substring(0, ERROR_MESSAGE_LIMIT);
    }
}

package com.prodigalgal.ircs.scraper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.ingestion.IngestionItem;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
class IngestionPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    void publish(IngestionItem item) {
        QueueTopic topic = QueueTopic.INGEST_VIDEO;
        try {
            rabbitTemplate.convertAndSend(topic.exchange(), topic.routingKey(), objectMapper.writeValueAsString(item));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize ingestion item", e);
        }
    }
}

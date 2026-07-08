package com.prodigalgal.ircs.identity.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class SystemConfigChangePublisherTest {

    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final SystemConfigChangePublisher publisher = new SystemConfigChangePublisher(rabbitTemplate, objectMapper);

    @Test
    void publishesSideWriteEventWithoutConfigValue() {
        publisher.publish("security.jwt.iat-floor", SystemConfigChangedEvent.Action.UPDATED, "DB", 5L, 4L);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(
                org.mockito.Mockito.eq(QueueTopic.Names.DOMAIN_EVENT_X),
                org.mockito.Mockito.eq(SystemConfigChangedEvent.ROUTING_KEY),
                payloadCaptor.capture());
        String payload = payloadCaptor.getValue();
        assertTrue(payload.contains("\"key\":\"security.jwt.iat-floor\""));
        assertTrue(payload.contains("\"sensitive\":true"));
        assertTrue(payload.contains("\"revision\":5"));
        assertTrue(payload.contains("\"previousRevision\":4"));
        assertTrue(payload.contains("\"eventId\""));
        assertFalse(payload.contains("configValue"));
        assertFalse(payload.contains("effectiveValue"));
    }
}

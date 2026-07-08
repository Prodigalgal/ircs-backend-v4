package com.prodigalgal.ircs.config.infrastructure;


import com.prodigalgal.ircs.config.application.ConfigValueRedactor;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class SystemConfigChangePublisherTest {

    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final SystemConfigChangePublisher publisher =
            new SystemConfigChangePublisher(rabbitTemplate, new ConfigValueRedactor(), objectMapper);

    @Test
    void publishesConfigChangedEventWithoutConfigValue() {
        publisher.publish("app.mail.enabled", SystemConfigChangedEvent.Action.UPDATED, "DB", 7L, 6L);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(
                org.mockito.Mockito.eq(QueueTopic.Names.DOMAIN_EVENT_X),
                org.mockito.Mockito.eq(SystemConfigChangedEvent.ROUTING_KEY),
                payloadCaptor.capture());
        SystemConfigChangedEvent event = readEvent(payloadCaptor.getValue());
        assertEquals("app.mail.enabled", event.key());
        assertEquals(SystemConfigChangedEvent.Action.UPDATED, event.action());
        assertEquals("DB", event.effectiveSource());
        assertEquals(false, event.sensitive());
        assertEquals(7L, event.revision());
        assertEquals(6L, event.previousRevision());
        org.junit.jupiter.api.Assertions.assertNotNull(event.eventId());
        org.junit.jupiter.api.Assertions.assertFalse(payloadCaptor.getValue().contains("configValue"));
        org.junit.jupiter.api.Assertions.assertFalse(payloadCaptor.getValue().contains("effectiveValue"));
        assertSafePayloadShape(payloadCaptor.getValue());
    }

    @Test
    void marksSensitiveKeysWithoutPublishingSecretValue() {
        publisher.publish("security.jwt.secret", SystemConfigChangedEvent.Action.UPDATED, "INJECTED", 8L, 7L);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(
                org.mockito.Mockito.eq(QueueTopic.Names.DOMAIN_EVENT_X),
                org.mockito.Mockito.eq(SystemConfigChangedEvent.ROUTING_KEY),
                payloadCaptor.capture());

        SystemConfigChangedEvent event = readEvent(payloadCaptor.getValue());
        assertTrue(event.sensitive());
        assertEquals("INJECTED", event.effectiveSource());
        assertEquals(8L, event.revision());
        assertEquals(7L, event.previousRevision());
        assertSafePayloadShape(payloadCaptor.getValue());
        org.junit.jupiter.api.Assertions.assertFalse(payloadCaptor.getValue().contains("configValue"));
        org.junit.jupiter.api.Assertions.assertFalse(payloadCaptor.getValue().contains("effectiveValue"));
        org.junit.jupiter.api.Assertions.assertFalse(payloadCaptor.getValue().contains("db-secret-value"));
        org.junit.jupiter.api.Assertions.assertFalse(payloadCaptor.getValue().contains("runtime-secret-value"));
    }

    @Test
    void swallowsRabbitFailureAsBestEffortSideEffect() {
        doThrow(new AmqpException("rabbit down"))
                .when(rabbitTemplate)
                .convertAndSend(
                        org.mockito.Mockito.eq(QueueTopic.Names.DOMAIN_EVENT_X),
                        org.mockito.Mockito.eq(SystemConfigChangedEvent.ROUTING_KEY),
                        org.mockito.Mockito.anyString());

        assertDoesNotThrow(() -> publisher.publish(
                "app.mail.enabled",
                SystemConfigChangedEvent.Action.UPDATED,
                "DB",
                2L,
                1L));
    }

    private SystemConfigChangedEvent readEvent(String payload) {
        try {
            return objectMapper.readValue(payload, SystemConfigChangedEvent.class);
        } catch (Exception ex) {
            throw new AssertionError("Unable to read system config changed event", ex);
        }
    }

    private void assertSafePayloadShape(String payload) {
        try {
            java.util.Set<String> fieldNames = new java.util.HashSet<>();
            objectMapper.readTree(payload).fieldNames().forEachRemaining(fieldNames::add);
            assertEquals(Set.of(
                    "eventId",
                    "key",
                    "action",
                    "effectiveSource",
                    "sensitive",
                    "revision",
                    "previousRevision",
                    "changedAt"), fieldNames);
        } catch (Exception ex) {
            throw new AssertionError("Unable to inspect system config changed event payload", ex);
        }
    }
}

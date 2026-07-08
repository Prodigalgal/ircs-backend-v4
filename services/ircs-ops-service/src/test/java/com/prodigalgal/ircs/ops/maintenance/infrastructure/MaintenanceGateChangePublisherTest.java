package com.prodigalgal.ircs.ops.maintenance.infrastructure;


import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateResponse;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateChangedEvent;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateStatus;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class MaintenanceGateChangePublisherTest {

    private final RabbitTemplate rabbitTemplate = org.mockito.Mockito.mock(RabbitTemplate.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final MaintenanceGateChangePublisher publisher =
            new MaintenanceGateChangePublisher(rabbitTemplate, objectMapper);

    @Test
    void publishesVersionedGateChangedEvent() throws Exception {
        MaintenanceGateResponse response = response(7);

        publisher.publishClosed(response);

        ArgumentCaptor<String> payloadCaptor = ArgumentCaptor.forClass(String.class);
        verify(rabbitTemplate).convertAndSend(
                org.mockito.Mockito.eq(QueueTopic.Names.DOMAIN_EVENT_X),
                org.mockito.Mockito.eq(MaintenanceGateChangedEvent.ROUTING_KEY),
                payloadCaptor.capture());
        MaintenanceGateChangedEvent event = objectMapper.readValue(payloadCaptor.getValue(), MaintenanceGateChangedEvent.class);
        assertNotNull(event.eventId());
        assertEquals(response.id(), event.operationId());
        assertEquals(response.operationKey(), event.operationKey());
        assertEquals(MaintenanceGateChangedEvent.Action.CLOSED, event.action());
        assertEquals(response.ownerService(), event.ownerService());
        assertEquals(response.resourceType(), event.resourceType());
        assertEquals(response.resourceScope(), event.resourceScope());
        assertEquals(response.mode(), event.mode());
        assertEquals(response.status(), event.status());
        assertEquals(7, event.revision());
        assertEquals(response.updatedAt(), event.changedAt());
        assertEquals(response.expiresAt(), event.expiresAt());
        assertEquals(response.correlationId(), event.correlationId());
    }

    @Test
    void swallowsRabbitFailureAsBestEffortSideEffect() {
        doThrow(new AmqpException("rabbit down"))
                .when(rabbitTemplate)
                .convertAndSend(
                        org.mockito.Mockito.eq(QueueTopic.Names.DOMAIN_EVENT_X),
                        org.mockito.Mockito.eq(MaintenanceGateChangedEvent.ROUTING_KEY),
                        org.mockito.Mockito.anyString());

        assertDoesNotThrow(() -> publisher.publishCreated(response(0)));
    }

    private MaintenanceGateResponse response(long version) {
        Instant now = Instant.parse("2026-06-11T10:15:30Z");
        return new MaintenanceGateResponse(
                UUID.randomUUID(),
                now.minusSeconds(60),
                now,
                version,
                "content-video-rebuild",
                "content-service",
                "video",
                "*",
                MaintenanceGateMode.READ_ONLY,
                version > 0 ? MaintenanceGateStatus.CLOSED : MaintenanceGateStatus.ACTIVE,
                "rebuild",
                "admin",
                "corr",
                now.plusSeconds(600),
                version > 0 ? now : null,
                version > 0 ? "done" : "");
    }
}

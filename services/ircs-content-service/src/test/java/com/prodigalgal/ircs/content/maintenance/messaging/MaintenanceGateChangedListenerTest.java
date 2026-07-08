package com.prodigalgal.ircs.content.maintenance.messaging;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateChangedEvent;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateMode;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateStatus;
import com.prodigalgal.ircs.content.maintenance.application.ContentMaintenanceGate;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

class MaintenanceGateChangedListenerTest {

    private final ContentMaintenanceGate maintenanceGate = org.mockito.Mockito.mock(ContentMaintenanceGate.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final MaintenanceGateChangedListener listener =
            new MaintenanceGateChangedListener(maintenanceGate, objectMapper);

    @Test
    void invalidatesContentGateOnValidEvent() throws Exception {
        MaintenanceGateChangedEvent event = event("content-service");

        listener.handle(objectMapper.writeValueAsString(event));

        verify(maintenanceGate).invalidate(event);
    }

    @Test
    void ignoresMalformedPayloadAsBestEffortConsumer() {
        listener.handle("{");

        verifyNoInteractions(maintenanceGate);
    }

    @Test
    void listenerUsesDedicatedMaintenanceGateAutoStartupSwitch() throws Exception {
        Method handle = MaintenanceGateChangedListener.class.getDeclaredMethod("handle", String.class);
        RabbitListener rabbitListener = handle.getAnnotation(RabbitListener.class);

        org.junit.jupiter.api.Assertions.assertEquals(
                "${app.content.maintenance-gate-listener.enabled:false}",
                rabbitListener.autoStartup());
    }

    private MaintenanceGateChangedEvent event(String ownerService) {
        Instant now = Instant.parse("2026-06-11T10:15:30Z");
        return new MaintenanceGateChangedEvent(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "content-video-rebuild",
                MaintenanceGateChangedEvent.Action.CLOSED,
                ownerService,
                "video",
                "*",
                MaintenanceGateMode.READ_ONLY,
                MaintenanceGateStatus.CLOSED,
                1L,
                now,
                now.plusSeconds(600),
                "corr");
    }
}

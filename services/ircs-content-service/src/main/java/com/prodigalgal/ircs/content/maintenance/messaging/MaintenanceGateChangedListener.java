package com.prodigalgal.ircs.content.maintenance.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateChangedEvent;
import com.prodigalgal.ircs.content.maintenance.application.ContentMaintenanceGate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
class MaintenanceGateChangedListener {

    static final String QUEUE_NAME = "q.content.maintenance_gate_changed";

    private final ContentMaintenanceGate maintenanceGate;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QUEUE_NAME, autoStartup = "${app.content.maintenance-gate-listener.enabled:false}")
    void handle(String payload) {
        try {
            MaintenanceGateChangedEvent event = objectMapper.readValue(payload, MaintenanceGateChangedEvent.class);
            maintenanceGate.invalidate(event);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to parse content maintenance gate change event", ex);
        }
    }
}

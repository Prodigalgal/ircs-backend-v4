package com.prodigalgal.ircs.ops.maintenance.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.contracts.maintenance.MaintenanceGateChangedEvent;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import com.prodigalgal.ircs.ops.maintenance.dto.MaintenanceGateResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class MaintenanceGateChangePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    public void publishCreated(MaintenanceGateResponse response) {
        publish(response, MaintenanceGateChangedEvent.Action.CREATED);
    }

    public void publishClosed(MaintenanceGateResponse response) {
        publish(response, MaintenanceGateChangedEvent.Action.CLOSED);
    }

    private void publish(MaintenanceGateResponse response, MaintenanceGateChangedEvent.Action action) {
        if (response == null) {
            return;
        }
        MaintenanceGateChangedEvent event = new MaintenanceGateChangedEvent(
                IrcsUuidGenerators.nextId(),
                response.id(),
                response.operationKey(),
                action,
                response.ownerService(),
                response.resourceType(),
                response.resourceScope(),
                response.mode(),
                response.status(),
                response.version(),
                response.updatedAt(),
                response.expiresAt(),
                response.correlationId());
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    dispatch(event);
                }
            });
            return;
        }
        dispatch(event);
    }

    private void dispatch(MaintenanceGateChangedEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    QueueTopic.Names.DOMAIN_EVENT_X,
                    MaintenanceGateChangedEvent.ROUTING_KEY,
                    objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException | RuntimeException ex) {
            log.warn(
                    "Failed to publish maintenance gate change event: operationId={}, action={}, revision={}",
                    event.operationId(),
                    event.action(),
                    event.revision(),
                    ex);
        }
    }
}

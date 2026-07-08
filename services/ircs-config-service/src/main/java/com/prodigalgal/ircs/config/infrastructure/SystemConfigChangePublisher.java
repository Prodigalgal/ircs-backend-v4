package com.prodigalgal.ircs.config.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.config.application.ConfigValueRedactor;
import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
@Slf4j
public class SystemConfigChangePublisher {

    private final RabbitTemplate rabbitTemplate;
    private final ConfigValueRedactor valueRedactor;
    private final ObjectMapper objectMapper;

    public void publish(
            String key,
            SystemConfigChangedEvent.Action action,
            String effectiveSource,
            long revision,
            long previousRevision) {
        SystemConfigChangedEvent event = new SystemConfigChangedEvent(
                IrcsUuidGenerators.nextId(),
                key,
                action,
                effectiveSource,
                valueRedactor.isSensitive(key),
                revision,
                previousRevision,
                Instant.now());
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

    private void dispatch(SystemConfigChangedEvent event) {
        try {
            rabbitTemplate.convertAndSend(
                    QueueTopic.Names.DOMAIN_EVENT_X,
                    SystemConfigChangedEvent.ROUTING_KEY,
                    objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException | RuntimeException ex) {
            log.warn(
                    "Failed to publish system config change event: key={}, action={}",
                    event.key(),
                    event.action(),
                    ex);
        }
    }
}

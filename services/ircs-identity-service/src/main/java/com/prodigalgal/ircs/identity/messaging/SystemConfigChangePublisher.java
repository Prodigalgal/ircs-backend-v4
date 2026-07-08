package com.prodigalgal.ircs.identity.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.id.IrcsUuidGenerators;
import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import com.prodigalgal.ircs.contracts.queue.QueueTopic;
import java.time.Instant;
import java.util.Locale;
import java.util.regex.Pattern;
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

    private static final Pattern SENSITIVE_KEY = Pattern.compile(
            "(password|passwd|pwd|secret|token|api[-_.]?key|access[-_.]?key|private[-_.]?key|credential|jwt|auth)",
            Pattern.CASE_INSENSITIVE);

    private final RabbitTemplate rabbitTemplate;
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
                isSensitive(key),
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
                    "Failed to publish identity system config change event: key={}, action={}",
                    event.key(),
                    event.action(),
                    ex);
        }
    }

    private boolean isSensitive(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        return SENSITIVE_KEY.matcher(key.toLowerCase(Locale.ROOT)).find();
    }
}

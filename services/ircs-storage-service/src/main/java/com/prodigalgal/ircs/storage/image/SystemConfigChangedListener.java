package com.prodigalgal.ircs.storage.image;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
@Slf4j
class SystemConfigChangedListener {

    static final String QUEUE_NAME = "q.storage.config_changed";

    private final SystemConfigRepository repository;
    private final ObjectMapper objectMapper;

    @RabbitListener(queues = QUEUE_NAME, autoStartup = "${app.storage.config-listener.enabled:true}")
    void handle(String payload) {
        try {
            SystemConfigChangedEvent event = objectMapper.readValue(payload, SystemConfigChangedEvent.class);
            if (StringUtils.hasText(event.key())) {
                repository.evict(event.key());
            }
        } catch (JsonProcessingException ex) {
            log.warn("Failed to parse storage system config change event", ex);
        }
    }
}

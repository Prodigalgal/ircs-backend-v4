package com.prodigalgal.ircs.ops.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
class SystemConfigChangedListener {

    static final String QUEUE_NAME = "q.ops.config_changed";

    private final SystemConfigRepository repository;
    private final RuntimeConfigService runtimeConfig;
    private final ObjectMapper objectMapper;

    SystemConfigChangedListener(
            SystemConfigRepository repository,
            RuntimeConfigService runtimeConfig,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.runtimeConfig = runtimeConfig;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = QUEUE_NAME, autoStartup = "${app.ops.config-listener.enabled:true}")
    void handle(String payload) {
        try {
            SystemConfigChangedEvent event = objectMapper.readValue(payload, SystemConfigChangedEvent.class);
            if (StringUtils.hasText(event.key())) {
                repository.evict(event.key());
                runtimeConfig.evict(event.key());
            }
        } catch (JsonProcessingException ex) {
            log.warn("Failed to parse ops system config change event", ex);
        }
    }
}

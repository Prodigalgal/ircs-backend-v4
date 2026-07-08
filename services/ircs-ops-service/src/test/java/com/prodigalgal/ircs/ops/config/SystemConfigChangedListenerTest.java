package com.prodigalgal.ircs.ops.config;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

class SystemConfigChangedListenerTest {

    private final SystemConfigRepository repository = org.mockito.Mockito.mock(SystemConfigRepository.class);
    private final RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final SystemConfigChangedListener listener =
            new SystemConfigChangedListener(repository, runtimeConfig, objectMapper);

    @Test
    void evictsChangedKey() throws Exception {
        String payload = objectMapper.writeValueAsString(new SystemConfigChangedEvent(
                UUID.randomUUID(),
                OpsConfigValues.REINDEX_DEV_LIMIT_KEY,
                SystemConfigChangedEvent.Action.UPDATED,
                "DB",
                false,
                1L,
                0L,
                java.time.Instant.now()));

        listener.handle(payload);

        verify(repository).evict(OpsConfigValues.REINDEX_DEV_LIMIT_KEY);
        verify(runtimeConfig).evict(OpsConfigValues.REINDEX_DEV_LIMIT_KEY);
    }

    @Test
    void ignoresMalformedPayload() {
        listener.handle("{bad-json");

        verify(repository, never()).evict(org.mockito.Mockito.anyString());
    }

    @Test
    void listenerUsesDedicatedConfigAutoStartupSwitch() throws Exception {
        Method handle = SystemConfigChangedListener.class.getDeclaredMethod("handle", String.class);
        RabbitListener rabbitListener = handle.getAnnotation(RabbitListener.class);

        org.junit.jupiter.api.Assertions.assertEquals(
                "${app.ops.config-listener.enabled:true}",
                rabbitListener.autoStartup());
    }
}

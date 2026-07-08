package com.prodigalgal.ircs.search.support;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.prodigalgal.ircs.contracts.config.SystemConfigChangedEvent;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

class SystemConfigChangedListenerTest {

    private final SystemConfigRepository repository = org.mockito.Mockito.mock(SystemConfigRepository.class);
    private final ObjectMapper objectMapper = JsonMapper.builder().findAndAddModules().build();
    private final SystemConfigChangedListener listener = new SystemConfigChangedListener(repository, objectMapper);

    @Test
    void evictsChangedKey() throws Exception {
        String payload = objectMapper.writeValueAsString(new SystemConfigChangedEvent(
                UUID.randomUUID(),
                "app.storage.r2.public-domain",
                SystemConfigChangedEvent.Action.UPDATED,
                "DB",
                true,
                1L,
                0L,
                Instant.now()));

        listener.handle(payload);

        verify(repository).evict("app.storage.r2.public-domain");
    }

    @Test
    void ignoresMalformedPayloadAsBestEffortConsumer() {
        listener.handle("{");

        verifyNoInteractions(repository);
    }

    @Test
    void listenerUsesDedicatedConfigAutoStartupSwitch() throws Exception {
        Method handle = SystemConfigChangedListener.class.getDeclaredMethod("handle", String.class);
        RabbitListener rabbitListener = handle.getAnnotation(RabbitListener.class);

        org.junit.jupiter.api.Assertions.assertEquals(
                "${app.search.config-listener.enabled:true}",
                rabbitListener.autoStartup());
    }
}

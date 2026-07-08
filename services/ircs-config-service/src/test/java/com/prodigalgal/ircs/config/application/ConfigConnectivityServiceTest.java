package com.prodigalgal.ircs.config.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigConnectivityServiceTest {

    private final ConfigConnectivityService service = new ConfigConnectivityService();

    @Test
    void returnsDryRunSuccessWithoutExternalAccess() {
        Map<String, String> result = service.testConnection("llm", Map.of("api_key", "secret"));

        assertEquals("连接成功", result.get("message"));
        assertEquals("LLM", result.get("type"));
        assertEquals("dry-run", result.get("mode"));
        assertEquals("1", result.get("fieldCount"));
    }

    @Test
    void rejectsUnknownConnectionType() {
        assertThrows(IllegalArgumentException.class, () -> service.testConnection("unknown", Map.of()));
    }
}

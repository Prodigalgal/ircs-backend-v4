package com.prodigalgal.ircs.ops.metadata.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class MetadataDebugControllerTest {

    private final MetadataDebugController controller = new MetadataDebugController();

    @Test
    void returnsDryRunResponseWithoutCallingExternalProvider() {
        Map<String, Object> body = controller.search("Movie", "2026", "Sub", "TMDB").getBody();

        assertEquals("DRY_RUN", body.get("status"));
        assertEquals("TMDB", body.get("providerType"));
    }
}

package com.prodigalgal.ircs.interaction;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class UserMessageResponseTest {

    @Test
    void serializesBothPublicFieldNamesForPortalAndAdminCompatibility() throws Exception {
        UserMessageResponse response = new UserMessageResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "画外用户",
                "member@example.invalid",
                null,
                "留言",
                null,
                "PENDING",
                true,
                Instant.parse("2026-06-04T00:00:00Z"),
                Instant.parse("2026-06-04T00:00:00Z"));

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(response);

        assertTrue(json.get("isPublic").asBoolean());
        assertTrue(json.get("public").asBoolean());
        assertFalse(json.has("publicMessage"));
    }
}

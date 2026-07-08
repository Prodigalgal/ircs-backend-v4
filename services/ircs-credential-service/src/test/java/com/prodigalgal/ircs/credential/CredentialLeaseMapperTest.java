package com.prodigalgal.ircs.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialLeaseMapperTest {

    private final CredentialLeaseMapper mapper = new CredentialLeaseMapper(new ObjectMapper());

    @Test
    void mapsScalarPayloadValuesToLeaseSecrets() {
        CredentialRecord record = record("""
                {"api_key":"secret","number_value":42,"nested":{"ignored":true},"null_value":null}
                """);

        var lease = mapper.toLease(record);

        assertEquals("TMDB", lease.getProvider());
        assertEquals(Map.of("api_key", "secret", "number_value", "42"), lease.getSecretPayload());
        assertEquals(50L, lease.getDayLimit());
        assertEquals(500L, lease.getMonthLimit());
    }

    @Test
    void ignoresInvalidPayload() {
        assertEquals(Map.of(), mapper.secretPayload("not-json"));
    }

    private CredentialRecord record(String payloadJson) {
        return new CredentialRecord(
                UUID.randomUUID(),
                Instant.parse("2026-06-03T00:00:00Z"),
                Instant.parse("2026-06-03T00:00:00Z"),
                "TMDB",
                "dev",
                payloadJson,
                "abcdef1234567890",
                true,
                1,
                30,
                "MINUTE",
                50L,
                500L,
                0L,
                0L,
                "remark");
    }
}

package com.prodigalgal.ircs.credential;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CredentialSanitizerTest {

    private final CredentialSanitizer sanitizer = new CredentialSanitizer(new ObjectMapper());

    @Test
    void exposesOnlyRedactedPayloadKeysAndFingerprintSuffix() {
        CredentialRecord record = new CredentialRecord(
                UUID.randomUUID(),
                Instant.parse("2026-06-03T00:00:00Z"),
                Instant.parse("2026-06-03T00:01:00Z"),
                "TMDB",
                "dev",
                "{\"api_key\":\"secret\",\"token\":\"another-secret\"}",
                "0123456789abcdef",
                true,
                5,
                30,
                "MINUTE",
                100L,
                1000L,
                0L,
                0L,
                "remark");

        CredentialSummary summary = sanitizer.toSummary(record);

        assertEquals("89abcdef", summary.fingerprintSuffix());
        assertEquals(Map.of(
                "api_key", Map.of("present", true, "redacted", true),
                "token", Map.of("present", true, "redacted", true)), summary.payload());
        assertEquals(List.of("api_key", "token"), summary.payloadKeys());
    }

    @Test
    void toleratesMissingSecretMetadata() {
        assertEquals(Map.of(), sanitizer.payload(""));
        assertEquals(Map.of(), sanitizer.payload("not-json"));
        assertNull(sanitizer.fingerprintSuffix(null));
    }
}

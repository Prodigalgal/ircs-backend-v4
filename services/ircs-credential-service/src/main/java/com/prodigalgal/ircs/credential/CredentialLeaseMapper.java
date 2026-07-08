package com.prodigalgal.ircs.credential;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.contracts.credential.ProviderCredentialLease;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CredentialLeaseMapper {

    private final ObjectMapper objectMapper;

    public ProviderCredentialLease toLease(CredentialRecord record) {
        return toLease(record, null, null);
    }

    public ProviderCredentialLease toLease(CredentialRecord record, Instant leasedAt, Instant expiresAt) {
        return ProviderCredentialLease.builder()
                .id(record.id())
                .revision(record.revision())
                .provider(record.provider())
                .name(record.name())
                .secretPayload(secretPayload(record.payloadJson()))
                .priority(record.priority())
                .rateLimit(record.rateLimit())
                .rateLimitUnit(record.rateLimitUnit())
                .dayLimit(record.dayLimit())
                .monthLimit(record.monthLimit())
                .leasedAt(leasedAt)
                .expiresAt(expiresAt)
                .build();
    }

    Map<String, String> secretPayload(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(payloadJson);
            if (!root.isObject()) {
                return Map.of();
            }
            Map<String, String> result = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> field : root.properties()) {
                JsonNode value = field.getValue();
                if (value != null && value.isValueNode() && !value.isNull()) {
                    result.put(field.getKey(), value.asText());
                }
            }
            return result;
        } catch (Exception ignored) {
            return Map.of();
        }
    }
}

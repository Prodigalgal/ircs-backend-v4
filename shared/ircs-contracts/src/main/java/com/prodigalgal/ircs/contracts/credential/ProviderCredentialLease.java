package com.prodigalgal.ircs.contracts.credential;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProviderCredentialLease implements Serializable {
    private UUID id;
    private Long revision;
    private String provider;
    private String name;
    private Map<String, String> secretPayload;
    private Integer priority;
    private Integer rateLimit;
    private String rateLimitUnit;
    private Long dayLimit;
    private Long monthLimit;
    private Instant leasedAt;
    private Instant expiresAt;
}

package com.prodigalgal.ircs.credential;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;

public record CredentialWriteRequest(
        @NotBlank String provider,
        String name,
        @NotNull Map<String, Object> payload,
        Boolean enabled,
        Integer priority,
        @Min(0) Integer rateLimit,
        String rateLimitUnit,
        @Min(0) Long dayLimit,
        @Min(0) Long monthLimit,
        @Min(0) Long classALimit,
        @Min(0) Long classBLimit,
        String remark) {
}

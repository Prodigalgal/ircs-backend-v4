package com.prodigalgal.ircs.apigateway;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

final class AdminApiTokenDtos {

    private AdminApiTokenDtos() {
    }

    record CreateRequest(
            @NotBlank(message = "Token 名称不能为空")
            @Size(max = 120, message = "Token 名称不能超过 120 个字符")
            String name) {
    }

    record Summary(
            UUID id,
            String name,
            String tokenPrefix,
            String status,
            String createdBy,
            Instant createdAt,
            Instant lastUsedAt,
            Instant revokedAt,
            String revokedBy,
            Instant expiresAt) {
    }

    record CreatedResponse(
            UUID id,
            String name,
            String tokenPrefix,
            String token,
            Instant createdAt) {
    }
}

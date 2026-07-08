package com.prodigalgal.ircs.catalog;

import java.time.Instant;
import java.util.UUID;

public record StandardAreaRead(
        UUID id,
        String name,
        String code,
        String region,
        Instant createdAt,
        Instant updatedAt) {}

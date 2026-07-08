package com.prodigalgal.ircs.catalog;

import java.time.Instant;
import java.util.UUID;

public record StandardCategoryRead(
        UUID id,
        String name,
        String slug,
        Instant createdAt,
        Instant updatedAt) {}

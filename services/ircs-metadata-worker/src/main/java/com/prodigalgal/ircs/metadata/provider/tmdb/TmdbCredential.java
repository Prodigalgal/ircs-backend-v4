package com.prodigalgal.ircs.metadata.provider.tmdb;

import java.util.UUID;

public record TmdbCredential(
        UUID id,
        String apiKey,
        Integer rateLimit,
        String rateLimitUnit,
        Integer priority) {
}

package com.prodigalgal.ircs.normalization;

import java.math.BigDecimal;
import java.util.UUID;

public record RawVideoRecord(
        UUID id,
        String normalizationStatus,
        Integer normalizationRetryCount,
        String rawMetadata,
        String lockedFields,
        String title,
        String aliasTitle,
        Integer season,
        String subtitle,
        String description,
        String year,
        String area,
        String rawLanguageStr,
        String remarks,
        BigDecimal score,
        String totalEpisodes,
        String duration,
        String doubanId,
        String tmdbId,
        String imdbId,
        String rottenTomatoesId,
        UUID dataSourceId,
        String dataHash
) {
}

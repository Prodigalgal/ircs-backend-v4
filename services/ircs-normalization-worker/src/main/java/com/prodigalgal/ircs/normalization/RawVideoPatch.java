package com.prodigalgal.ircs.normalization;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

public record RawVideoPatch(
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
        UUID rawCategoryDataSourceId,
        String rawCategorySourceCode,
        String rawCategorySourceName,
        Set<String> rawGenreValues,
        Set<String> rawLanguageValues,
        Set<String> rawAreaValues,
        Set<String> actorValues,
        Set<String> directorValues
) {
}

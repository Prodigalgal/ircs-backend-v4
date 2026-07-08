package com.prodigalgal.ircs.contracts.trend;

import java.math.BigDecimal;
import java.time.LocalDate;

public record TrendItemPayload(
        String title,
        String originalTitle,
        String description,
        String year,
        LocalDate publishedAt,
        BigDecimal score,
        String posterUrl,
        String tmdbId,
        String doubanId,
        String imdbId,
        String type) {
}

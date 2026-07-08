package com.prodigalgal.ircs.contracts.search;

import java.math.BigDecimal;
import java.util.UUID;

public record AdminVideoSearchRequest(
        int page,
        int size,
        String sort,
        String direction,
        String title,
        UUID categoryId,
        String categoryCode,
        String enrichmentStatus,
        String normalizationStatus,
        String aggregationStatus,
        String metadataStatus,
        String year,
        String area,
        BigDecimal minScore,
        Boolean isMissingSlug,
        Boolean hasDoubanId,
        Boolean hasTmdbId,
        String contentVisibility,
        UUID dataSourceId,
        String sourceCategoryName,
        String genre,
        String language,
        String actor,
        String director) {
}

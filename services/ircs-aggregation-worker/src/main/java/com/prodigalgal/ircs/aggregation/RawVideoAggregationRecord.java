package com.prodigalgal.ircs.aggregation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

public record RawVideoAggregationRecord(
        UUID id,
        String title,
        String aliasTitle,
        String description,
        String year,
        BigDecimal score,
        LocalDate publishedAt,
        String totalEpisodes,
        String duration,
        String remarks,
        String subtitle,
        Integer season,
        String categoryName,
        String categoryCode,
        Set<String> actorNames,
        Set<String> directorNames,
        Set<String> areaNames,
        String doubanId,
        String tmdbId,
        String imdbId,
        String rottenTomatoesId,
        String normalizationStatus,
        String enrichmentStatus,
        String aggregationStatus
) {
    public RawVideoAggregationRecord(
            UUID id,
            String title,
            String aliasTitle,
            String description,
            String year,
            BigDecimal score,
            LocalDate publishedAt,
            String totalEpisodes,
            String duration,
            String remarks,
            String subtitle,
            Integer season,
            String categoryName,
            Set<String> actorNames,
            Set<String> directorNames,
            Set<String> areaNames,
            String doubanId,
            String tmdbId,
            String imdbId,
            String rottenTomatoesId,
            String normalizationStatus,
            String enrichmentStatus,
            String aggregationStatus) {
        this(
                id,
                title,
                aliasTitle,
                description,
                year,
                score,
                publishedAt,
                totalEpisodes,
                duration,
                remarks,
                subtitle,
                season,
                categoryName,
                null,
                actorNames,
                directorNames,
                areaNames,
                doubanId,
                tmdbId,
                imdbId,
                rottenTomatoesId,
                normalizationStatus,
                enrichmentStatus,
                aggregationStatus);
    }

    public RawVideoAggregationRecord(
            UUID id,
            String title,
            String aliasTitle,
            String description,
            String year,
            BigDecimal score,
            LocalDate publishedAt,
            String totalEpisodes,
            String duration,
            String remarks,
            String subtitle,
            Integer season,
            String categoryName,
            String categoryCode,
            String doubanId,
            String tmdbId,
            String imdbId,
            String rottenTomatoesId,
            String normalizationStatus,
            String enrichmentStatus,
            String aggregationStatus) {
        this(
                id,
                title,
                aliasTitle,
                description,
                year,
                score,
                publishedAt,
                totalEpisodes,
                duration,
                remarks,
                subtitle,
                season,
                categoryName,
                categoryCode,
                Set.of(),
                Set.of(),
                Set.of(),
                doubanId,
                tmdbId,
                imdbId,
                rottenTomatoesId,
                normalizationStatus,
                enrichmentStatus,
                aggregationStatus);
    }

    public RawVideoAggregationRecord(
            UUID id,
            String title,
            String aliasTitle,
            String description,
            String year,
            BigDecimal score,
            LocalDate publishedAt,
            String totalEpisodes,
            String duration,
            String remarks,
            String subtitle,
            Integer season,
            String categoryName,
            String doubanId,
            String tmdbId,
            String imdbId,
            String rottenTomatoesId,
            String normalizationStatus,
            String enrichmentStatus,
            String aggregationStatus) {
        this(
                id,
                title,
                aliasTitle,
                description,
                year,
                score,
                publishedAt,
                totalEpisodes,
                duration,
                remarks,
                subtitle,
                season,
                categoryName,
                null,
                Set.of(),
                Set.of(),
                Set.of(),
                doubanId,
                tmdbId,
                imdbId,
                rottenTomatoesId,
                normalizationStatus,
                enrichmentStatus,
                aggregationStatus);
    }

    public RawVideoAggregationRecord {
        actorNames = copyTextSet(actorNames);
        directorNames = copyTextSet(directorNames);
        areaNames = copyTextSet(areaNames);
    }

    public boolean isAggregationEligible() {
        return StringUtils.hasText(title)
                && "READY".equals(normalizationStatus)
                && !"PENDING".equals(enrichmentStatus)
                && ("PENDING".equals(aggregationStatus) || "PROCESSING".equals(aggregationStatus));
    }

    RawVideoAggregationRecord withMetadata(
            Set<String> actorNames,
            Set<String> directorNames,
            Set<String> areaNames) {
        return new RawVideoAggregationRecord(
                id,
                title,
                aliasTitle,
                description,
                year,
                score,
                publishedAt,
                totalEpisodes,
                duration,
                remarks,
                subtitle,
                season,
                categoryName,
                categoryCode,
                actorNames,
                directorNames,
                areaNames,
                doubanId,
                tmdbId,
                imdbId,
                rottenTomatoesId,
                normalizationStatus,
                enrichmentStatus,
                aggregationStatus);
    }

    private static Set<String> copyTextSet(Set<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        return values.stream()
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
    }
}

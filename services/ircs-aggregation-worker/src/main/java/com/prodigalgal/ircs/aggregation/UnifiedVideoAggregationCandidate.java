package com.prodigalgal.ircs.aggregation;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;

public record UnifiedVideoAggregationCandidate(
        UUID id,
        String title,
        String aliasTitle,
        String subtitle,
        String year,
        String totalEpisodes,
        String duration,
        String remarks,
        String categoryName,
        Set<String> actorNames,
        Set<String> directorNames,
        Set<String> areaNames,
        String doubanId,
        String tmdbId,
        String imdbId,
        String rottenTomatoesId,
        Integer season
) {
    public UnifiedVideoAggregationCandidate(
            UUID id,
            String title,
            String aliasTitle,
            String subtitle,
            String year,
            String totalEpisodes,
            String duration,
            String remarks,
            String categoryName,
            String doubanId,
            String tmdbId,
            String imdbId,
            String rottenTomatoesId,
            Integer season) {
        this(
                id,
                title,
                aliasTitle,
                subtitle,
                year,
                totalEpisodes,
                duration,
                remarks,
                categoryName,
                Set.of(),
                Set.of(),
                Set.of(),
                doubanId,
                tmdbId,
                imdbId,
                rottenTomatoesId,
                season);
    }

    public UnifiedVideoAggregationCandidate {
        actorNames = copyTextSet(actorNames);
        directorNames = copyTextSet(directorNames);
        areaNames = copyTextSet(areaNames);
    }

    UnifiedVideoAggregationCandidate withMetadata(
            Set<String> actorNames,
            Set<String> directorNames,
            Set<String> areaNames) {
        return new UnifiedVideoAggregationCandidate(
                id,
                title,
                aliasTitle,
                subtitle,
                year,
                totalEpisodes,
                duration,
                remarks,
                categoryName,
                actorNames,
                directorNames,
                areaNames,
                doubanId,
                tmdbId,
                imdbId,
                rottenTomatoesId,
                season);
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

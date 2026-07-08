package com.prodigalgal.ircs.metadata.result.domain;

import com.prodigalgal.ircs.contracts.metadata.ProviderType;
import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RawVideoMetadataRecord {
    private UUID id;
    private String doubanId;
    private String tmdbId;
    private String imdbId;
    private String rottenTomatoesId;
    private String description;
    private BigDecimal score;
    private String year;
    private String aliasTitle;
    private String rawMetadata;
    private Set<String> lockedFields;
    private String enrichmentStatus;
    private Integer enrichmentRetryCount;
    private String aggregationStatus;
    private String dataHash;

    public boolean isFieldLocked(String fieldName) {
        return lockedFields != null && lockedFields.contains(fieldName);
    }

    public boolean hasAuthoritativeId() {
        return hasText(doubanId) || hasText(tmdbId) || hasText(imdbId) || hasText(rottenTomatoesId);
    }

    public boolean hasProviderId(ProviderType providerType) {
        if (providerType == null) {
            return false;
        }
        return switch (providerType) {
            case DOUBAN -> hasText(doubanId);
            case TMDB -> hasText(tmdbId);
            case ROTTEN_TOMATOES -> hasText(rottenTomatoesId);
            default -> false;
        };
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

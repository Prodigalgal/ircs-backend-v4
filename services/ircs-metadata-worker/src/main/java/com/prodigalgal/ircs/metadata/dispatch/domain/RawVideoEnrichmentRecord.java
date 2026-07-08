package com.prodigalgal.ircs.metadata.dispatch.domain;

import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class RawVideoEnrichmentRecord {
    private UUID id;
    private String title;
    private String aliasTitle;
    private String subtitle;
    private Integer season;
    private String year;
    private String categorySlug;
    private String doubanId;
    private String tmdbId;
    private String imdbId;
    private String rottenTomatoesId;
    private String enrichmentStatus;
    private Integer enrichmentRetryCount;
    private String dataHash;

    public boolean hasAuthoritativeId() {
        return hasText(doubanId) || hasText(tmdbId);
    }

    public static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

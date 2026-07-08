package com.prodigalgal.ircs.portal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PortalMovieDetailResponse(
        UUID id,
        String title,
        String aliasTitle,
        String tagline,
        String description,
        BigDecimal rating,
        String releaseYear,
        String totalEpisodes,
        String remarks,
        String duration,
        String posterUrl,
        String area,
        String language,
        String categoryName,
        String doubanId,
        String tmdbId,
        String imdbId,
        Instant lastTrendAt,
        List<String> genres,
        List<CastMember> cast,
        List<VideoSource> sources,
        List<MagnetLink> magnets,
        List<String> tags,
        List<Object> cloudLinks) {

    public record CastMember(String id, String name, String role) {
    }

    public record VideoSource(String id, String name, List<Episode> episodes) {
    }

    public record Episode(String id, String title, String url) {
    }

    public record MagnetLink(
            UUID id,
            String title,
            String link,
            String size,
            Instant uploadDate,
            String quality,
            String resolution,
            Integer seeders,
            String providerCode,
            List<String> tags) {
    }
}

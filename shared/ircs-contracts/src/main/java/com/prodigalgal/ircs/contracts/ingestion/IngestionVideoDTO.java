package com.prodigalgal.ircs.contracts.ingestion;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record IngestionVideoDTO(
        String sourceVid,
        String sourceHash,
        String dataHash,
        String title,
        String aliasTitle,
        String description,
        String coverImageUrl,
        String year,
        String area,
        String language,
        String remarks,
        BigDecimal score,
        LocalDate publishedAt,
        String totalEpisodes,
        String duration,
        String doubanId,
        String tmdbId,
        String imdbId,
        String rottenTomatoesId,
        String rawMetadata,
        String normalizationStatus,
        UUID dataSourceId,
        List<IngestionPlaylistDTO> playlists,
        Integer ingestionRetryCount
) {
}

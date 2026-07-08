package com.prodigalgal.ircs.contracts.ingestion;

import java.util.List;

public record IngestionPlaylistDTO(
        String name,
        List<IngestionEpisodeDTO> episodes
) {
}

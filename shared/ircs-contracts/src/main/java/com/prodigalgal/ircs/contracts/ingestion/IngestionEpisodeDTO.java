package com.prodigalgal.ircs.contracts.ingestion;

import java.util.UUID;

public record IngestionEpisodeDTO(
        String name,
        String url,
        UUID sourceDomainId
) {
}

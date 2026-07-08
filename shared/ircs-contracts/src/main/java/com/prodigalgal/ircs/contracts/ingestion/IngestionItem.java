package com.prodigalgal.ircs.contracts.ingestion;

public record IngestionItem(
        IngestionVideoDTO video,
        boolean forceIngest
) {
}

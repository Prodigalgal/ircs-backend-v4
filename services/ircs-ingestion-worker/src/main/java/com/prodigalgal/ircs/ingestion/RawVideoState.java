package com.prodigalgal.ircs.ingestion;

import java.util.UUID;

public record RawVideoState(
        UUID id,
        String dataHash
) {
}

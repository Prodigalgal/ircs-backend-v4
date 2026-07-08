package com.prodigalgal.ircs.metadata.result.infrastructure;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RawVideoMetadataRepositoryTest {

    @Test
    void selectsAndUpdatesRawMetadataJsonbForEnrichmentMerge() {
        assertTrue(RawVideoMetadataRepository.SELECT_BY_ID.contains("raw_metadata::text"));
        assertTrue(RawVideoMetadataRepository.UPDATE_METADATA.contains("raw_metadata = cast(? as jsonb)"));
    }
}

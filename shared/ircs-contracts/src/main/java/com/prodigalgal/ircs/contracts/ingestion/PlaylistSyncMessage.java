package com.prodigalgal.ircs.contracts.ingestion;

import java.util.List;
import java.util.UUID;

public record PlaylistSyncMessage(
        UUID videoId,
        String sourceHash,
        String dataHash,
        List<IngestionPlaylistDTO> playlists
) {
}

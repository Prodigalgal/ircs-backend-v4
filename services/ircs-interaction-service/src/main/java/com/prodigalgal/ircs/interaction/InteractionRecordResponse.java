package com.prodigalgal.ircs.interaction;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record InteractionRecordResponse(
        UUID id,
        UUID unifiedVideoId,
        String title,
        String coverImageUrl,
        BigDecimal score,
        UUID lastVideoId,
        UUID lastEpisodeId,
        String episodeName,
        int progressSeconds,
        int durationSeconds,
        Instant lastWatchedAt,
        @JsonProperty("isFavorite") boolean favorite) {
}

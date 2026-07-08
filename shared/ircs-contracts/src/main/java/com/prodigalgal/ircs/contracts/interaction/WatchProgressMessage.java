package com.prodigalgal.ircs.contracts.interaction;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class WatchProgressMessage implements Serializable {
    private UUID memberId;
    private UUID unifiedVideoId;
    private UUID videoId;
    private UUID episodeId;
    private String episodeName;
    private int progressSeconds;
    private int durationSeconds;
    private Instant timestamp;
}

package com.prodigalgal.ircs.catalog;

import java.util.UUID;

public record LanguageTraceRead(
        UUID videoId,
        String videoTitle,
        String sourceVid,
        UUID dataSourceId,
        String dataSourceName,
        String originalUrl) {}

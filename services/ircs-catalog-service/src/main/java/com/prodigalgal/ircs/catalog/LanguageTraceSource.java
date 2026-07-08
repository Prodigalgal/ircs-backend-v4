package com.prodigalgal.ircs.catalog;

import java.util.UUID;

record LanguageTraceSource(
        UUID videoId,
        String videoTitle,
        String sourceVid,
        UUID dataSourceId,
        String dataSourceName,
        String baseUrl,
        String detailPath,
        String detailParams) {}

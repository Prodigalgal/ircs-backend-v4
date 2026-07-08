package com.prodigalgal.ircs.contracts.task;

public record TaskScrapeOptions(
        String keyword,
        String filterType,
        Integer filterHours,
        String userAgent,
        boolean enableRandomUa,
        boolean useCustomProxy,
        String proxyType,
        String proxyHost,
        Integer proxyPort,
        String proxyUsername,
        String proxyPassword,
        String headers,
        Integer fixedDelayMs,
        boolean forceIngest
) {
}

package com.prodigalgal.ircs.contracts.trend;

import java.util.List;

public record TrendDiscoveryScheduleRequest(
        List<String> keywords,
        Integer startPage,
        Integer endPage,
        Integer fixedDelayMs,
        Boolean force,
        Integer maxDataSources) {

    public TrendDiscoveryScheduleRequest {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    public TrendDiscoveryScheduleRequest(
            List<String> keywords,
            Integer startPage,
            Integer endPage,
            Integer fixedDelayMs,
            Boolean force) {
        this(keywords, startPage, endPage, fixedDelayMs, force, null);
    }
}

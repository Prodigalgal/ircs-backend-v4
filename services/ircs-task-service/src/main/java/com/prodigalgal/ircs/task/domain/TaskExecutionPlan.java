package com.prodigalgal.ircs.task.domain;

import java.util.UUID;

public record TaskExecutionPlan(
        UUID id,
        String name,
        UUID dataSourceId,
        String status,
        Boolean enabled,
        Integer startPage,
        Integer endPage,
        Integer currentPage,
        String filterType,
        Integer filterHours,
        String filterKeywords,
        Integer fixedDelayMs,
        String userAgent,
        Boolean enableRandomUa,
        Boolean useCustomProxy,
        String proxyType,
        String proxyHost,
        Integer proxyPort,
        String proxyUsername,
        String proxyPassword,
        String headers) {

    public int effectiveStartPage(boolean resume) {
        int configured = startPage != null && startPage > 0 ? startPage : 1;
        if (resume && currentPage != null && currentPage >= configured) {
            return currentPage;
        }
        return configured;
    }

    public int effectiveEndPage(boolean resume) {
        int start = effectiveStartPage(resume);
        return endPage != null && endPage >= start ? endPage : start;
    }
}

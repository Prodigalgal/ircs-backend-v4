package com.prodigalgal.ircs.task.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskUpdateRequest(
        @NotNull UUID id,
        String name,
        Boolean enabled,
        String cronExpression,
        String timeZone,
        UUID dataSourceId,
        String taskType,
        Integer startPage,
        Integer endPage,
        String filterType,
        Integer filterHours,
        String filterKeywords,
        String requestDelayType,
        Integer fixedDelayMs,
        Integer randomDelayMinMs,
        Integer randomDelayMaxMs,
        Integer timeoutMs,
        Integer maxRetries,
        String userAgent,
        Boolean enableRandomUa,
        Boolean useCustomProxy,
        String proxyType,
        String proxyHost,
        Integer proxyPort,
        String proxyUsername,
        String proxyPassword,
        String headers
) {
}

package com.prodigalgal.ircs.task.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record TaskCreateRequest(
        @NotBlank String name,
        @NotNull UUID dataSourceId,
        @NotBlank String taskType,
        Boolean enabled,
        String cronExpression,
        String timeZone,
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
        @Min(0) @Max(10) Integer maxRetries,
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

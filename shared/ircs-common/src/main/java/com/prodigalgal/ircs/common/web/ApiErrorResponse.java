package com.prodigalgal.ircs.common.web;

import java.time.Instant;
import java.util.Map;

public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String entity,
        String path,
        String traceId,
        String correlationId,
        Map<String, Object> details) {
}

package com.prodigalgal.ircs.common.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

public final class ApiErrorResponses {

    public static final String TRACE_HEADER = "X-Trace-Id";
    public static final String CORRELATION_HEADER = "X-Correlation-Id";

    private ApiErrorResponses() {
    }

    public static ResponseEntity<ApiErrorResponse> response(
            HttpStatusCode status,
            String code,
            String message,
            String entity,
            HttpServletRequest request) {
        return response(status, code, message, entity, request, null);
    }

    public static ResponseEntity<ApiErrorResponse> response(
            HttpStatusCode status,
            String code,
            String message,
            String entity,
            HttpServletRequest request,
            Map<String, Object> details) {
        ApiErrorResponse body = body(status, code, message, entity, request, details);
        return ResponseEntity.status(status)
                .headers(headers(body))
                .body(body);
    }

    public static ResponseEntity<ApiErrorResponse> validation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        FieldError fieldError = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .orElse(null);
        String message = fieldError == null
                ? "Validation failed"
                : fieldError.getField() + ": " + fieldError.getDefaultMessage();
        Map<String, Object> details = new LinkedHashMap<>();
        if (fieldError != null) {
            details.put("field", fieldError.getField());
            details.put("reason", fieldError.getDefaultMessage());
        }
        return response(
                HttpStatus.BAD_REQUEST,
                "validation.failed",
                message,
                "request",
                request,
                details.isEmpty() ? null : details);
    }

    public static ResponseEntity<ApiErrorResponse> statusException(
            ResponseStatusException exception,
            HttpServletRequest request) {
        String reason = StringUtils.hasText(exception.getReason())
                ? exception.getReason()
                : reasonPhrase(exception.getStatusCode());
        return response(
                exception.getStatusCode(),
                code("http", exception.getStatusCode()),
                reason,
                "http",
                request);
    }

    public static ApiErrorResponse body(
            HttpStatusCode status,
            String code,
            String message,
            String entity,
            HttpServletRequest request,
            Map<String, Object> details) {
        String traceId = traceId(request);
        String correlationId = correlationId(request, traceId);
        return new ApiErrorResponse(
                Instant.now(),
                status.value(),
                reasonPhrase(status),
                normalize(code, code(entity, status)),
                normalize(message, reasonPhrase(status)),
                normalize(entity, "general"),
                request == null ? null : request.getRequestURI(),
                traceId,
                correlationId,
                details == null || details.isEmpty() ? null : Map.copyOf(details));
    }

    public static void write(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatusCode status,
            String code,
            String message,
            String entity) throws IOException {
        ApiErrorResponse body = body(status, code, message, entity, request, null);
        headers(body).forEach((name, values) -> {
            if (!HttpHeaders.CONTENT_TYPE.equalsIgnoreCase(name)) {
                values.forEach(value -> response.addHeader(name, value));
            }
        });
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(toJson(body));
    }

    public static HttpHeaders headers(ApiErrorResponse body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (StringUtils.hasText(body.traceId())) {
            headers.set(TRACE_HEADER, body.traceId());
        }
        if (StringUtils.hasText(body.correlationId())) {
            headers.set(CORRELATION_HEADER, body.correlationId());
        }
        return headers;
    }

    public static String code(String entity, HttpStatusCode status) {
        String owner = normalize(entity, "general")
                .replaceAll("[^A-Za-z0-9._-]+", ".")
                .replaceAll("\\.+", ".")
                .replaceAll("^\\.|\\.$", "")
                .toLowerCase(java.util.Locale.ROOT);
        return owner + "." + status.value();
    }

    private static String traceId(HttpServletRequest request) {
        String direct = firstHeader(request, TRACE_HEADER, "X-Request-Id", "X-B3-TraceId");
        if (direct != null) {
            return direct;
        }
        String traceparent = normalize(request == null ? null : request.getHeader("traceparent"), null);
        if (traceparent != null) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 2 && StringUtils.hasText(parts[1])) {
                return parts[1];
            }
            return traceparent;
        }
        return UUID.randomUUID().toString();
    }

    private static String correlationId(HttpServletRequest request, String traceId) {
        String direct = firstHeader(request, CORRELATION_HEADER, "X-Request-Id", "X-B3-TraceId");
        return direct == null ? traceId : direct;
    }

    private static String firstHeader(HttpServletRequest request, String... names) {
        if (request == null) {
            return null;
        }
        for (String name : names) {
            String value = normalize(request.getHeader(name), null);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String reasonPhrase(HttpStatusCode status) {
        HttpStatus resolved = HttpStatus.resolve(status.value());
        return resolved == null ? "HTTP " + status.value() : resolved.getReasonPhrase();
    }

    private static String normalize(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static String toJson(ApiErrorResponse body) {
        return "{"
                + "\"timestamp\":\"" + escape(body.timestamp().toString()) + "\","
                + "\"status\":" + body.status() + ","
                + "\"error\":\"" + escape(body.error()) + "\","
                + "\"code\":\"" + escape(body.code()) + "\","
                + "\"message\":\"" + escape(body.message()) + "\","
                + "\"entity\":\"" + escape(body.entity()) + "\","
                + "\"path\":\"" + escape(body.path()) + "\","
                + "\"traceId\":\"" + escape(body.traceId()) + "\","
                + "\"correlationId\":\"" + escape(body.correlationId()) + "\""
                + "}";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "\\r")
                .replace("\n", "\\n");
    }
}

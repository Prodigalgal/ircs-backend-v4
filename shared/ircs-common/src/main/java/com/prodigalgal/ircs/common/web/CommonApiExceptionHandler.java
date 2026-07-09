package com.prodigalgal.ircs.common.web;

import com.prodigalgal.ircs.common.maintenance.MaintenanceGateDecision;
import com.prodigalgal.ircs.common.maintenance.MaintenanceGateLockedException;
import com.prodigalgal.ircs.common.security.IrcsAuthException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class CommonApiExceptionHandler {

    private static final int CLIENT_CLOSED_REQUEST_STATUS = 499;
    private static final Logger log = LoggerFactory.getLogger(CommonApiExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleStatus(
            ResponseStatusException exception,
            HttpServletRequest request) {
        return ApiErrorResponses.statusException(exception, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return ApiErrorResponses.validation(exception, request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingRequestParameter(
            MissingServletRequestParameterException exception,
            HttpServletRequest request) {
        return ApiErrorResponses.response(
                HttpStatus.BAD_REQUEST,
                "request.parameter.missing",
                "Missing required request parameter: " + exception.getParameterName(),
                "request",
                request,
                Map.of("parameter", exception.getParameterName()));
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiErrorResponse> handleNotFound(Exception exception, HttpServletRequest request) {
        return ApiErrorResponses.response(
                HttpStatus.NOT_FOUND,
                "http.404",
                "Resource not found",
                "http",
                request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodNotAllowed(
            HttpRequestMethodNotSupportedException exception,
            HttpServletRequest request) {
        return ApiErrorResponses.response(
                HttpStatus.METHOD_NOT_ALLOWED,
                "http.405",
                "Request method is not supported",
                "http",
                request,
                Map.of("method", exception.getMethod()));
    }

    @ExceptionHandler(IrcsAuthException.class)
    public ResponseEntity<ApiErrorResponse> handleAuth(
            IrcsAuthException exception,
            HttpServletRequest request) {
        HttpStatus status = exception.reason() == IrcsAuthException.Reason.FORBIDDEN
                ? HttpStatus.FORBIDDEN
                : HttpStatus.UNAUTHORIZED;
        return ApiErrorResponses.response(
                status,
                "auth.token." + exception.reason().name().toLowerCase(java.util.Locale.ROOT),
                exception.getMessage(),
                "auth",
                request);
    }

    @ExceptionHandler(MaintenanceGateLockedException.class)
    public ResponseEntity<ApiErrorResponse> handleMaintenanceGateLocked(
            MaintenanceGateLockedException exception,
            HttpServletRequest request) {
        MaintenanceGateDecision decision = exception.decision();
        Map<String, Object> details = new LinkedHashMap<>();
        if (decision != null) {
            details.put("checkKind", decision.checkKind());
            details.put("operationId", decision.operationId());
            details.put("operationKey", decision.operationKey());
            details.put("ownerService", decision.ownerService());
            details.put("resourceType", decision.resourceType());
            details.put("resourceScope", decision.resourceScope());
            details.put("mode", decision.mode());
            details.put("reason", decision.reason());
            details.put("expiresAt", decision.expiresAt());
        }
        return ApiErrorResponses.response(
                HttpStatus.LOCKED,
                "maintenance.gate.locked",
                exception.getMessage(),
                "maintenance",
                request,
                details);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiErrorResponse> handleBadRequest(
            IllegalArgumentException exception,
            HttpServletRequest request) {
        return ApiErrorResponses.response(
                HttpStatus.BAD_REQUEST,
                "request.invalid",
                "Invalid request",
                "request",
                request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(
            Exception exception,
            HttpServletRequest request) {
        if (isDisconnectedClient(exception)) {
            log.debug(
                    "Client disconnected before API response completed path={} traceId={} correlationId={}",
                    request == null ? null : request.getRequestURI(),
                    request == null ? null : request.getHeader("X-Trace-Id"),
                    request == null ? null : request.getHeader("X-Correlation-Id"));
            return ResponseEntity.status(CLIENT_CLOSED_REQUEST_STATUS).body(null);
        }
        log.error(
                "Unhandled API exception path={} traceId={} correlationId={}",
                request == null ? null : request.getRequestURI(),
                request == null ? null : request.getHeader("X-Trace-Id"),
                request == null ? null : request.getHeader("X-Correlation-Id"),
                exception);
        return ApiErrorResponses.response(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "internal.error",
                "Internal server error",
                "system",
                request);
    }

    private static boolean isDisconnectedClient(Throwable exception) {
        Throwable current = exception;
        while (current != null) {
            String className = current.getClass().getName();
            if ("org.springframework.web.context.request.async.AsyncRequestNotUsableException".equals(className)
                    || className.endsWith(".ClientAbortException")) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains("broken pipe")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

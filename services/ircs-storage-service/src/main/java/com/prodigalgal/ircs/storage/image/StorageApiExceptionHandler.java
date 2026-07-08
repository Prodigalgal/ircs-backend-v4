package com.prodigalgal.ircs.storage.image;

import com.prodigalgal.ircs.common.web.ApiErrorResponse;
import com.prodigalgal.ircs.common.web.ApiErrorResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class StorageApiExceptionHandler {

    @ExceptionHandler(StorageApiException.class)
    public ResponseEntity<ApiErrorResponse> handle(
            StorageApiException exception,
            HttpServletRequest request) {
        return ApiErrorResponses.response(
                exception.status(),
                ApiErrorResponses.code("storage", exception.status()),
                exception.getMessage(),
                "storage",
                request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return ApiErrorResponses.validation(exception, request);
    }
}

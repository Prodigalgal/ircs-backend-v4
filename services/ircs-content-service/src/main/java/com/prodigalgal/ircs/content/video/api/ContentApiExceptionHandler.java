package com.prodigalgal.ircs.content.video.api;

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
public class ContentApiExceptionHandler {

    @ExceptionHandler(ContentApiException.class)
    public ResponseEntity<ApiErrorResponse> handle(
            ContentApiException exception,
            HttpServletRequest request) {
        return ApiErrorResponses.response(
                exception.status(),
                ApiErrorResponses.code("content", exception.status()),
                exception.getMessage(),
                "content",
                request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleValidation(
            MethodArgumentNotValidException exception,
            HttpServletRequest request) {
        return ApiErrorResponses.validation(exception, request);
    }
}

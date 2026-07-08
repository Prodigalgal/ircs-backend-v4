package com.prodigalgal.ircs.interaction;

import com.prodigalgal.ircs.common.web.ApiErrorResponse;
import com.prodigalgal.ircs.common.web.ApiErrorResponses;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiErrorResponse> handle(ApiException exception, HttpServletRequest request) {
        return ApiErrorResponses.response(
                exception.status(),
                ApiErrorResponses.code("interaction", exception.status()),
                exception.getMessage(),
                "interaction",
                request);
    }
}

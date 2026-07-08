package com.prodigalgal.ircs.identity.api;

import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String entity;
    private final String errorKey;

    public ApiException(HttpStatus status, String message, String entity, String errorKey) {
        super(message);
        this.status = status;
        this.entity = entity;
        this.errorKey = errorKey;
    }

    public static ApiException badRequest(String message, String entity, String errorKey) {
        return new ApiException(HttpStatus.BAD_REQUEST, message, entity, errorKey);
    }

    public static ApiException unauthorized(String message, String entity, String errorKey) {
        return new ApiException(HttpStatus.UNAUTHORIZED, message, entity, errorKey);
    }

    public static ApiException forbidden(String message, String entity, String errorKey) {
        return new ApiException(HttpStatus.FORBIDDEN, message, entity, errorKey);
    }

    public static ApiException notFound(String message, String entity, String errorKey) {
        return new ApiException(HttpStatus.NOT_FOUND, message, entity, errorKey);
    }

    public HttpStatus status() {
        return status;
    }

    public String entity() {
        return entity;
    }

    public String errorKey() {
        return errorKey;
    }
}

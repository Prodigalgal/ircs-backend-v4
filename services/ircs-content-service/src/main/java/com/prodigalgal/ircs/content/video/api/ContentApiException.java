package com.prodigalgal.ircs.content.video.api;

import org.springframework.http.HttpStatus;

public class ContentApiException extends RuntimeException {

    private final HttpStatus status;

    public ContentApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}

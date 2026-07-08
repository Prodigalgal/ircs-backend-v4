package com.prodigalgal.ircs.storage.image;

import org.springframework.http.HttpStatus;

public class StorageApiException extends RuntimeException {

    private final HttpStatus status;

    public StorageApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}


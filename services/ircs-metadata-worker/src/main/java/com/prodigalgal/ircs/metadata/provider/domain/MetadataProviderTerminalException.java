package com.prodigalgal.ircs.metadata.provider.domain;

public class MetadataProviderTerminalException extends RuntimeException {

    private final String errorCode;

    public MetadataProviderTerminalException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public MetadataProviderTerminalException(String errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}

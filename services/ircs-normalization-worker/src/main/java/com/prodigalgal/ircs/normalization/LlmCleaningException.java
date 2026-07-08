package com.prodigalgal.ircs.normalization;

class LlmCleaningException extends RuntimeException {

    LlmCleaningException(String message, Throwable cause) {
        super(message, cause);
    }

    LlmCleaningException(String message) {
        super(message);
    }

    static final class ProviderTimeout extends LlmCleaningException {
        ProviderTimeout(String message, Throwable cause) {
            super(message, cause);
        }
    }

    static final class ProviderError extends LlmCleaningException {
        ProviderError(String message, Throwable cause) {
            super(message, cause);
        }

        ProviderError(String message) {
            super(message);
        }
    }
}

package com.prodigalgal.ircs.magnet;

class MagnetProviderRunnerException extends RuntimeException {

    private final String failureType;
    private final String requestUrl;
    private final Integer httpStatus;

    MagnetProviderRunnerException(String failureType, String requestUrl, Integer httpStatus) {
        super(buildMessage(failureType, httpStatus));
        this.failureType = failureType;
        this.requestUrl = requestUrl;
        this.httpStatus = httpStatus;
    }

    String failureType() {
        return failureType;
    }

    String requestUrl() {
        return requestUrl;
    }

    Integer httpStatus() {
        return httpStatus;
    }

    private static String buildMessage(String failureType, Integer httpStatus) {
        if (httpStatus == null) {
            return failureType;
        }
        return failureType + " status=" + httpStatus;
    }
}

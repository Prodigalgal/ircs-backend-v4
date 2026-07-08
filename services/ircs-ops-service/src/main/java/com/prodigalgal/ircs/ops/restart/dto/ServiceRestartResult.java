package com.prodigalgal.ircs.ops.restart.dto;

public record ServiceRestartResult(
        String service,
        boolean accepted,
        String message) {

    public static ServiceRestartResult accepted(String service) {
        return new ServiceRestartResult(service, true, "Deployment restart requested");
    }

    public static ServiceRestartResult rejected(String service, String message) {
        return new ServiceRestartResult(service, false, message);
    }
}

package com.prodigalgal.ircs.ops.restart.dto;

import java.util.List;

public record ServiceRestartCapabilitiesResponse(
        boolean enabled,
        String namespace,
        List<String> allowedServices,
        String reason) {
}

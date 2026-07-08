package com.prodigalgal.ircs.ops.traffic.dto;

import java.util.List;

public record TrafficStatusResponse(
        List<TrafficSlotResponse> globalLimiters,
        List<TrafficSlotResponse> credentialLimiters) {
}


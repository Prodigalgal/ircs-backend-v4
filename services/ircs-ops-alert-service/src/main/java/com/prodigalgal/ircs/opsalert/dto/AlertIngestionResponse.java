package com.prodigalgal.ircs.opsalert.dto;

import java.util.List;

public record AlertIngestionResponse(
        AlertEventResponse event,
        IncidentResponse incident,
        List<HealingActionResponse> healingActions) {
}

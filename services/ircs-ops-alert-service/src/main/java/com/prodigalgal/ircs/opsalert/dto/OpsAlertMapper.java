package com.prodigalgal.ircs.opsalert.dto;

import com.prodigalgal.ircs.opsalert.domain.AlertEvent;
import com.prodigalgal.ircs.opsalert.domain.HealingAction;
import com.prodigalgal.ircs.opsalert.domain.Incident;

public final class OpsAlertMapper {

    private OpsAlertMapper() {
    }

    public static AlertEventResponse toResponse(AlertEvent event) {
        return new AlertEventResponse(
                event.id(),
                event.createdAt(),
                event.observedAt(),
                event.source(),
                event.eventType(),
                event.severity(),
                event.resourceType(),
                event.resourceName(),
                event.fingerprint(),
                event.summary(),
                event.detailsJson());
    }

    public static IncidentResponse toResponse(Incident incident) {
        return new IncidentResponse(
                incident.id(),
                incident.createdAt(),
                incident.updatedAt(),
                incident.version(),
                incident.fingerprint(),
                incident.status(),
                incident.severity(),
                incident.title(),
                incident.source(),
                incident.resourceType(),
                incident.resourceName(),
                incident.firstSeenAt(),
                incident.lastSeenAt(),
                incident.recoveredAt(),
                incident.occurrenceCount(),
                incident.lastReason(),
                incident.lastEventId());
    }

    public static HealingActionResponse toResponse(HealingAction action) {
        return new HealingActionResponse(
                action.id(),
                action.incidentId(),
                action.createdAt(),
                action.updatedAt(),
                action.policyKey(),
                action.playbookKey(),
                action.dryRun(),
                action.status(),
                action.requestPayload(),
                action.resultPayload(),
                action.startedAt(),
                action.finishedAt());
    }
}

package com.prodigalgal.ircs.opsalert.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.opsalert.domain.AlertEvent;
import com.prodigalgal.ircs.opsalert.domain.AlertSeverity;
import com.prodigalgal.ircs.opsalert.domain.HealingActionStatus;
import com.prodigalgal.ircs.opsalert.domain.HealingPlan;
import com.prodigalgal.ircs.opsalert.domain.Incident;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HealingPolicyEngine {

    static final String ENABLED_KEY = "app.ops-alert.self-healing.enabled";
    static final String DRY_RUN_KEY = "app.ops-alert.self-healing.dry-run";
    static final String MIN_SEVERITY_KEY = "app.ops-alert.self-healing.min-severity";

    private final RuntimeConfigService runtimeConfig;
    private final ObjectMapper objectMapper;

    public List<HealingPlan> plan(AlertEvent event, Incident incident) {
        if (!runtimeConfig.booleanValue(ENABLED_KEY, true)) {
            return List.of();
        }
        AlertSeverity minSeverity = AlertSeverity.parse(
                runtimeConfig.stringValue(MIN_SEVERITY_KEY, AlertSeverity.WARNING.name()), AlertSeverity.WARNING);
        if (!event.severity().atLeast(minSeverity)) {
            return List.of();
        }
        boolean dryRun = runtimeConfig.booleanValue(DRY_RUN_KEY, true);
        Playbook playbook = playbook(event.eventType());
        return List.of(new HealingPlan(
                playbook.policyKey(),
                playbook.playbookKey(),
                dryRun,
                dryRun ? HealingActionStatus.DRY_RUN : HealingActionStatus.SKIPPED,
                json(Map.of(
                        "incidentId", incident.id().toString(),
                        "eventId", event.id().toString(),
                        "eventType", event.eventType(),
                        "resourceType", event.resourceType(),
                        "resourceName", event.resourceName(),
                        "dryRun", dryRun)),
                json(Map.of(
                        "message", dryRun
                                ? "Dry-run only; no runtime mutation performed."
                                : "Execution is intentionally skipped until playbook executor is enabled.",
                        "phase", "phase-1"))));
    }

    private Playbook playbook(String eventType) {
        String normalized = eventType == null ? "" : eventType.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SERVICE_NOT_READY", "SERVICE_UNAVAILABLE", "POD_NOT_READY" ->
                    new Playbook("service-restart", "service.restart");
            case "RUNTIME_QUEUE_STALE_INFLIGHT" ->
                    new Playbook("runtime-queue-stale-recovery", "runtime-queue.recover-stale");
            case "RABBIT_DLQ_TRANSIENT" ->
                    new Playbook("rabbit-dlq-transient-retry", "rabbit-dlq.retry-transient");
            case "DASHBOARD_TOPIC_FAILURE" ->
                    new Playbook("dashboard-topic-refresh", "dashboard.refresh-topic");
            default -> new Playbook("manual-review", "manual.review");
        };
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize healing plan payload", ex);
        }
    }

    private record Playbook(String policyKey, String playbookKey) {
    }
}

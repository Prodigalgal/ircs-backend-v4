package com.prodigalgal.ircs.opsalert.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.opsalert.domain.AlertEvent;
import com.prodigalgal.ircs.opsalert.domain.AlertSeverity;
import com.prodigalgal.ircs.opsalert.domain.HealingActionStatus;
import com.prodigalgal.ircs.opsalert.domain.Incident;
import com.prodigalgal.ircs.opsalert.domain.IncidentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HealingPolicyEngineTest {

    @Test
    void createsDryRunServiceRestartPlanForNotReadyEvent() {
        RuntimeConfigService runtimeConfig = mock(RuntimeConfigService.class);
        when(runtimeConfig.booleanValue(HealingPolicyEngine.ENABLED_KEY, true)).thenReturn(true);
        when(runtimeConfig.booleanValue(HealingPolicyEngine.DRY_RUN_KEY, true)).thenReturn(true);
        when(runtimeConfig.stringValue(HealingPolicyEngine.MIN_SEVERITY_KEY, AlertSeverity.WARNING.name()))
                .thenReturn(AlertSeverity.WARNING.name());
        HealingPolicyEngine engine = new HealingPolicyEngine(runtimeConfig, new ObjectMapper());

        var plans = engine.plan(event("SERVICE_NOT_READY", AlertSeverity.ERROR), incident());

        assertThat(plans).hasSize(1);
        assertThat(plans.getFirst().policyKey()).isEqualTo("service-restart");
        assertThat(plans.getFirst().playbookKey()).isEqualTo("service.restart");
        assertThat(plans.getFirst().dryRun()).isTrue();
        assertThat(plans.getFirst().status()).isEqualTo(HealingActionStatus.DRY_RUN);
    }

    @Test
    void skipsPlanWhenSeverityIsBelowConfiguredThreshold() {
        RuntimeConfigService runtimeConfig = mock(RuntimeConfigService.class);
        when(runtimeConfig.booleanValue(HealingPolicyEngine.ENABLED_KEY, true)).thenReturn(true);
        when(runtimeConfig.stringValue(HealingPolicyEngine.MIN_SEVERITY_KEY, AlertSeverity.WARNING.name()))
                .thenReturn(AlertSeverity.ERROR.name());
        HealingPolicyEngine engine = new HealingPolicyEngine(runtimeConfig, new ObjectMapper());

        assertThat(engine.plan(event("DASHBOARD_TOPIC_FAILURE", AlertSeverity.WARNING), incident())).isEmpty();
    }

    @Test
    void skipsPlanWhenSelfHealingIsDisabled() {
        RuntimeConfigService runtimeConfig = mock(RuntimeConfigService.class);
        when(runtimeConfig.booleanValue(HealingPolicyEngine.ENABLED_KEY, true)).thenReturn(false);
        HealingPolicyEngine engine = new HealingPolicyEngine(runtimeConfig, new ObjectMapper());

        assertThat(engine.plan(event("SERVICE_NOT_READY", AlertSeverity.CRITICAL), incident())).isEmpty();
    }

    private static AlertEvent event(String eventType, AlertSeverity severity) {
        Instant now = Instant.parse("2026-06-22T10:00:00Z");
        return new AlertEvent(
                UUID.randomUUID(),
                now,
                now,
                "ops-service",
                eventType.toLowerCase(),
                severity,
                "service",
                "ircs-ops-service",
                "service-not-ready",
                "service not ready",
                null);
    }

    private static Incident incident() {
        Instant now = Instant.parse("2026-06-22T10:00:00Z");
        return new Incident(
                UUID.randomUUID(),
                now,
                now,
                0,
                "service-not-ready",
                IncidentStatus.OPEN,
                AlertSeverity.ERROR,
                "service not ready",
                "ops-service",
                "service",
                "ircs-ops-service",
                now,
                now,
                null,
                1,
                "service not ready",
                UUID.randomUUID());
    }
}

package com.prodigalgal.ircs.ops.selfhealing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.ops.restart.application.KubernetesDeploymentRestartService;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartResponse;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class ServiceSelfHealingServiceTest {

    private final KubernetesDeploymentRestartService restartService = mock(KubernetesDeploymentRestartService.class);
    private final RuntimeConfigService runtimeConfig = mock(RuntimeConfigService.class);
    private final ServiceSelfHealingService service = new ServiceSelfHealingService(
            restartService,
            runtimeConfig,
            Clock.fixed(Instant.parse("2026-06-22T12:30:00Z"), ZoneOffset.UTC));

    @Test
    void defaultsToDryRunAndDoesNotRestartService() {
        ServiceRestartHealingResponse response = service.restart(new ServiceRestartHealingRequest(
                "ircs-search-service",
                "probe",
                null));

        assertThat(response.dryRun()).isTrue();
        assertThat(response.accepted()).isFalse();
        assertThat(response.status()).isEqualTo("DRY_RUN");
        verifyNoInteractions(restartService);
    }

    @Test
    void skipsWhenServiceRestartSelfHealingIsDisabled() {
        when(runtimeConfig.booleanValue(ServiceSelfHealingService.DEFAULT_DRY_RUN_KEY, true)).thenReturn(false);
        when(runtimeConfig.booleanValue(ServiceSelfHealingService.ENABLED_KEY, false)).thenReturn(false);

        ServiceRestartHealingResponse response = service.restart(new ServiceRestartHealingRequest(
                "ircs-search-service",
                "probe",
                false));

        assertThat(response.dryRun()).isFalse();
        assertThat(response.status()).isEqualTo("SKIPPED");
        verifyNoInteractions(restartService);
    }

    @Test
    void delegatesToRestartServiceWhenEnabledAndMarksRecoveryVerifiedOnAcceptedResult() {
        when(runtimeConfig.booleanValue(ServiceSelfHealingService.DEFAULT_DRY_RUN_KEY, true)).thenReturn(false);
        when(runtimeConfig.booleanValue(ServiceSelfHealingService.ENABLED_KEY, false)).thenReturn(true);
        when(runtimeConfig.durationValue(ServiceSelfHealingService.COOLDOWN_KEY, Duration.ofMinutes(10)))
                .thenReturn(Duration.ofMinutes(10));
        when(runtimeConfig.durationValue(ServiceSelfHealingService.ATTEMPT_WINDOW_KEY, Duration.ofHours(1)))
                .thenReturn(Duration.ofHours(1));
        when(runtimeConfig.intValue(ServiceSelfHealingService.MAX_ATTEMPTS_KEY, 1)).thenReturn(1);
        when(restartService.restart(List.of("ircs-search-service"), "probe")).thenReturn(new ServiceRestartResponse(
                Instant.parse("2026-06-22T12:30:00Z"),
                "ircs-dev",
                List.of(ServiceRestartResult.accepted("ircs-search-service"))));

        ServiceRestartHealingResponse response = service.restart(new ServiceRestartHealingRequest(
                "ircs-search-service",
                "probe",
                false));

        assertThat(response.accepted()).isTrue();
        assertThat(response.recoveryVerified()).isTrue();
        assertThat(response.status()).isEqualTo("ACCEPTED");
        assertThat(response.evidence()).containsEntry("namespace", "ircs-dev");
        verify(restartService).restart(List.of("ircs-search-service"), "probe");
    }

    @Test
    void appliesCooldownAfterAcceptedRestart() {
        when(runtimeConfig.booleanValue(ServiceSelfHealingService.DEFAULT_DRY_RUN_KEY, true)).thenReturn(false);
        when(runtimeConfig.booleanValue(ServiceSelfHealingService.ENABLED_KEY, false)).thenReturn(true);
        when(runtimeConfig.durationValue(ServiceSelfHealingService.COOLDOWN_KEY, Duration.ofMinutes(10)))
                .thenReturn(Duration.ofMinutes(10));
        when(runtimeConfig.durationValue(ServiceSelfHealingService.ATTEMPT_WINDOW_KEY, Duration.ofHours(1)))
                .thenReturn(Duration.ofHours(1));
        when(runtimeConfig.intValue(ServiceSelfHealingService.MAX_ATTEMPTS_KEY, 1)).thenReturn(2);
        when(restartService.restart(List.of("ircs-search-service"), "probe")).thenReturn(new ServiceRestartResponse(
                Instant.parse("2026-06-22T12:30:00Z"),
                "ircs-dev",
                List.of(ServiceRestartResult.accepted("ircs-search-service"))));

        ServiceRestartHealingResponse accepted = service.restart(new ServiceRestartHealingRequest(
                "ircs-search-service",
                "probe",
                false));
        ServiceRestartHealingResponse blocked = service.restart(new ServiceRestartHealingRequest(
                "ircs-search-service",
                "probe",
                false));

        assertThat(accepted.status()).isEqualTo("ACCEPTED");
        assertThat(blocked.status()).isEqualTo("RATE_LIMITED");
        assertThat(blocked.reason()).contains("cooldown");
    }
}

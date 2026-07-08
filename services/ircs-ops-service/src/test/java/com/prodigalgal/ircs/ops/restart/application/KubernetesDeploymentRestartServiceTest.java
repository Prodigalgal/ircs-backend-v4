package com.prodigalgal.ircs.ops.restart.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.config.RuntimeConfigService;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartCapabilitiesResponse;
import java.util.List;
import org.junit.jupiter.api.Test;

class KubernetesDeploymentRestartServiceTest {

    private final RuntimeConfigService runtimeConfig = org.mockito.Mockito.mock(RuntimeConfigService.class);

    @Test
    void capabilitiesReportDisabledReason() {
        KubernetesDeploymentRestartService service = service(false);

        ServiceRestartCapabilitiesResponse capabilities = service.capabilities();

        assertFalse(capabilities.enabled());
        assertEquals("ircs-dev", capabilities.namespace());
        org.assertj.core.api.Assertions.assertThat(capabilities.allowedServices()).contains("ircs-search-service");
        assertEquals("Service restart is disabled", capabilities.reason());
    }

    @Test
    void capabilitiesReportMissingServiceAccountToken() {
        KubernetesDeploymentRestartService service = service(true);

        ServiceRestartCapabilitiesResponse capabilities = service.capabilities();

        assertFalse(capabilities.enabled());
        assertEquals("Kubernetes service account token is unavailable", capabilities.reason());
    }

    private KubernetesDeploymentRestartService service(boolean enabled) {
        when(runtimeConfig.booleanValue(anyString(), anyBoolean())).thenReturn(enabled);
        when(runtimeConfig.stringValue("app.ops.service-restart.namespace", "ircs-dev")).thenReturn("ircs-dev");
        when(runtimeConfig.stringValue("app.ops.service-restart.allowed-services", "")).thenReturn("");
        return new KubernetesDeploymentRestartService(
                new ObjectMapper(),
                runtimeConfig,
                enabled,
                "",
                "ircs-dev",
                String.join(",", List.of("ircs-search-service")),
                "PT10S");
    }
}

package com.prodigalgal.ircs.ops.restart.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.ops.restart.application.KubernetesDeploymentRestartService;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartCapabilitiesResponse;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartRequest;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartResponse;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

class ServiceRestartControllerTest {

    private final KubernetesDeploymentRestartService restartService =
            org.mockito.Mockito.mock(KubernetesDeploymentRestartService.class);
    private final ServiceRestartController controller = new ServiceRestartController(restartService);

    @Test
    void returnsRestartCapabilities() {
        ServiceRestartCapabilitiesResponse response = new ServiceRestartCapabilitiesResponse(
                false,
                "ircs-dev",
                List.of("ircs-search-service"),
                "Service restart is disabled");
        when(restartService.capabilities()).thenReturn(response);

        ResponseEntity<ServiceRestartCapabilitiesResponse> result = controller.capabilities();

        assertEquals(200, result.getStatusCode().value());
        assertEquals(response, result.getBody());
    }

    @Test
    void returnsAcceptedWhenAnyServiceRestartIsAccepted() {
        ServiceRestartRequest request = new ServiceRestartRequest(List.of("ircs-search-service"), "config changed");
        ServiceRestartResponse response = new ServiceRestartResponse(
                Instant.parse("2026-06-20T00:00:00Z"),
                "ircs-dev",
                List.of(ServiceRestartResult.accepted("ircs-search-service")));
        when(restartService.restart(request.services(), request.reason())).thenReturn(response);

        ResponseEntity<ServiceRestartResponse> result = controller.restart(request);

        assertEquals(202, result.getStatusCode().value());
        assertEquals(response, result.getBody());
    }

    @Test
    void returnsConflictWhenAllServiceRestartsAreRejected() {
        ServiceRestartRequest request = new ServiceRestartRequest(List.of("ircs-search-service"), "config changed");
        ServiceRestartResponse response = new ServiceRestartResponse(
                Instant.parse("2026-06-20T00:00:00Z"),
                "ircs-dev",
                List.of(ServiceRestartResult.rejected("ircs-search-service", "Service restart is disabled")));
        when(restartService.restart(request.services(), request.reason())).thenReturn(response);

        ResponseEntity<ServiceRestartResponse> result = controller.restart(request);

        assertEquals(409, result.getStatusCode().value());
        assertEquals(response, result.getBody());
    }
}

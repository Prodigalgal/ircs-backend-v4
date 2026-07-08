package com.prodigalgal.ircs.ops.restart.controller;

import com.prodigalgal.ircs.ops.restart.application.KubernetesDeploymentRestartService;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartCapabilitiesResponse;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartRequest;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartResponse;
import com.prodigalgal.ircs.ops.restart.dto.ServiceRestartResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/ops/service-restarts")
class ServiceRestartController {

    private final KubernetesDeploymentRestartService restartService;

    @GetMapping("/capabilities")
    ResponseEntity<ServiceRestartCapabilitiesResponse> capabilities() {
        return ResponseEntity.ok(restartService.capabilities());
    }

    @PostMapping
    ResponseEntity<ServiceRestartResponse> restart(@Valid @RequestBody ServiceRestartRequest request) {
        ServiceRestartResponse response = restartService.restart(request.services(), request.reason());
        boolean anyAccepted = response.results().stream().anyMatch(ServiceRestartResult::accepted);
        return ResponseEntity.status(anyAccepted ? HttpStatus.ACCEPTED : HttpStatus.CONFLICT).body(response);
    }
}

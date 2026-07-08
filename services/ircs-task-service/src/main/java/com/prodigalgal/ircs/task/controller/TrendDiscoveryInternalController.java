package com.prodigalgal.ircs.task.controller;

import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleRequest;
import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleResponse;
import com.prodigalgal.ircs.task.security.TaskInternalAccessPolicy;
import com.prodigalgal.ircs.task.application.TrendDiscoveryTaskService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/tasks/trend-discovery")
class TrendDiscoveryInternalController {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final TrendDiscoveryTaskService service;
    private final TaskInternalAccessPolicy accessPolicy;

    @PostMapping
    ResponseEntity<TrendDiscoveryScheduleResponse> schedule(
            @Valid @RequestBody TrendDiscoveryScheduleRequest request,
            @RequestHeader(value = CORRELATION_HEADER, required = false) String correlationId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.accepted().body(service.schedule(request, correlationId));
    }
}

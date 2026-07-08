package com.prodigalgal.ircs.aggregation;

import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.aggregation.AggregationMaintenanceRunResponse;
import com.prodigalgal.ircs.contracts.aggregation.AggregationResetStepResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/v1/aggregation")
@RequiredArgsConstructor
class InternalAggregationController {

    private final AggregationService aggregationService;
    private final AggregationInternalAccessPolicy accessPolicy;
    private final ObjectProvider<AggregationWorkQueueWorker> workQueueWorkerProvider;

    @PostMapping("/unified-videos/{id}/recalculate")
    ResponseEntity<RecalculateResponse> recalculateUnified(
            @PathVariable UUID id,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.ok(new RecalculateResponse(id, aggregationService.recalculateUnified(id)));
    }

    @PostMapping("/unified-videos/recalculate-dirty")
    ResponseEntity<AggregationMaintenanceRunResponse> recalculateDirtyUnified(
            @RequestParam(defaultValue = "5") int limit,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.accepted().body(aggregationService.recalculateDirtyUnified(limit));
    }

    @PostMapping("/raw-videos/enqueue-pending")
    ResponseEntity<AggregationMaintenanceRunResponse> enqueuePendingRawWork(
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.accepted().body(aggregationService.enqueuePendingRawWork(limit));
    }

    @PostMapping("/unified-videos/backfill-covers")
    ResponseEntity<AggregationMaintenanceRunResponse> backfillUnifiedCovers(
            @RequestParam(defaultValue = "100") int limit,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.accepted().body(aggregationService.backfillUnifiedCovers(limit));
    }

    @PostMapping("/unified-videos/backfill-adult-assessments")
    ResponseEntity<AggregationMaintenanceRunResponse> backfillUnifiedAdultAssessments(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "true") boolean publishSearch,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.accepted().body(aggregationService.backfillUnifiedAdultAssessments(limit, publishSearch));
    }

    @PostMapping("/reset/prepare")
    ResponseEntity<AggregationResetStepResponse> prepareAggregationReset(
            @RequestParam(defaultValue = "5") int sampleLimit,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.accepted().body(aggregationService.prepareAggregationReset(sampleLimit));
    }

    @PostMapping("/reset/mark-raw-pending")
    ResponseEntity<AggregationResetStepResponse> markAggregationResetRawPending(
            @RequestParam(defaultValue = "5") int sampleLimit,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        return ResponseEntity.accepted().body(aggregationService.markAllRawAggregationPending(sampleLimit));
    }

    @GetMapping("/work-queue/state")
    ResponseEntity<AggregationWorkQueueState> workQueueState(
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_ID, required = false) String serviceId,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_TOKEN, required = false) String serviceToken,
            @RequestHeader(value = InternalServiceAuthHeaders.SERVICE_SCOPES, required = false) String serviceScopes) {
        accessPolicy.assertAccess(serviceId, serviceToken, serviceScopes);
        AggregationWorkQueueWorker worker = workQueueWorkerProvider.getIfAvailable();
        return ResponseEntity.ok(worker == null ? null : worker.state());
    }

    record RecalculateResponse(UUID unifiedVideoId, int sourceCount) {
    }
}

package com.prodigalgal.ircs.aggregation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.prodigalgal.ircs.contracts.aggregation.AggregationMaintenanceRunResponse;
import com.prodigalgal.ircs.contracts.aggregation.AggregationResetStepResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

class InternalAggregationControllerTest {

    private final AggregationService aggregationService = org.mockito.Mockito.mock(AggregationService.class);
    private final AggregationInternalAccessPolicy accessPolicy =
            org.mockito.Mockito.mock(AggregationInternalAccessPolicy.class);
    private final ObjectProvider<AggregationWorkQueueWorker> workerProvider = org.mockito.Mockito.mock(ObjectProvider.class);
    private final InternalAggregationController controller =
            new InternalAggregationController(aggregationService, accessPolicy, workerProvider);

    @Test
    void recalculateDirtyUnifiedChecksAccessAndDelegatesToService() {
        UUID unifiedId = UUID.randomUUID();
        AggregationMaintenanceRunResponse response = new AggregationMaintenanceRunResponse(
                "unified-recalculate",
                1,
                3,
                List.of(unifiedId));
        when(aggregationService.recalculateDirtyUnified(4)).thenReturn(response);

        ResponseEntity<AggregationMaintenanceRunResponse> result = controller.recalculateDirtyUnified(
                4,
                "ops-service",
                "token",
                "aggregation:maintenance ops:maintenance");

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
        assertEquals(response, result.getBody());
        verify(accessPolicy).assertAccess("ops-service", "token", "aggregation:maintenance ops:maintenance");
        verify(aggregationService).recalculateDirtyUnified(4);
    }

    @Test
    void recalculateUnifiedChecksAccessAndDelegatesToService() {
        UUID unifiedId = UUID.randomUUID();
        when(aggregationService.recalculateUnified(unifiedId)).thenReturn(2);

        ResponseEntity<InternalAggregationController.RecalculateResponse> result = controller.recalculateUnified(
                unifiedId,
                "ops-service",
                "token",
                "aggregation:maintenance");

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(unifiedId, result.getBody().unifiedVideoId());
        assertEquals(2, result.getBody().sourceCount());
        verify(accessPolicy).assertAccess("ops-service", "token", "aggregation:maintenance");
        verify(aggregationService).recalculateUnified(unifiedId);
    }

    @Test
    void prepareAggregationResetChecksAccessAndDelegatesToService() {
        UUID rawId = UUID.randomUUID();
        AggregationResetStepResponse response = new AggregationResetStepResponse(
                "aggregation-reset",
                "prepare",
                4,
                2,
                3,
                9,
                List.of(rawId));
        when(aggregationService.prepareAggregationReset(5)).thenReturn(response);

        ResponseEntity<AggregationResetStepResponse> result = controller.prepareAggregationReset(
                5,
                "ops-service",
                "token",
                "aggregation:maintenance");

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
        assertEquals(response, result.getBody());
        verify(accessPolicy).assertAccess("ops-service", "token", "aggregation:maintenance");
        verify(aggregationService).prepareAggregationReset(5);
    }

    @Test
    void markAggregationResetRawPendingChecksAccessAndDelegatesToService() {
        UUID rawId = UUID.randomUUID();
        AggregationResetStepResponse response = new AggregationResetStepResponse(
                "aggregation-reset",
                "mark-raw-pending",
                4,
                0,
                0,
                4,
                List.of(rawId));
        when(aggregationService.markAllRawAggregationPending(6)).thenReturn(response);

        ResponseEntity<AggregationResetStepResponse> result = controller.markAggregationResetRawPending(
                6,
                "ops-service",
                "token",
                "aggregation:maintenance");

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
        assertEquals(response, result.getBody());
        verify(accessPolicy).assertAccess("ops-service", "token", "aggregation:maintenance");
        verify(aggregationService).markAllRawAggregationPending(6);
    }

    @Test
    void enqueuePendingRawWorkChecksAccessAndDelegatesToService() {
        UUID rawId = UUID.randomUUID();
        AggregationMaintenanceRunResponse response = new AggregationMaintenanceRunResponse(
                "aggregation-pending-backfill",
                1,
                1,
                List.of(rawId));
        when(aggregationService.enqueuePendingRawWork(7)).thenReturn(response);

        ResponseEntity<AggregationMaintenanceRunResponse> result = controller.enqueuePendingRawWork(
                7,
                "ops-service",
                "token",
                "aggregation:maintenance");

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
        assertEquals(response, result.getBody());
        verify(accessPolicy).assertAccess("ops-service", "token", "aggregation:maintenance");
        verify(aggregationService).enqueuePendingRawWork(7);
    }

    @Test
    void backfillUnifiedCoversChecksAccessAndDelegatesToService() {
        UUID unifiedId = UUID.randomUUID();
        AggregationMaintenanceRunResponse response = new AggregationMaintenanceRunResponse(
                "aggregation-cover-backfill",
                1,
                1,
                List.of(unifiedId));
        when(aggregationService.backfillUnifiedCovers(8)).thenReturn(response);

        ResponseEntity<AggregationMaintenanceRunResponse> result = controller.backfillUnifiedCovers(
                8,
                "ops-service",
                "token",
                "aggregation:maintenance");

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
        assertEquals(response, result.getBody());
        verify(accessPolicy).assertAccess("ops-service", "token", "aggregation:maintenance");
        verify(aggregationService).backfillUnifiedCovers(8);
    }

    @Test
    void backfillUnifiedAdultAssessmentsChecksAccessAndDelegatesToService() {
        UUID unifiedId = UUID.randomUUID();
        AggregationMaintenanceRunResponse response = new AggregationMaintenanceRunResponse(
                "aggregation-adult-assessment-backfill",
                1,
                1,
                List.of(unifiedId));
        when(aggregationService.backfillUnifiedAdultAssessments(9, false)).thenReturn(response);

        ResponseEntity<AggregationMaintenanceRunResponse> result = controller.backfillUnifiedAdultAssessments(
                9,
                false,
                "ops-service",
                "token",
                "aggregation:maintenance");

        assertEquals(HttpStatus.ACCEPTED, result.getStatusCode());
        assertEquals(response, result.getBody());
        verify(accessPolicy).assertAccess("ops-service", "token", "aggregation:maintenance");
        verify(aggregationService).backfillUnifiedAdultAssessments(9, false);
    }

    @Test
    void workQueueStateChecksAccessAndReturnsNullWhenWorkerUnavailable() {
        when(workerProvider.getIfAvailable()).thenReturn(null);

        ResponseEntity<AggregationWorkQueueState> result = controller.workQueueState(
                "ops-service",
                "token",
                "aggregation:maintenance");

        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals(null, result.getBody());
        verify(accessPolicy).assertAccess("ops-service", "token", "aggregation:maintenance");
    }
}

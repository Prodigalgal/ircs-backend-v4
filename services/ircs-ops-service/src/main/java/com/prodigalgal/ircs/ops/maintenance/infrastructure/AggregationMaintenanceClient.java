package com.prodigalgal.ircs.ops.maintenance.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundCircuitOpenException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.aggregation.AggregationMaintenanceRunResponse;
import com.prodigalgal.ircs.contracts.aggregation.AggregationResetStepResponse;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import com.prodigalgal.ircs.ops.maintenance.application.MaintenanceAggregationClient;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class AggregationMaintenanceClient implements MaintenanceAggregationClient {

    private static final String RECALCULATE_DIRTY_PATH = "/internal/v1/aggregation/unified-videos/recalculate-dirty";
    private static final String ENQUEUE_PENDING_RAW_PATH = "/internal/v1/aggregation/raw-videos/enqueue-pending";
    private static final String BACKFILL_UNIFIED_COVERS_PATH = "/internal/v1/aggregation/unified-videos/backfill-covers";
    private static final String BACKFILL_UNIFIED_ADULT_ASSESSMENTS_PATH =
            "/internal/v1/aggregation/unified-videos/backfill-adult-assessments";
    private static final String RESET_PREPARE_PATH = "/internal/v1/aggregation/reset/prepare";
    private static final String RESET_MARK_RAW_PENDING_PATH = "/internal/v1/aggregation/reset/mark-raw-pending";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;
    private final OpsConfigValues configValues;

    AggregationMaintenanceClient(
            ObjectMapper objectMapper,
            OutboundHttpClient httpClient,
            OpsConfigValues configValues) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.configValues = configValues;
    }

    @Override
    public MaintenanceRunResult recalculateDirtyUnified(String correlationId) {
        return maintenanceRun("unified-recalculate", limitUri(RECALCULATE_DIRTY_PATH), correlationId);
    }

    @Override
    public MaintenanceRunResult enqueuePendingRawWork(String correlationId) {
        return maintenanceRun("aggregation-pending-backfill", limitUri(ENQUEUE_PENDING_RAW_PATH), correlationId);
    }

    @Override
    public MaintenanceRunResult backfillUnifiedCovers(String correlationId) {
        return maintenanceRun("aggregation-cover-backfill", limitUri(BACKFILL_UNIFIED_COVERS_PATH), correlationId);
    }

    @Override
    public MaintenanceRunResult backfillUnifiedAdultAssessments(String correlationId) {
        return maintenanceRun(
                "aggregation-adult-assessment-backfill",
                limitUri(BACKFILL_UNIFIED_ADULT_ASSESSMENTS_PATH),
                correlationId);
    }

    private MaintenanceRunResult maintenanceRun(String fallbackTaskName, URI uri, String correlationId) {
        try {
            OutboundHttpResponse response = post(uri, correlationId);
            AggregationMaintenanceRunResponse body =
                    objectMapper.readValue(response.body(), AggregationMaintenanceRunResponse.class);
            return new MaintenanceRunResult(
                    StringUtils.hasText(body.taskName()) ? body.taskName() : fallbackTaskName,
                    Math.max(0, body.candidates()),
                    Math.max(0, body.processed()),
                    body.entityIds());
        } catch (OutboundCircuitOpenException ex) {
            throw new IllegalStateException("aggregation-worker maintenance failed: outbound circuit open", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("aggregation-worker maintenance failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("aggregation-worker maintenance failed: interrupted", ex);
        }
    }

    @Override
    public MaintenanceRunResult prepareAggregationReset(String correlationId) {
        return resetStep("aggregation-reset.prepare", resetPrepareUri(), correlationId);
    }

    @Override
    public MaintenanceRunResult markAggregationResetRawPending(String correlationId) {
        return resetStep("aggregation-reset.mark-raw-pending", resetMarkRawPendingUri(), correlationId);
    }

    private MaintenanceRunResult resetStep(String taskName, URI uri, String correlationId) {
        try {
            OutboundHttpResponse response = post(uri, correlationId);
            AggregationResetStepResponse body = objectMapper.readValue(response.body(), AggregationResetStepResponse.class);
            return new MaintenanceRunResult(
                    taskName,
                    safeInt(body.rawVideoCount()),
                    safeInt(body.changedRows()),
                    ids(body.sampleRawVideoIds()));
        } catch (OutboundCircuitOpenException ex) {
            throw new IllegalStateException("aggregation-worker maintenance failed: outbound circuit open", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("aggregation-worker maintenance failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("aggregation-worker maintenance failed: interrupted", ex);
        }
    }

    private OutboundHttpResponse post(URI uri, String correlationId)
            throws IOException, InterruptedException {
        OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(
                        configValues.aggregationOwnerRequestTimeout())
                .withCallerCircuitBreakerKey("ops-aggregation-maintenance");
        OutboundHttpRequest request = OutboundHttpRequest.post(uri, policy)
                .withHeader("Accept", "application/json");
        if (StringUtils.hasText(correlationId)) {
            request = request.withHeader(CORRELATION_HEADER, correlationId.trim());
        }
        request = InternalServiceAuthHeaders.apply(
                request,
                configValues.aggregationOwnerServiceId(),
                configValues.aggregationOwnerServiceToken(),
                configValues.aggregationOwnerScopes());

        OutboundHttpResponse response = httpClient.execute(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("aggregation-worker maintenance failed: upstream status "
                    + response.statusCode());
        }
        return response;
    }

    private URI limitUri(String path) {
        String baseUrl = configValues.aggregationOwnerBaseUrl();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + path
                + "?limit=" + configValues.aggregationMaintenanceDevLimit());
    }

    private URI resetPrepareUri() {
        return resetUri(RESET_PREPARE_PATH);
    }

    private URI resetMarkRawPendingUri() {
        return resetUri(RESET_MARK_RAW_PENDING_PATH);
    }

    private URI resetUri(String path) {
        String baseUrl = configValues.aggregationOwnerBaseUrl();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + path
                + "?sampleLimit=" + configValues.aggregationMaintenanceDevLimit());
    }

    private int safeInt(long value) {
        if (value <= 0) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private List<UUID> ids(List<UUID> ids) {
        return ids == null ? List.of() : ids;
    }
}

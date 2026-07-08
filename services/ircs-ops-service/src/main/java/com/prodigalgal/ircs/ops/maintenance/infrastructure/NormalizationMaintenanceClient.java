package com.prodigalgal.ircs.ops.maintenance.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundCircuitOpenException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.normalization.NormalizationMaintenanceRunResponse;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import com.prodigalgal.ircs.ops.maintenance.application.MaintenanceNormalizationClient;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class NormalizationMaintenanceClient implements MaintenanceNormalizationClient {

    private static final String RESET_NORMALIZATION_PATH =
            "/internal/v1/normalization/maintenance/raw-videos/reset-normalization";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;
    private final OpsConfigValues configValues;

    NormalizationMaintenanceClient(
            ObjectMapper objectMapper,
            @Qualifier("opsOutboundHttpClient")
            OutboundHttpClient httpClient,
            OpsConfigValues configValues) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.configValues = configValues;
    }

    @Override
    public MaintenanceRunResult resetAllNormalization(String correlationId) {
        try {
            OutboundHttpResponse response = post(
                    resetNormalizationUri(),
                    correlationId,
                    configValues.normalizationOwnerRequestTimeout(),
                    "ops-normalization-maintenance",
                    "normalization-worker maintenance failed");
            NormalizationMaintenanceRunResponse body =
                    objectMapper.readValue(response.body(), NormalizationMaintenanceRunResponse.class);
            return new MaintenanceRunResult(
                    "sanitize",
                    safeInt(body.rawVideoCount()),
                    safeInt(body.enqueuedRows() > 0 ? body.enqueuedRows() : body.changedRows()),
                    ids(body.sampleRawVideoIds()));
        } catch (OutboundCircuitOpenException ex) {
            throw new IllegalStateException("normalization-worker maintenance failed: outbound circuit open", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("normalization-worker maintenance failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("normalization-worker maintenance failed: interrupted", ex);
        }
    }

    private OutboundHttpResponse post(
            URI uri,
            String correlationId,
            java.time.Duration requestTimeout,
            String circuitBreakerKey,
            String failurePrefix)
            throws IOException, InterruptedException {
        OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(requestTimeout)
                .withCallerCircuitBreakerKey(circuitBreakerKey);
        OutboundHttpRequest request = OutboundHttpRequest.post(uri, policy)
                .withHeader("Accept", "application/json");
        if (StringUtils.hasText(correlationId)) {
            request = request.withHeader(CORRELATION_HEADER, correlationId.trim());
        }
        request = InternalServiceAuthHeaders.apply(
                request,
                configValues.normalizationOwnerServiceId(),
                configValues.normalizationOwnerServiceToken(),
                configValues.normalizationOwnerScopes());

        OutboundHttpResponse response = httpClient.execute(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(failurePrefix + ": upstream status " + response.statusCode());
        }
        return response;
    }

    private URI resetNormalizationUri() {
        String baseUrl = configValues.normalizationOwnerBaseUrl();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + RESET_NORMALIZATION_PATH
                + "?sampleLimit=" + configValues.normalizationMaintenanceDevLimit()
                + "&enqueue=true"
                + "&batchSize=" + configValues.normalizationMaintenanceBatchSize());
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

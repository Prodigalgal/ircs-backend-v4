package com.prodigalgal.ircs.ops.maintenance.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundCircuitOpenException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.search.SearchEntityType;
import com.prodigalgal.ircs.contracts.search.SearchIndexMaintenanceResponse;
import com.prodigalgal.ircs.contracts.search.SearchSyncTaskBatchEnqueueRequest;
import com.prodigalgal.ircs.contracts.search.SearchSyncTaskEnqueueResponse;
import com.prodigalgal.ircs.contracts.search.SyncOperation;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import com.prodigalgal.ircs.ops.maintenance.application.MaintenanceSearchSyncClient;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class SearchServiceMaintenanceSyncClient implements MaintenanceSearchSyncClient {

    private static final String BATCH_PATH = "/internal/v1/search/sync-tasks/batch";
    private static final String HARD_RESET_PATH = "/internal/v1/search/index-maintenance/%s/hard-reset";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;
    private final OpsConfigValues configValues;

    SearchServiceMaintenanceSyncClient(
            ObjectMapper objectMapper,
            @Qualifier("opsOutboundHttpClient")
            OutboundHttpClient httpClient,
            OpsConfigValues configValues) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.configValues = configValues;
    }

    @Override
    public int enqueueIndex(List<UUID> entityIds, SearchEntityType entityType, String correlationId) {
        List<UUID> ids = entityIds == null
                ? List.of()
                : entityIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return 0;
        }
        SearchEntityType safeEntityType = entityType == null ? SearchEntityType.UNIFIED_VIDEO : entityType;

        try {
            byte[] body = objectMapper.writeValueAsBytes(new SearchSyncTaskBatchEnqueueRequest(
                    ids,
                    safeEntityType,
                    SyncOperation.INDEX,
                    "ops-service",
                    correlationId));
            OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(configValues.searchOwnerRequestTimeout())
                    .withCallerCircuitBreakerKey("ops-search-maintenance");
            OutboundHttpRequest request = OutboundHttpRequest.post(batchUri(), policy)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .withBody(body);
            if (StringUtils.hasText(correlationId)) {
                request = request.withHeader(CORRELATION_HEADER, correlationId.trim());
            }
            request = InternalServiceAuthHeaders.apply(
                    request,
                    configValues.searchOwnerServiceId(),
                    configValues.searchOwnerServiceToken(),
                    configValues.searchOwnerScopes());

            OutboundHttpResponse response = httpClient.execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("search-service maintenance sync failed: upstream status "
                        + response.statusCode());
            }
            if (response.body().length == 0) {
                return ids.size();
            }
            return objectMapper.readValue(response.body(), SearchSyncTaskEnqueueResponse.class).accepted();
        } catch (OutboundCircuitOpenException ex) {
            throw new IllegalStateException("search-service maintenance sync failed: outbound circuit open", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("search-service maintenance sync failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("search-service maintenance sync failed: interrupted", ex);
        }
    }

    @Override
    public int hardResetIndex(SearchEntityType entityType, String correlationId) {
        SearchEntityType safeEntityType = entityType == null ? SearchEntityType.UNIFIED_VIDEO : entityType;
        try {
            OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(configValues.searchOwnerRequestTimeout())
                    .withCallerCircuitBreakerKey("ops-search-maintenance");
            OutboundHttpRequest request = OutboundHttpRequest.post(hardResetUri(safeEntityType), policy)
                    .withHeader("Accept", "application/json");
            if (StringUtils.hasText(correlationId)) {
                request = request.withHeader(CORRELATION_HEADER, correlationId.trim());
            }
            request = InternalServiceAuthHeaders.apply(
                    request,
                    configValues.searchOwnerServiceId(),
                    configValues.searchOwnerServiceToken(),
                    configValues.searchOwnerScopes());

            OutboundHttpResponse response = httpClient.execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("search-service index maintenance failed: upstream status "
                        + response.statusCode());
            }
            if (response.body().length == 0) {
                return 1;
            }
            SearchIndexMaintenanceResponse body =
                    objectMapper.readValue(response.body(), SearchIndexMaintenanceResponse.class);
            return Math.max(0, body.deletedSyncTasks()) + (body.recreated() ? 1 : 0);
        } catch (OutboundCircuitOpenException ex) {
            throw new IllegalStateException("search-service index maintenance failed: outbound circuit open", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("search-service index maintenance failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("search-service index maintenance failed: interrupted", ex);
        }
    }

    private URI batchUri() {
        String baseUrl = configValues.searchOwnerBaseUrl();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + BATCH_PATH);
    }

    private URI hardResetUri(SearchEntityType entityType) {
        String baseUrl = configValues.searchOwnerBaseUrl();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + HARD_RESET_PATH.formatted(entityType.name()));
    }
}

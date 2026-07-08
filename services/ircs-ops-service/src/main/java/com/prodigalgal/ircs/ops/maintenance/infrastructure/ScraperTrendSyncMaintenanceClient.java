package com.prodigalgal.ircs.ops.maintenance.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundCircuitOpenException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyResponse;
import com.prodigalgal.ircs.contracts.trend.TrendSyncRunResponse;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import com.prodigalgal.ircs.ops.maintenance.application.MaintenanceTrendSyncClient;
import com.prodigalgal.ircs.ops.maintenance.domain.MaintenanceRunResult;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ScraperTrendSyncMaintenanceClient implements MaintenanceTrendSyncClient {

    private static final String SYNC_PATH = "/internal/v1/scraper/trends/sync";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;
    private final OpsConfigValues configValues;

    ScraperTrendSyncMaintenanceClient(
            ObjectMapper objectMapper,
            @Qualifier("opsOutboundHttpClient")
            OutboundHttpClient httpClient,
            OpsConfigValues configValues) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.configValues = configValues;
    }

    @Override
    public MaintenanceRunResult syncTrends(String correlationId) {
        try {
            OutboundHttpResponse response = post(syncUri(), correlationId);
            TrendSyncRunResponse body = objectMapper.readValue(response.body(), TrendSyncRunResponse.class);
            TrendSyncApplyResponse apply = body.applyResult();
            int selected = safeInt(body.fetchedItems());
            int published = apply == null
                    ? 0
                    : safeInt(apply.updatedByExternalId() + apply.updatedByTitleMatch() + apply.createdGhosts());
            return new MaintenanceRunResult(
                    "trend-sync",
                    selected,
                    published,
                    entityIds(apply));
        } catch (OutboundCircuitOpenException ex) {
            throw new IllegalStateException("scraper-service trend sync failed: outbound circuit open", ex);
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("scraper-service trend sync failed: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("scraper-service trend sync failed: interrupted", ex);
        }
    }

    private OutboundHttpResponse post(URI uri, String correlationId)
            throws IOException, InterruptedException {
        OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(
                        configValues.scraperOwnerRequestTimeout())
                .withCallerCircuitBreakerKey("ops-scraper-trend-maintenance");
        OutboundHttpRequest request = OutboundHttpRequest.post(uri, policy)
                .withHeader("Accept", "application/json");
        if (StringUtils.hasText(correlationId)) {
            request = request.withHeader(CORRELATION_HEADER, correlationId.trim());
        }
        request = InternalServiceAuthHeaders.apply(
                request,
                configValues.scraperOwnerServiceId(),
                configValues.scraperOwnerServiceToken(),
                configValues.scraperOwnerScopes());

        OutboundHttpResponse response = httpClient.execute(request);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("scraper-service trend sync failed: upstream status "
                    + response.statusCode());
        }
        return response;
    }

    private URI syncUri() {
        String baseUrl = configValues.scraperOwnerBaseUrl();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + SYNC_PATH);
    }

    private int safeInt(long value) {
        if (value <= 0) {
            return 0;
        }
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private List<UUID> entityIds(TrendSyncApplyResponse apply) {
        if (apply == null) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>(apply.updatedUnifiedVideoIds());
        ids.addAll(apply.createdUnifiedVideoIds());
        return ids;
    }
}

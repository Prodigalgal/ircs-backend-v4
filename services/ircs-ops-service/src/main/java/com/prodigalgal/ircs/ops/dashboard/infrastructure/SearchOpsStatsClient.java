package com.prodigalgal.ircs.ops.dashboard.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.ops.config.OpsConfigValues;
import java.io.IOException;
import java.net.URI;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class SearchOpsStatsClient {

    private static final String SEARCH_SYNC_STATS_PATH = "/api/v1/search/ops/search-sync-tasks/stats";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;
    private final OpsConfigValues configValues;

    SearchOpsStatsClient(
            ObjectMapper objectMapper,
            OutboundHttpClient httpClient,
            OpsConfigValues configValues) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.configValues = configValues;
    }

    public Map<String, Object> currentStats() {
        try {
            OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(configValues.searchOwnerRequestTimeout())
                    .withCallerCircuitBreakerKey("ops-search-stats");
            OutboundHttpRequest request = OutboundHttpRequest.get(statsUri(), policy)
                    .withHeader("Accept", "application/json");
            OutboundHttpResponse response = httpClient.execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300 || response.body().length == 0) {
                return Map.of("available", false, "unavailableReason", "HTTP_" + response.statusCode());
            }
            return objectMapper.readValue(response.body(), MAP_TYPE);
        } catch (IOException | RuntimeException ex) {
            log.debug("Search ops stats fetch failed: {}", ex.getMessage());
            return Map.of("available", false, "unavailableReason", ex.getClass().getSimpleName());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Map.of("available", false, "unavailableReason", "Interrupted");
        }
    }

    private URI statsUri() {
        String baseUrl = configValues.searchOwnerBaseUrl();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + SEARCH_SYNC_STATS_PATH);
    }
}

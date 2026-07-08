package com.prodigalgal.ircs.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleRequest;
import com.prodigalgal.ircs.contracts.trend.TrendDiscoveryScheduleResponse;
import java.net.URI;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class TrendDiscoveryTaskClient {

    private static final String DISCOVERY_PATH = "/internal/v1/tasks/trend-discovery";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;
    private final ScraperTrendConfigValues configValues;

    TrendDiscoveryTaskClient(
            ObjectMapper objectMapper,
            OutboundHttpClient httpClient,
            ScraperTrendConfigValues configValues) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.configValues = configValues;
    }

    static TrendDiscoveryTaskClient forTest(
            ObjectMapper objectMapper,
            OutboundHttpClient httpClient,
            ScraperTrendConfigValues configValues) {
        return new TrendDiscoveryTaskClient(objectMapper, httpClient, configValues);
    }

    TrendDiscoveryScheduleResponse schedule(List<String> keywords, String correlationId) {
        if (!configValues.trendDiscoveryEnabled()) {
            return new TrendDiscoveryScheduleResponse(
                    "trend-discovery",
                    keywords == null ? 0 : keywords.size(),
                    0,
                    0,
                    0,
                    0,
                    List.of(),
                    List.of("trend discovery is disabled in scraper-service"));
        }
        try {
            TrendDiscoveryScheduleRequest request = new TrendDiscoveryScheduleRequest(
                    keywords,
                    1,
                    1,
                    0,
                    false,
                    configValues.trendDiscoveryMaxDataSources());
            byte[] body = objectMapper.writeValueAsBytes(request);
            OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(
                            configValues.taskOwnerRequestTimeout())
                    .withCallerCircuitBreakerKey("scraper-trend-task-discovery");
            OutboundHttpRequest outbound = OutboundHttpRequest.post(discoveryUri(), policy)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .withBody(body);
            if (StringUtils.hasText(correlationId)) {
                outbound = outbound.withHeader(CORRELATION_HEADER, correlationId.trim());
            }
            outbound = InternalServiceAuthHeaders.apply(
                    outbound,
                    configValues.taskOwnerServiceId(),
                    configValues.taskOwnerServiceToken(),
                    configValues.taskOwnerScopes());
            OutboundHttpResponse response = httpClient.execute(outbound);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("task-service trend discovery failed: upstream status "
                        + response.statusCode());
            }
            return objectMapper.readValue(response.body(), TrendDiscoveryScheduleResponse.class);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("task-service trend discovery failed: interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("task-service trend discovery failed: " + ex.getMessage(), ex);
        }
    }

    private URI discoveryUri() {
        String baseUrl = configValues.taskOwnerBaseUrl();
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalized + DISCOVERY_PATH);
    }
}

package com.prodigalgal.ircs.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundTransportRouter;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyRequest;
import com.prodigalgal.ircs.contracts.trend.TrendSyncApplyResponse;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class TrendSyncContentClient {

    private static final String APPLY_PATH = "/internal/v1/content/trends/apply";
    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;
    private final ScraperTrendConfigValues configValues;

    TrendSyncContentClient(
            ObjectMapper objectMapper,
            ObjectProvider<OutboundHttpClient> httpClientProvider,
            ScraperTrendConfigValues configValues) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient(httpClientProvider);
        this.configValues = configValues;
    }

    TrendSyncApplyResponse apply(TrendSyncApplyRequest request, String correlationId) {
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(
                            configValues.contentOwnerRequestTimeout())
                    .withCallerCircuitBreakerKey("scraper-trend-content-maintenance");
            OutboundHttpRequest outbound = OutboundHttpRequest.post(applyUri(), policy)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .withBody(body);
            if (StringUtils.hasText(correlationId)) {
                outbound = outbound.withHeader(CORRELATION_HEADER, correlationId.trim());
            }
            outbound = InternalServiceAuthHeaders.apply(
                    outbound,
                    configValues.contentOwnerServiceId(),
                    configValues.contentOwnerServiceToken(),
                    configValues.contentOwnerScopes());
            OutboundHttpResponse response = httpClient.execute(outbound);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("content-service trend apply failed: upstream status "
                        + response.statusCode());
            }
            return objectMapper.readValue(response.body(), TrendSyncApplyResponse.class);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("content-service trend apply failed: interrupted", ex);
        } catch (Exception ex) {
            throw new IllegalStateException("content-service trend apply failed: " + ex.getMessage(), ex);
        }
    }

    private URI applyUri() {
        String baseUrl = configValues.contentOwnerBaseUrl();
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalized + APPLY_PATH);
    }

    private static OutboundHttpClient httpClient(ObjectProvider<OutboundHttpClient> httpClientProvider) {
        if (httpClientProvider != null) {
            OutboundHttpClient provided = httpClientProvider.getIfUnique();
            if (provided != null) {
                return provided;
            }
        }
        return new OutboundHttpClient(
                new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                new OutboundTransportRouter());
    }
}

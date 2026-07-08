package com.prodigalgal.ircs.content.video.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.JdkOutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundCircuitOpenException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.content.config.ContentConfigValues;
import com.prodigalgal.ircs.contracts.search.AdminVideoSearchRequest;
import com.prodigalgal.ircs.contracts.search.AdminVideoSearchResult;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@Slf4j
class AdminVideoSearchClient {

    private static final String RAW_IDS_PATH = "/internal/v1/search/admin/videos/raw/ids";
    private static final String UNIFIED_IDS_PATH = "/internal/v1/search/admin/videos/unified/ids";

    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;
    private final ContentConfigValues configValues;
    private final Duration requestTimeout;
    private final String serviceId;
    private final String serviceToken;
    private final String serviceScopes;

    AdminVideoSearchClient(
            ObjectMapper objectMapper,
            ObjectProvider<OutboundHttpClient> httpClientProvider,
            ContentConfigValues configValues,
            @Value("${app.content.admin-video-search.request-timeout:PT3S}") Duration requestTimeout,
            @Value("${app.content.admin-video-search.service-id:content-service}") String serviceId,
            @Value("${app.content.admin-video-search.service-token:${APP_SEARCH_SERVICE_TOKEN:}}") String serviceToken,
            @Value("${app.content.admin-video-search.service-scopes:search:sync}") String serviceScopes) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClientProvider == null
                ? defaultHttpClient()
                : httpClientProvider.getIfAvailable(AdminVideoSearchClient::defaultHttpClient);
        this.configValues = configValues;
        this.requestTimeout = requestTimeout == null || requestTimeout.isZero() || requestTimeout.isNegative()
                ? Duration.ofSeconds(3)
                : requestTimeout;
        this.serviceId = serviceId;
        this.serviceToken = serviceToken;
        this.serviceScopes = serviceScopes;
    }

    Optional<AdminVideoSearchResult> searchRawIds(AdminVideoSearchRequest request) {
        return search(request, RAW_IDS_PATH, "raw");
    }

    Optional<AdminVideoSearchResult> searchUnifiedIds(AdminVideoSearchRequest request) {
        return search(request, UNIFIED_IDS_PATH, "unified");
    }

    private Optional<AdminVideoSearchResult> search(AdminVideoSearchRequest request, String path, String entityName) {
        if (!configValues.adminVideoSearchEsEnabled()) {
            return Optional.empty();
        }
        try {
            byte[] body = objectMapper.writeValueAsBytes(request);
            OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(requestTimeout)
                    .withCallerCircuitBreakerKey("content-admin-video-search");
            OutboundHttpRequest outbound = OutboundHttpRequest.post(searchUri(path), policy)
                    .withHeader("Content-Type", "application/json")
                    .withHeader("Accept", "application/json")
                    .withBody(body);
            outbound = InternalServiceAuthHeaders.apply(outbound, serviceId, serviceToken, serviceScopes);
            OutboundHttpResponse response = httpClient.execute(outbound);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("Admin {} video ES discovery unavailable: upstream status {}", entityName, response.statusCode());
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(response.body(), AdminVideoSearchResult.class));
        } catch (OutboundCircuitOpenException ex) {
            log.warn("Admin {} video ES discovery skipped: outbound circuit open", entityName);
            return Optional.empty();
        } catch (IOException | IllegalArgumentException ex) {
            log.warn("Admin {} video ES discovery skipped: {}", entityName, ex.getMessage());
            return Optional.empty();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Admin {} video ES discovery skipped: interrupted", entityName);
            return Optional.empty();
        }
    }

    private URI searchUri(String path) {
        String baseUrl = configValues.searchBaseUrl();
        String normalizedBase = StringUtils.trimTrailingCharacter(baseUrl, '/');
        return URI.create(normalizedBase + path);
    }

    private static OutboundHttpClient defaultHttpClient() {
        return new OutboundHttpClient(
                new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                new JdkOutboundTransport());
    }
}

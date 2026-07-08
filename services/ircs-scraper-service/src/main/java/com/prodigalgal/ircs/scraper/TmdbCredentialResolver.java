package com.prodigalgal.ircs.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.credential.ProviderCredentialLease;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Slf4j
class TmdbCredentialResolver {

    private static final String PROVIDER = "TMDB";
    private static final String API_KEY = "api_key";

    private final ObjectMapper objectMapper;
    private final OutboundHttpClient httpClient;
    private final ScraperTrendConfigValues configValues;

    TmdbCredentialResolver(
            ObjectMapper objectMapper,
            @Qualifier("scraperOutboundHttpClient")
            OutboundHttpClient httpClient,
            ScraperTrendConfigValues configValues) {
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.configValues = configValues;
    }

    static TmdbCredentialResolver forTest(
            ObjectMapper objectMapper,
            OutboundHttpClient httpClient,
            ScraperTrendConfigValues configValues) {
        return new TmdbCredentialResolver(objectMapper, httpClient, configValues);
    }

    Optional<String> resolveApiKey() {
        Optional<String> leased = leaseApiKey();
        if (leased.isPresent()) {
            return leased;
        }
        String fallback = configValues.tmdbApiKey();
        return StringUtils.hasText(fallback) ? Optional.of(fallback.trim()) : Optional.empty();
    }

    private Optional<String> leaseApiKey() {
        if (!configValues.tmdbCredentialServiceEnabled()) {
            return Optional.empty();
        }
        try {
            OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(
                            configValues.tmdbCredentialServiceRequestTimeout())
                    .withCallerCircuitBreakerKey("scraper-tmdb-credential-service");
            OutboundHttpRequest request = OutboundHttpRequest.get(leaseUri(), policy)
                    .withHeader("Accept", "application/json");
            request = InternalServiceAuthHeaders.apply(
                    request,
                    configValues.tmdbCredentialServiceId(),
                    configValues.tmdbCredentialServiceToken(),
                    configValues.tmdbCredentialServiceScopes());
            OutboundHttpResponse response = httpClient.execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("TMDB credential lease skipped: credential-service returned HTTP {}", response.statusCode());
                return Optional.empty();
            }
            ProviderCredentialLease[] leases = objectMapper.readValue(response.body(), ProviderCredentialLease[].class);
            if (leases == null) {
                return Optional.empty();
            }
            return Arrays.stream(leases)
                    .map(this::apiKey)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .findFirst();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("TMDB credential lease interrupted");
            return Optional.empty();
        } catch (Exception ex) {
            log.warn("TMDB credential lease skipped: {}", ex.getMessage());
            return Optional.empty();
        }
    }

    private URI leaseUri() {
        return UriComponentsBuilder.fromUriString(configValues.tmdbCredentialServiceBaseUrl())
                .path("/internal/credentials/providers/{provider}/leases")
                .queryParam("requiredPayloadKey", API_KEY)
                .queryParam("limit", configValues.tmdbCredentialServiceLeaseLimit())
                .build(PROVIDER);
    }

    private String apiKey(ProviderCredentialLease lease) {
        return lease == null || lease.getSecretPayload() == null ? null : lease.getSecretPayload().get(API_KEY);
    }
}

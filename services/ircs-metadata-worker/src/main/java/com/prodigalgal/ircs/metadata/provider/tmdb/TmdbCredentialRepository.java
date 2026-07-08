package com.prodigalgal.ircs.metadata.provider.tmdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.JdkOutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.credential.ProviderCredentialLease;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import com.prodigalgal.ircs.metadata.provider.credential.MetadataCredentialServiceProperties;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

@Component
public class TmdbCredentialRepository {

    private static final String PROVIDER = "TMDB";
    private static final String API_KEY = "api_key";

    private OutboundHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MetadataCredentialServiceProperties properties;
    public TmdbCredentialRepository(
            ObjectMapper objectMapper,
            MetadataCredentialServiceProperties properties) {
        this.httpClient = new OutboundHttpClient(
                new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                new JdkOutboundTransport(properties.getRequestTimeout()));
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    static TmdbCredentialRepository forTest(
            OutboundHttpClient httpClient,
            ObjectMapper objectMapper,
            MetadataCredentialServiceProperties properties) {
        TmdbCredentialRepository repository = new TmdbCredentialRepository(objectMapper, properties);
        repository.httpClient = httpClient;
        return repository;
    }

    public List<TmdbCredential> findEnabled() {
        try {
            OutboundHttpRequest request = OutboundHttpRequest.get(
                    credentialLeaseUri(),
                    OutboundHttpPolicy.internalService(properties.getRequestTimeout()));
            request = InternalServiceAuthHeaders.apply(
                    request,
                    properties.getServiceId(),
                    properties.getToken(),
                    properties.getScopes());
            OutboundHttpResponse response = httpClient.execute(request);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new OutboundHttpException("Credential-service returned status " + response.statusCode());
            }
            ProviderCredentialLease[] leases = objectMapper.readValue(response.body(), ProviderCredentialLease[].class);
            if (leases == null) {
                return List.of();
            }
            return java.util.Arrays.stream(leases)
                    .map(this::mapLease)
                    .filter(credential -> credential.apiKey() != null && !credential.apiKey().isBlank())
                    .toList();
        } catch (IOException ex) {
            throw new MetadataProviderRetryableException(
                    "CREDENTIAL_SERVICE_UNAVAILABLE",
                    "Unable to lease TMDB credentials from credential-service",
                    ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MetadataProviderRetryableException(
                    "CREDENTIAL_SERVICE_UNAVAILABLE",
                    "Unable to lease TMDB credentials from credential-service",
                    ex);
        }
    }

    private URI credentialLeaseUri() {
        return UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                .path("/internal/credentials/providers/{provider}/leases")
                .queryParam("requiredPayloadKey", API_KEY)
                .queryParam("limit", properties.getLeaseLimit())
                .build(PROVIDER);
    }

    private TmdbCredential mapLease(ProviderCredentialLease lease) {
        return new TmdbCredential(
                lease.getId(),
                lease.getSecretPayload() == null ? null : lease.getSecretPayload().get(API_KEY),
                lease.getRateLimit(),
                lease.getRateLimitUnit(),
                lease.getPriority());
    }
}

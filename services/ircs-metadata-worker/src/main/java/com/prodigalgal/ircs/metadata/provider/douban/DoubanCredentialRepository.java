package com.prodigalgal.ircs.metadata.provider.douban;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.JdkOutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import com.prodigalgal.ircs.contracts.credential.ProviderCredentialLease;
import com.prodigalgal.ircs.metadata.provider.credential.MetadataCredentialServiceProperties;
import java.net.URI;
import java.util.Arrays;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@Slf4j
public class DoubanCredentialRepository {

    private static final String PROVIDER = "DOUBAN";
    private static final String COOKIE = "cookie";
    private static final String USER_AGENT = "user_agent";

    private OutboundHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final MetadataCredentialServiceProperties properties;
    public DoubanCredentialRepository(
            ObjectMapper objectMapper,
            MetadataCredentialServiceProperties properties) {
        this.httpClient = new OutboundHttpClient(
                new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                new JdkOutboundTransport(properties.getRequestTimeout()));
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    static DoubanCredentialRepository forTest(
            OutboundHttpClient httpClient,
            ObjectMapper objectMapper,
            MetadataCredentialServiceProperties properties) {
        DoubanCredentialRepository repository = new DoubanCredentialRepository(objectMapper, properties);
        repository.httpClient = httpClient;
        return repository;
    }

    public Optional<DoubanCredential> findPreferred() {
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
                log.info("Douban optional credential lease unavailable, status={}", response.statusCode());
                return Optional.empty();
            }
            ProviderCredentialLease[] leases = objectMapper.readValue(response.body(), ProviderCredentialLease[].class);
            if (leases == null) {
                return Optional.empty();
            }
            return Arrays.stream(leases)
                    .map(this::mapLease)
                    .filter(this::hasUsableHeader)
                    .findFirst();
        } catch (Exception ex) {
            log.info("Douban optional credential lease failed; continuing without credential");
            return Optional.empty();
        }
    }

    private URI credentialLeaseUri() {
        return UriComponentsBuilder.fromUriString(properties.getBaseUrl())
                .path("/internal/credentials/providers/{provider}/leases")
                .queryParam("limit", properties.getLeaseLimit())
                .build(PROVIDER);
    }

    private DoubanCredential mapLease(ProviderCredentialLease lease) {
        return new DoubanCredential(
                lease.getId(),
                lease.getSecretPayload() == null ? null : lease.getSecretPayload().get(COOKIE),
                lease.getSecretPayload() == null ? null : lease.getSecretPayload().get(USER_AGENT),
                lease.getRateLimit(),
                lease.getRateLimitUnit(),
                lease.getPriority());
    }

    private boolean hasUsableHeader(DoubanCredential credential) {
        return StringUtils.hasText(credential.cookie()) || StringUtils.hasText(credential.userAgent());
    }
}

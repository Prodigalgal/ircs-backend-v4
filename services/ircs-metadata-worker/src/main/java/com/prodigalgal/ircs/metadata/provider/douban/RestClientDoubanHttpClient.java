package com.prodigalgal.ircs.metadata.provider.douban;

import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.JdkOutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.metadata.provider.application.MetadataProviderTrafficLimiter;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderRetryableException;
import com.prodigalgal.ircs.metadata.provider.domain.MetadataProviderTerminalException;
import java.io.IOException;
import java.net.URI;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class RestClientDoubanHttpClient implements DoubanHttpClient {

    private static final String PROVIDER_CODE = "DOUBAN";

    private OutboundHttpClient httpClient;
    private final DoubanProviderProperties properties;
    private final MetadataProviderTrafficLimiter trafficLimiter;
    public RestClientDoubanHttpClient(
            DoubanProviderProperties properties,
            ObjectProvider<MetadataProviderTrafficLimiter> trafficLimiterProvider) {
        this.httpClient = new OutboundHttpClient(
                new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                new JdkOutboundTransport(properties.getRequestTimeout()));
        this.properties = properties;
        this.trafficLimiter = resolveTrafficLimiter(trafficLimiterProvider);
    }

    static RestClientDoubanHttpClient forTest(
            OutboundHttpClient httpClient,
            DoubanProviderProperties properties) {
        RestClientDoubanHttpClient client = new RestClientDoubanHttpClient(
                properties,
                trafficLimiterProvider(MetadataProviderTrafficLimiter.noop()));
        client.httpClient = httpClient;
        return client;
    }

    private static MetadataProviderTrafficLimiter resolveTrafficLimiter(
            ObjectProvider<MetadataProviderTrafficLimiter> trafficLimiterProvider) {
        return trafficLimiterProvider == null
                ? MetadataProviderTrafficLimiter.noop()
                : trafficLimiterProvider.getIfAvailable(MetadataProviderTrafficLimiter::noop);
    }

    private static ObjectProvider<MetadataProviderTrafficLimiter> trafficLimiterProvider(
            MetadataProviderTrafficLimiter trafficLimiter) {
        return new ObjectProvider<>() {
            @Override
            public MetadataProviderTrafficLimiter getObject(Object... args) {
                return trafficLimiter;
            }

            @Override
            public MetadataProviderTrafficLimiter getIfAvailable() {
                return trafficLimiter;
            }

            @Override
            public MetadataProviderTrafficLimiter getIfUnique() {
                return trafficLimiter;
            }

            @Override
            public MetadataProviderTrafficLimiter getObject() {
                return trafficLimiter;
            }
        };
    }

    @Override
    public Optional<String> getJson(URI uri, Optional<DoubanCredential> credential) {
        try {
            trafficLimiter.acquireProviderSlot(PROVIDER_CODE);
            OutboundHttpPolicy policy = OutboundHttpPolicy.publicFetch(properties.getRequestTimeout())
                    .withUserAgent(credential
                            .map(DoubanCredential::userAgent)
                            .filter(StringUtils::hasText)
                            .orElse(properties.getDefaultUserAgent()));
            OutboundHttpRequest request = OutboundHttpRequest.get(uri, policy);
            if (credential.isPresent() && StringUtils.hasText(credential.get().cookie())) {
                request = request.withHeader("Cookie", credential.get().cookie());
            }
            OutboundHttpResponse response = httpClient.execute(request);
            int status = response.statusCode();
            String body = response.bodyAsUtf8();
            if (status >= 200 && status < 300) {
                return body == null || body.isBlank() ? Optional.empty() : Optional.of(body);
            }
            if (status == 404) {
                return Optional.empty();
            }
            if (status == 429) {
                throw new MetadataProviderRetryableException("RATE_LIMITED", "Douban rate limited request");
            }
            if (status >= 500 && status < 600) {
                throw new MetadataProviderRetryableException(
                        "UPSTREAM_UNAVAILABLE",
                        "Douban upstream returned " + status);
            }
            if (status == 403) {
                throw new MetadataProviderTerminalException("DOUBAN_FORBIDDEN", "Douban returned 403");
            }
            throw new MetadataProviderTerminalException("HTTP_" + status, "Douban returned " + status);
        } catch (MetadataProviderRetryableException | MetadataProviderTerminalException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MetadataProviderRetryableException("DOUBAN_OUTBOUND_INTERRUPTED", "Douban outbound request interrupted", ex);
        } catch (IOException ex) {
            throw new MetadataProviderRetryableException("DOUBAN_OUTBOUND_UNAVAILABLE", "Douban outbound request failed", ex);
        }
    }
}

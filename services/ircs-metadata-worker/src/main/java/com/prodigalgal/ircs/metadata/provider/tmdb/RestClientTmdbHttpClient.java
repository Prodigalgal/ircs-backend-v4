package com.prodigalgal.ircs.metadata.provider.tmdb;

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
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class RestClientTmdbHttpClient implements TmdbHttpClient {

    private static final String PROVIDER_CODE = "TMDB";

    private OutboundHttpClient httpClient;
    private final Duration timeout;
    private final MetadataProviderTrafficLimiter trafficLimiter;
    public RestClientTmdbHttpClient(
            TmdbProviderProperties properties,
            ObjectProvider<MetadataProviderTrafficLimiter> trafficLimiterProvider) {
        this.httpClient = new OutboundHttpClient(
                new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                new JdkOutboundTransport(properties.getRequestTimeout()));
        this.timeout = properties.getRequestTimeout();
        this.trafficLimiter = resolveTrafficLimiter(trafficLimiterProvider);
    }

    static RestClientTmdbHttpClient forTest(OutboundHttpClient httpClient, Duration timeout) {
        return forTest(httpClient, timeout, MetadataProviderTrafficLimiter.noop());
    }

    static RestClientTmdbHttpClient forTest(
            OutboundHttpClient httpClient,
            Duration timeout,
        MetadataProviderTrafficLimiter trafficLimiter) {
        TmdbProviderProperties properties = new TmdbProviderProperties();
        properties.setRequestTimeout(timeout);
        RestClientTmdbHttpClient client = new RestClientTmdbHttpClient(properties, trafficLimiterProvider(trafficLimiter));
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
        MetadataProviderTrafficLimiter resolved = trafficLimiter == null
                ? MetadataProviderTrafficLimiter.noop()
                : trafficLimiter;
        return new ObjectProvider<>() {
            @Override
            public MetadataProviderTrafficLimiter getObject(Object... args) {
                return resolved;
            }

            @Override
            public MetadataProviderTrafficLimiter getIfAvailable() {
                return resolved;
            }

            @Override
            public MetadataProviderTrafficLimiter getIfUnique() {
                return resolved;
            }

            @Override
            public MetadataProviderTrafficLimiter getObject() {
                return resolved;
            }
        };
    }

    @Override
    public Optional<String> getJson(URI uri) {
        try {
            trafficLimiter.acquireProviderSlot(PROVIDER_CODE);
            OutboundHttpResponse response = httpClient.execute(OutboundHttpRequest.get(
                    uri.toString(),
                    OutboundHttpPolicy.publicFetch(timeout)));
            int status = response.statusCode();
            String body = response.bodyAsUtf8();
            if (status >= 200 && status < 300) {
                return body == null || body.isBlank() ? Optional.empty() : Optional.of(body);
            }
            if (status == 404) {
                return Optional.empty();
            }
            if (status == 429) {
                throw new MetadataProviderRetryableException("RATE_LIMITED", "TMDB rate limited request");
            }
            if (status >= 500 && status < 600) {
                throw new MetadataProviderRetryableException(
                        "UPSTREAM_UNAVAILABLE",
                        "TMDB upstream returned " + status);
            }
            throw new MetadataProviderTerminalException("HTTP_" + status, "TMDB returned " + status);
        } catch (MetadataProviderRetryableException | MetadataProviderTerminalException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MetadataProviderRetryableException("TMDB_OUTBOUND_INTERRUPTED", "TMDB outbound request interrupted", ex);
        } catch (IOException ex) {
            throw new MetadataProviderRetryableException("TMDB_OUTBOUND_UNAVAILABLE", "TMDB outbound request failed", ex);
        }
    }
}

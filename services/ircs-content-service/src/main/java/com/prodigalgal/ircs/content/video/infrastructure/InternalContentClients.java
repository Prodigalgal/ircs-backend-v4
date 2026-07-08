package com.prodigalgal.ircs.content.video.infrastructure;


import com.prodigalgal.ircs.content.video.api.ContentApiException;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.JdkOutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundCircuitOpenException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.content.config.ContentConfigValues;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class InternalContentClients {

    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private final ContentConfigValues configValues;
    private final OutboundHttpClient httpClient;
    private final Duration requestTimeout;

    public InternalContentClients(
            ContentConfigValues configValues,
            ObjectProvider<OutboundHttpClient> httpClient,
            @Value("${app.content.internal-client.request-timeout:PT10S}") Duration requestTimeout) {
        this.configValues = configValues;
        this.httpClient = httpClient == null
                ? defaultHttpClient()
                : httpClient.getIfAvailable(InternalContentClients::defaultHttpClient);
        this.requestTimeout = requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()
                ? DEFAULT_REQUEST_TIMEOUT
                : requestTimeout;
    }

    static InternalContentClients forTest(ContentConfigValues configValues) {
        return forTest(configValues, null, DEFAULT_REQUEST_TIMEOUT);
    }

    static InternalContentClients forTest(
            ContentConfigValues configValues,
            OutboundHttpClient httpClient,
            Duration requestTimeout) {
        return new InternalContentClients(configValues, new ObjectProvider<>() {
            @Override
            public OutboundHttpClient getObject() {
                return httpClient;
            }
        }, requestTimeout);
    }

    public void refetchRawVideo(UUID rawVideoId) {
        try {
            OutboundHttpPolicy policy = OutboundHttpPolicy.internalService(requestTimeout)
                    .withCallerCircuitBreakerKey("content-refetch-scraper");
            OutboundHttpResponse response = httpClient.execute(OutboundHttpRequest.post(refetchUri(rawVideoId), policy));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ContentApiException(HttpStatus.BAD_GATEWAY,
                        "Failed to dispatch raw video refetch: upstream status " + response.statusCode());
            }
        } catch (OutboundCircuitOpenException ex) {
            throw new ContentApiException(HttpStatus.BAD_GATEWAY,
                    "Failed to dispatch raw video refetch: outbound circuit open");
        } catch (IOException | IllegalArgumentException ex) {
            throw new ContentApiException(HttpStatus.BAD_GATEWAY, "Failed to dispatch raw video refetch: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ContentApiException(HttpStatus.BAD_GATEWAY, "Failed to dispatch raw video refetch: interrupted");
        }
    }

    private URI refetchUri(UUID rawVideoId) {
        String baseUrl = configValues.scraperBaseUrl();
        String normalizedBase = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return URI.create(normalizedBase + "/internal/v1/scraper/raw-videos/" + rawVideoId + "/refetch");
    }

    private static OutboundHttpClient defaultHttpClient() {
        return new OutboundHttpClient(
                new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                new JdkOutboundTransport());
    }
}

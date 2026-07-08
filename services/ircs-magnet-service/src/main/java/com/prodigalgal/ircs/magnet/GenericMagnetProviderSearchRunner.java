package com.prodigalgal.ircs.magnet;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.JdkOutboundTransport;
import com.prodigalgal.ircs.common.outbound.OutboundHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class GenericMagnetProviderSearchRunner implements MagnetProviderSearchRunner {

    static final String HTTP_ERROR = "GENERIC_MAGNET_HTTP_ERROR";
    static final String ACCESS_RESTRICTED = "GENERIC_MAGNET_ACCESS_RESTRICTED";
    private static final String PARSE_FAILURE = "GENERIC_MAGNET_PARSE_FAILURE";
    private static final String TIMEOUT = "GENERIC_MAGNET_TIMEOUT";
    private static final String UNSUPPORTED_QUERY = "GENERIC_MAGNET_UNSUPPORTED_QUERY";

    private final MagnetProviderTrafficLimiter trafficLimiter;
    private final GenericMagnetHttpClient httpClient;
    private final GenericMagnetRequestUrlBuilder requestUrlBuilder;
    private final GenericMagnetCandidateParser candidateParser;

    GenericMagnetProviderSearchRunner(
            ObjectMapper objectMapper,
            MagnetProviderTrafficLimiter trafficLimiter,
            ObjectProvider<GenericMagnetHttpClient> httpClientProvider) {
        this.trafficLimiter = trafficLimiter;
        this.httpClient = httpClient(httpClientProvider);
        this.requestUrlBuilder = new GenericMagnetRequestUrlBuilder();
        this.candidateParser = new GenericMagnetCandidateParser(objectMapper);
    }

    @Override
    public MagnetProviderSearchResult search(
            MagnetProviderSummary provider,
            MagnetExternalIdQuery query,
            UUID unifiedVideoId) {
        if (!isSupportedQuery(query)) {
            throw new MagnetProviderRunnerException(UNSUPPORTED_QUERY, null, null);
        }
        String requestUrl = requestUrlBuilder.build(provider, query);
        GenericMagnetHttpResponse response = execute(provider, requestUrl);
        if (isAccessRestricted(response)) {
            throw new MagnetProviderRunnerException(ACCESS_RESTRICTED, requestUrl, response.statusCode());
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new MagnetProviderRunnerException(HTTP_ERROR, requestUrl, response.statusCode());
        }
        try {
            List<MagnetProviderCandidate> candidates = candidateParser.parse(provider, query, requestUrl, response.body());
            return new MagnetProviderSearchResult(requestUrl, response.statusCode(), candidates);
        } catch (Exception ex) {
            throw new MagnetProviderRunnerException(PARSE_FAILURE, requestUrl, response.statusCode());
        }
    }

    private GenericMagnetHttpResponse execute(MagnetProviderSummary provider, String requestUrl) {
        try {
            trafficLimiter.acquireProviderSlot(provider);
            return httpClient.get(URI.create(requestUrl), timeout(provider));
        } catch (HttpTimeoutException ex) {
            throw new MagnetProviderRunnerException(TIMEOUT, requestUrl, null);
        } catch (IOException ex) {
            throw new MagnetProviderRunnerException(HTTP_ERROR, requestUrl, null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MagnetProviderRunnerException(TIMEOUT, requestUrl, null);
        }
    }

    private boolean isSupportedQuery(MagnetExternalIdQuery query) {
        return query != null && StringUtils.hasText(query.type()) && StringUtils.hasText(query.value());
    }

    private Duration timeout(MagnetProviderSummary provider) {
        int timeoutMs = provider == null || provider.timeoutMs() == null ? 10000 : provider.timeoutMs();
        return Duration.ofMillis(Math.max(1, timeoutMs));
    }

    private boolean isAccessRestricted(GenericMagnetHttpResponse response) {
        if (response == null) {
            return false;
        }
        if (response.statusCode() == 401 || response.statusCode() == 403 || response.statusCode() == 429) {
            return true;
        }
        String body = response.body() == null ? "" : response.body().toLowerCase();
        return body.contains("cf-mitigated")
                || body.contains("challenges.cloudflare.com")
                || body.contains("just a moment")
                || body.contains("enable javascript and cookies to continue");
    }

    private static GenericMagnetHttpClient httpClient(ObjectProvider<GenericMagnetHttpClient> httpClientProvider) {
        if (httpClientProvider != null) {
            GenericMagnetHttpClient provided = httpClientProvider.getIfUnique();
            if (provided != null) {
                return provided;
            }
        }
        return new SharedOutboundGenericMagnetHttpClient();
    }

    interface GenericMagnetHttpClient {
        GenericMagnetHttpResponse get(URI uri, Duration timeout) throws IOException, InterruptedException;
    }

    record GenericMagnetHttpResponse(int statusCode, String body) {
    }

    static class SharedOutboundGenericMagnetHttpClient implements GenericMagnetHttpClient {

        private final OutboundHttpClient httpClient;

        SharedOutboundGenericMagnetHttpClient() {
            this(new OutboundHttpClient(
                    new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                    new JdkOutboundTransport()));
        }

        SharedOutboundGenericMagnetHttpClient(OutboundHttpClient httpClient) {
            this.httpClient = httpClient;
        }

        @Override
        public GenericMagnetHttpResponse get(URI uri, Duration timeout) throws IOException, InterruptedException {
            OutboundHttpResponse response = httpClient.execute(OutboundHttpRequest.get(
                    uri.toString(),
                    OutboundHttpPolicy.publicFetch(timeout)));
            return new GenericMagnetHttpResponse(response.statusCode(), response.bodyAsUtf8());
        }
    }
}

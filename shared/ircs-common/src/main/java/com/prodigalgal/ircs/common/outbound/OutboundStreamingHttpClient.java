package com.prodigalgal.ircs.common.outbound;

import java.io.IOException;
import java.util.Locale;

public class OutboundStreamingHttpClient {

    private final OutboundUrlPolicy urlPolicy;
    private final OutboundStreamingTransport transport;
    private final OutboundCircuitBreaker circuitBreaker;

    public OutboundStreamingHttpClient(OutboundUrlPolicy urlPolicy, OutboundStreamingTransport transport) {
        this(urlPolicy, transport, new OutboundCircuitBreaker());
    }

    OutboundStreamingHttpClient(
            OutboundUrlPolicy urlPolicy,
            OutboundStreamingTransport transport,
            OutboundCircuitBreaker circuitBreaker) {
        this.urlPolicy = urlPolicy;
        this.transport = transport;
        this.circuitBreaker = circuitBreaker;
    }

    public OutboundStreamingHttpResponse execute(OutboundHttpRequest request)
            throws IOException, InterruptedException {
        validate(request);
        request.policy().proxy().validate();
        String circuitKey = circuitKey(request);
        circuitBreaker.beforeCall(circuitKey, request.policy().circuitBreaker());
        try {
            OutboundStreamingHttpResponse response = transport.send(request);
            if (isCircuitFailure(response, request.policy())) {
                circuitBreaker.recordFailure(circuitKey, request.policy().circuitBreaker(),
                        "http_status_" + response.statusCode());
            } else {
                circuitBreaker.recordSuccess(circuitKey, request.policy().circuitBreaker());
            }
            return response;
        } catch (IOException | RuntimeException ex) {
            circuitBreaker.recordFailure(circuitKey, request.policy().circuitBreaker(), ex.getClass().getSimpleName());
            throw ex;
        } catch (InterruptedException ex) {
            circuitBreaker.recordFailure(circuitKey, request.policy().circuitBreaker(), "InterruptedException");
            throw ex;
        }
    }

    private void validate(OutboundHttpRequest request) throws OutboundHttpException {
        switch (request.policy().type()) {
            case PUBLIC_FETCH -> urlPolicy.validatePublicFetch(request.uri(), request.policy());
            case IMAGE_DOWNLOAD_STRICT -> urlPolicy.validateImageDownloadStrict(request.uri(), request.policy());
            case INTERNAL_SERVICE -> urlPolicy.validateInternalService(request.uri());
            case API_GATEWAY_PROXY -> urlPolicy.validateApiGatewayProxyTarget(request.uri());
        }
    }

    private boolean isCircuitFailure(OutboundStreamingHttpResponse response, OutboundHttpPolicy policy) {
        return response.statusCode() >= 500 || policy.retryStatuses().contains(response.statusCode());
    }

    private String circuitKey(OutboundHttpRequest request) {
        String host = request.uri().getHost() == null
                ? "unknown"
                : request.uri().getHost().toLowerCase(Locale.ROOT);
        int port = request.uri().getPort();
        String authority = port < 0 ? host : host + ":" + port;
        String prefix = request.policy().circuitBreakerKey();
        if (prefix == null || prefix.isBlank()) {
            prefix = request.policy().type().name();
        }
        return prefix + ":" + authority;
    }
}

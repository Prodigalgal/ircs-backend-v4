package com.prodigalgal.ircs.common.outbound;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;

public class OutboundHttpClient {

    private final OutboundUrlPolicy urlPolicy;
    private final OutboundTransport transport;
    private final OutboundCircuitBreaker circuitBreaker;

    public OutboundHttpClient(OutboundUrlPolicy urlPolicy, OutboundTransport transport) {
        this(urlPolicy, transport, new OutboundCircuitBreaker());
    }

    OutboundHttpClient(
            OutboundUrlPolicy urlPolicy,
            OutboundTransport transport,
            OutboundCircuitBreaker circuitBreaker) {
        this.urlPolicy = urlPolicy;
        this.transport = transport;
        this.circuitBreaker = circuitBreaker;
    }

    public OutboundHttpResponse execute(OutboundHttpRequest request) throws IOException, InterruptedException {
        validate(request);
        request.policy().proxy().validate();
        OutboundHttpRequest normalized = withDefaultHeaders(request);
        String circuitKey = circuitKey(normalized);
        circuitBreaker.beforeCall(circuitKey, request.policy().circuitBreaker());
        try {
            OutboundHttpResponse response = executeWithRetry(normalized);
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

    private OutboundHttpResponse executeWithRetry(OutboundHttpRequest normalized)
            throws IOException, InterruptedException {
        OutboundHttpPolicy policy = normalized.policy();
        int attempts = policy.maxRetries() + 1;
        IOException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                OutboundHttpResponse response = transport.send(normalized);
                if (shouldRetry(response, policy) && attempt < attempts) {
                    continue;
                }
                return maybeDecompress(response, policy);
            } catch (HttpTimeoutException ex) {
                last = ex;
                if (attempt >= attempts) {
                    throw ex;
                }
            } catch (IOException ex) {
                last = ex;
                if (attempt >= attempts) {
                    throw ex;
                }
            }
        }
        throw last == null ? new OutboundHttpException("Outbound request failed") : last;
    }

    private void validate(OutboundHttpRequest request) throws OutboundHttpException {
        switch (request.policy().type()) {
            case PUBLIC_FETCH -> urlPolicy.validatePublicFetch(request.uri(), request.policy());
            case IMAGE_DOWNLOAD_STRICT -> urlPolicy.validateImageDownloadStrict(request.uri(), request.policy());
            case INTERNAL_SERVICE -> urlPolicy.validateInternalService(request.uri());
            case API_GATEWAY_PROXY -> urlPolicy.validateApiGatewayProxyTarget(request.uri());
        }
    }

    private OutboundHttpRequest withDefaultHeaders(OutboundHttpRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json,text/plain,*/*");
        headers.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        headers.put("Accept-Encoding", "gzip, deflate");
        headers.put("Connection", "close");
        headers.put("User-Agent", request.policy().userAgent());
        headers.putAll(request.headers());
        return new OutboundHttpRequest(request.uri(), request.method(), headers, request.policy(), request.body());
    }

    private boolean shouldRetry(OutboundHttpResponse response, OutboundHttpPolicy policy) {
        return policy.retryStatuses().contains(response.statusCode());
    }

    private boolean isCircuitFailure(OutboundHttpResponse response, OutboundHttpPolicy policy) {
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

    private OutboundHttpResponse maybeDecompress(OutboundHttpResponse response, OutboundHttpPolicy policy)
            throws IOException {
        if (!policy.decompressGzip()) {
            return response;
        }
        if (!isGzip(response)) {
            return response;
        }
        return new OutboundHttpResponse(response.statusCode(), response.headers(), gunzip(response.body()));
    }

    private boolean isGzip(OutboundHttpResponse response) {
        String encoding = response.firstHeader("Content-Encoding");
        if (encoding != null && encoding.toLowerCase(java.util.Locale.ROOT).contains("gzip")) {
            return true;
        }
        byte[] body = response.body();
        return body.length >= 2 && (body[0] & 0xff) == 0x1f && (body[1] & 0xff) == 0x8b;
    }

    private byte[] gunzip(byte[] body) throws IOException {
        try (GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(body));
                ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            input.transferTo(output);
            return output.toByteArray();
        }
    }
}

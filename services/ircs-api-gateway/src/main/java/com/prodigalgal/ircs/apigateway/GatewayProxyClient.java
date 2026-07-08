package com.prodigalgal.ircs.apigateway;

import com.prodigalgal.ircs.common.audit.ProxyRequestAuditWriter;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.JdkOutboundStreamingTransport;
import com.prodigalgal.ircs.common.outbound.OutboundCircuitBreakerConfig;
import com.prodigalgal.ircs.common.outbound.OutboundHttpException;
import com.prodigalgal.ircs.common.outbound.OutboundHttpPolicy;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundStreamingHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundStreamingHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.security.IrcsAuthHeaders;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Component
class GatewayProxyClient {

    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of(
            "connection",
            "content-length",
            "expect",
            "host",
            "keep-alive",
            "proxy-authenticate",
            "proxy-authorization",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
    );

    private final OutboundStreamingHttpClient outboundClient;
    private final ProxyRequestAuditWriter auditWriter;
    private final OutboundUrlPolicy targetUrlPolicy;
    private final Duration requestTimeout;
    private final Duration streamRequestTimeout;
    private final String circuitKey;
    private final OutboundCircuitBreakerConfig circuitBreakerConfig;
    private final String serviceId;
    private final String opsServiceToken;
    private final String opsServiceScopes;
    private final String opsAlertServiceToken;
    private final String opsAlertServiceScopes;

    GatewayProxyClient(
            @Value("${app.gateway.proxy.connect-timeout:PT5S}") Duration connectTimeout,
            @Value("${app.gateway.proxy.request-timeout:PT5S}") Duration requestTimeout,
            @Value("${app.gateway.proxy.stream-request-timeout:PT1H}") Duration streamRequestTimeout,
            @Value("${app.gateway.proxy.circuit-key:api-gateway-proxy}") String circuitKey,
            @Value("${app.gateway.proxy.circuit.enabled:true}") boolean circuitEnabled,
            @Value("${app.gateway.proxy.circuit.failure-threshold:5}") int failureThreshold,
            @Value("${app.gateway.proxy.circuit.open-duration:PT30S}") Duration openDuration,
            @Value("${app.gateway.proxy.circuit.half-open-max-calls:1}") int halfOpenMaxCalls,
            @Value("${app.gateway.service-identity.service-id:api-gateway}") String serviceId,
            @Value("${app.gateway.service-identity.ops-token:${APP_GATEWAY_OPS_SERVICE_TOKEN:}}") String opsServiceToken,
            @Value("${app.gateway.service-identity.ops-scopes:ops:read ops:run}") String opsServiceScopes,
            @Value("${app.gateway.service-identity.ops-alert-token:${APP_GATEWAY_OPS_ALERT_SERVICE_TOKEN:}}") String opsAlertServiceToken,
            @Value("${app.gateway.service-identity.ops-alert-scopes:ops-alert:read ops-alert:run}") String opsAlertServiceScopes,
            ProxyRequestAuditWriter auditWriter,
            ObjectProvider<OutboundStreamingHttpClient> outboundClientProvider,
            ObjectProvider<OutboundUrlPolicy> targetUrlPolicyProvider) {
        this.outboundClient = outboundClient(outboundClientProvider, connectTimeout);
        this.auditWriter = auditWriter;
        this.targetUrlPolicy = targetUrlPolicy(targetUrlPolicyProvider);
        this.requestTimeout = requestTimeout;
        this.streamRequestTimeout = normalizePositive(streamRequestTimeout, Duration.ofHours(1));
        this.circuitKey = circuitKey;
        this.circuitBreakerConfig = new OutboundCircuitBreakerConfig(
                circuitEnabled,
                failureThreshold,
                openDuration,
                halfOpenMaxCalls);
        this.serviceId = serviceId;
        this.opsServiceToken = opsServiceToken;
        this.opsServiceScopes = opsServiceScopes;
        this.opsAlertServiceToken = opsAlertServiceToken;
        this.opsAlertServiceScopes = opsAlertServiceScopes;
    }

    void proxy(HttpServletRequest request, HttpServletResponse response, GatewayRouteTable routes) throws IOException {
        Instant startedAt = Instant.now();
        int statusCode = HttpStatus.INTERNAL_SERVER_ERROR.value();
        String errorMessage = null;
        try {
            ResolvedRoute route = routes.resolve(request.getRequestURI())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource not found"));
            URI target = targetUri(route, request);
            OutboundHttpRequest outbound = outboundRequest(request, target);
            try (OutboundStreamingHttpResponse upstream = outboundClient.execute(outbound)) {
                statusCode = upstream.statusCode();
                writeResponse(upstream, response);
            }
        } catch (ResponseStatusException ex) {
            statusCode = ex.getStatusCode().value();
            errorMessage = ex.getReason();
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            errorMessage = "API gateway proxy interrupted";
            throw new IOException("API gateway proxy interrupted", ex);
        } catch (OutboundHttpException ex) {
            statusCode = HttpStatus.BAD_GATEWAY.value();
            errorMessage = ex.getMessage();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "API gateway upstream unavailable", ex);
        } catch (IOException | RuntimeException ex) {
            errorMessage = ex.getMessage();
            throw ex;
        } finally {
            auditWriter.record(request, statusCode, Duration.between(startedAt, Instant.now()), errorMessage);
        }
    }

    private URI targetUri(ResolvedRoute route, HttpServletRequest request) {
        validateTargetBaseUrl(route.targetBaseUrl());
        String value = route.targetBaseUrl() + route.targetPath();
        String sanitizedQuery = queryForUpstream(request);
        if (sanitizedQuery != null && !sanitizedQuery.isBlank()) {
            value += "?" + sanitizedQuery;
        }
        return URI.create(value);
    }

    private static String queryForUpstream(HttpServletRequest request) {
        String query = request.getQueryString();
        return isEventStreamRequest(request) ? sanitizedQuery(query) : query;
    }

    private static String sanitizedQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        List<String> parameters = new ArrayList<>();
        for (String part : query.split("&")) {
            if (part.isBlank()) {
                continue;
            }
            String name = part;
            String value = null;
            int separator = part.indexOf('=');
            if (separator >= 0) {
                name = part.substring(0, separator);
                value = part.substring(separator + 1);
            }
            String decodedName = decodedQueryName(name);
            if ("token".equalsIgnoreCase(decodedName)) {
                continue;
            }
            if (value == null) {
                parameters.add(name);
            } else {
                parameters.add(name + "=" + value);
            }
        }
        return parameters.isEmpty() ? null : String.join("&", parameters);
    }

    private static String decodedQueryName(String name) {
        try {
            return URLDecoder.decode(name, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ignored) {
            return name;
        }
    }

    private void validateTargetBaseUrl(String targetBaseUrl) {
        try {
            targetUrlPolicy.validateApiGatewayProxyTarget(URI.create(targetBaseUrl));
        } catch (IllegalArgumentException | OutboundHttpException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Invalid API gateway target base URL", ex);
        }
    }

    private OutboundHttpRequest outboundRequest(HttpServletRequest request, URI target) throws IOException {
        Map<String, String> headers = requestHeaders(request);
        String method = request.getMethod();
        OutboundHttpPolicy policy = OutboundHttpPolicy.apiGatewayProxy(timeoutFor(request))
                .withCircuitBreaker(circuitBreakerConfig)
                .withCircuitBreakerKey(circuitKey);
        if ("GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method) || "OPTIONS".equalsIgnoreCase(method)) {
            return new OutboundHttpRequest(target, method, headers, policy, new byte[0]);
        }
        byte[] body = request.getInputStream().readAllBytes();
        return new OutboundHttpRequest(target, method, headers, policy, body);
    }

    private Map<String, String> requestHeaders(HttpServletRequest request) {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                headers.merge(name, values.nextElement(), (left, right) -> left + "," + right);
            }
        }
        IrcsAuthHeaders.removeTrustedIdentityHeaders(headers);
        removeExternalAuthHeaders(headers);
        removeInternalServiceHeaders(headers);
        IrcsAuthHeaders.fromRequestAttribute(request)
                .ifPresent(principal -> IrcsAuthHeaders.writePrincipal(headers, principal));
        if (shouldAttachOpsAlertIdentity(request.getRequestURI()) && StringUtils.hasText(opsAlertServiceToken)) {
            headers.put(InternalServiceAuthHeaders.SERVICE_ID, serviceId);
            headers.put(InternalServiceAuthHeaders.SERVICE_TOKEN, opsAlertServiceToken.trim());
            headers.put(InternalServiceAuthHeaders.SERVICE_SCOPES, opsAlertServiceScopes);
        } else if (shouldAttachOpsIdentity(request.getRequestURI()) && StringUtils.hasText(opsServiceToken)) {
            headers.put(InternalServiceAuthHeaders.SERVICE_ID, serviceId);
            headers.put(InternalServiceAuthHeaders.SERVICE_TOKEN, opsServiceToken.trim());
            headers.put(InternalServiceAuthHeaders.SERVICE_SCOPES, opsServiceScopes);
        }
        return headers;
    }

    private boolean shouldAttachOpsAlertIdentity(String path) {
        return path.equals("/api/v1/ops-alert") || path.startsWith("/api/v1/ops-alert/");
    }

    private boolean shouldAttachOpsIdentity(String path) {
        return (path.equals("/api/v1/ops") || path.startsWith("/api/v1/ops/"))
                || path.startsWith("/api/v1/dashboard")
                || path.startsWith("/api/v1/debug");
    }

    private Duration timeoutFor(HttpServletRequest request) {
        if (isEventStreamRequest(request)) {
            return streamRequestTimeout;
        }
        return requestTimeout;
    }

    private static boolean isEventStreamRequest(HttpServletRequest request) {
        String accept = request.getHeader("Accept");
        if (accept != null && accept.toLowerCase(Locale.ROOT).contains("text/event-stream")) {
            return true;
        }
        String path = request.getRequestURI();
        return path != null && (path.endsWith("/stream") || path.contains("/stream/"));
    }

    private void removeInternalServiceHeaders(Map<String, String> headers) {
        headers.keySet().removeIf(name ->
                name.equalsIgnoreCase(InternalServiceAuthHeaders.SERVICE_ID)
                        || name.equalsIgnoreCase(InternalServiceAuthHeaders.SERVICE_TOKEN)
                        || name.equalsIgnoreCase(InternalServiceAuthHeaders.SERVICE_SCOPES));
    }

    private void removeExternalAuthHeaders(Map<String, String> headers) {
        headers.keySet().removeIf(name ->
                name.equalsIgnoreCase("Authorization")
                        || name.equalsIgnoreCase(AdminApiTokenService.HEADER_API_TOKEN));
    }

    private void writeResponse(OutboundStreamingHttpResponse upstream, HttpServletResponse response) throws IOException {
        response.setStatus(upstream.statusCode());
        upstream.headers().forEach((name, values) -> {
            if (HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                return;
            }
            for (String value : values) {
                response.addHeader(name, value);
            }
        });
        copyBody(upstream.body(), response.getOutputStream(), isStreamingResponse(upstream.headers()));
    }

    private void copyBody(InputStream input, OutputStream output, boolean flushChunks) throws IOException {
        byte[] buffer = new byte[65536];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
            if (flushChunks) {
                output.flush();
            }
        }
        if (!flushChunks) {
            output.flush();
        }
    }

    private static boolean isStreamingResponse(Map<String, List<String>> headers) {
        return headers.entrySet().stream()
                .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase("Content-Type"))
                .flatMap(entry -> entry.getValue().stream())
                .filter(value -> value != null)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .anyMatch(value -> value.contains("text/event-stream"));
    }

    private static OutboundStreamingHttpClient outboundClient(
            ObjectProvider<OutboundStreamingHttpClient> outboundClientProvider,
            Duration connectTimeout) {
        if (outboundClientProvider != null) {
            OutboundStreamingHttpClient provided = outboundClientProvider.getIfUnique();
            if (provided != null) {
                return provided;
            }
        }
        return new OutboundStreamingHttpClient(
                new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                new JdkOutboundStreamingTransport(connectTimeout));
    }

    private static OutboundUrlPolicy targetUrlPolicy(ObjectProvider<OutboundUrlPolicy> targetUrlPolicyProvider) {
        if (targetUrlPolicyProvider != null) {
            OutboundUrlPolicy provided = targetUrlPolicyProvider.getIfUnique();
            if (provided != null) {
                return provided;
            }
        }
        return new OutboundUrlPolicy(new DefaultOutboundAddressResolver());
    }

    private static Duration normalizePositive(Duration value, Duration fallback) {
        return value == null || value.isZero() || value.isNegative() ? fallback : value;
    }
}

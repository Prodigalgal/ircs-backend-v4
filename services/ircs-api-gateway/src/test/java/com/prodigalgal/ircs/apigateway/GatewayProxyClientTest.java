package com.prodigalgal.ircs.apigateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.prodigalgal.ircs.common.audit.ProxyRequestAuditWriter;
import com.prodigalgal.ircs.common.outbound.DefaultOutboundAddressResolver;
import com.prodigalgal.ircs.common.outbound.OutboundHttpRequest;
import com.prodigalgal.ircs.common.outbound.OutboundStreamingHttpClient;
import com.prodigalgal.ircs.common.outbound.OutboundStreamingHttpResponse;
import com.prodigalgal.ircs.common.outbound.OutboundStreamingTransport;
import com.prodigalgal.ircs.common.outbound.OutboundUrlPolicy;
import com.prodigalgal.ircs.common.security.IrcsAuthHeaders;
import com.prodigalgal.ircs.common.security.IrcsPermissions;
import com.prodigalgal.ircs.common.security.IrcsRequestPrincipal;
import com.prodigalgal.ircs.common.security.InternalServiceAuthHeaders;
import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;

class GatewayProxyClientTest {

    private final FakeStreamingTransport transport = new FakeStreamingTransport();
    private final GatewayProxyClient proxyClient = new GatewayProxyClient(
            Duration.ofSeconds(5),
            Duration.ofSeconds(2),
            Duration.ofMinutes(30),
            "api-gateway-proxy-test",
            false,
            5,
            Duration.ofSeconds(30),
            1,
            "api-gateway",
            "",
            "ops:read ops:run",
            "",
            "ops-alert:read ops-alert:run",
            new ProxyRequestAuditWriter(false, null, null, null, "ircs-api-gateway"),
            provider(new OutboundStreamingHttpClient(
                    new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                    transport)),
            provider(new OutboundUrlPolicy(new DefaultOutboundAddressResolver())));

    @Test
    void forwardsWhitelistedRouteQueryAndFiltersHopByHopHeaders() throws Exception {
        transport.response = response(
                302,
                Map.of(
                        "Location", List.of("http://content/next"),
                        "Transfer-Encoding", List.of("chunked"),
                        "X-Upstream", List.of("content")),
                "redirect-body");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/raw-videos/42");
        request.setQueryString("next=http://169.254.169.254/latest&keyword=codex");
        request.addHeader("Connection", "keep-alive");
        request.addHeader("Host", "api-gateway");
        request.addHeader("Proxy-Authorization", "Basic secret");
        request.addHeader("Authorization", "Bearer ircs_pat_should_not_forward");
        request.addHeader(AdminApiTokenService.HEADER_API_TOKEN, "ircs_pat_header_should_not_forward");
        request.addHeader("X-Authenticated-User", "spoof@example.com");
        request.addHeader("X-IRCS-Auth-Role", "ROLE_MEMBER");
        request.addHeader("X-Trace-Id", "trace-1");
        IrcsAuthHeaders.setRequestAttribute(request, adminPrincipal());
        MockHttpServletResponse response = new MockHttpServletResponse();
        GatewayRouteTable routes = new GatewayRouteTable(List.of(
                new GatewayRoute("/api/v1/raw-videos", "http://content-service", "/api/v1/raw-videos")));

        proxyClient.proxy(request, response, routes);

        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.uri())
                .isEqualTo(URI.create("http://content-service/api/v1/raw-videos/42?next=http://169.254.169.254/latest&keyword=codex"));
        assertThat(sent.method()).isEqualTo("GET");
        assertThat(sent.body()).isEmpty();
        assertThat(sent.policy().circuitBreakerKey()).isEqualTo("api-gateway-proxy-test");
        assertThat(sent.policy().timeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(sent.headers()).containsEntry("X-Trace-Id", "trace-1");
        assertThat(sent.headers()).containsEntry(IrcsAuthHeaders.AUTHENTICATED_USER, "admin");
        assertThat(sent.headers()).containsEntry(IrcsAuthHeaders.AUTH_ROLE, IrcsPermissions.ROLE_ADMIN);
        assertThat(sent.headers()).containsEntry(IrcsAuthHeaders.AUTH_PERMISSIONS, IrcsPermissions.ALL);
        assertThat(sent.headers()).doesNotContainKeys(
                "Connection",
                "Host",
                "Proxy-Authorization",
                "Authorization",
                AdminApiTokenService.HEADER_API_TOKEN);
        assertThat(response.getStatus()).isEqualTo(302);
        assertThat(response.getHeader("Location")).isEqualTo("http://content/next");
        assertThat(response.getHeader("X-Upstream")).isEqualTo("content");
        assertThat(response.getHeader("Transfer-Encoding")).isNull();
        assertThat(response.getContentAsString()).isEqualTo("redirect-body");
    }

    @Test
    void attachesOpsAlertInternalIdentityWhenTokenIsConfigured() throws Exception {
        FakeStreamingTransport localTransport = new FakeStreamingTransport();
        GatewayProxyClient client = new GatewayProxyClient(
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                Duration.ofMinutes(30),
                "api-gateway-proxy-test",
                false,
                5,
                Duration.ofSeconds(30),
                1,
                "api-gateway",
                "ops-secret",
                "ops:read ops:run",
                "ops-alert-secret",
                "ops-alert:read ops-alert:run",
                new ProxyRequestAuditWriter(false, null, null, null, "ircs-api-gateway"),
                provider(new OutboundStreamingHttpClient(
                        new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                        localTransport)),
                provider(new OutboundUrlPolicy(new DefaultOutboundAddressResolver())));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ops-alert/incidents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        GatewayRouteTable routes = new GatewayRouteTable(List.of(
                new GatewayRoute("/api/v1/ops-alert", "http://ops-alert-service", "/api/v1/ops-alert")));

        client.proxy(request, response, routes);

        OutboundHttpRequest sent = localTransport.requests.getFirst();
        assertThat(sent.headers()).containsEntry(InternalServiceAuthHeaders.SERVICE_ID, "api-gateway");
        assertThat(sent.headers()).containsEntry(InternalServiceAuthHeaders.SERVICE_TOKEN, "ops-alert-secret");
        assertThat(sent.headers()).containsEntry(InternalServiceAuthHeaders.SERVICE_SCOPES, "ops-alert:read ops-alert:run");
    }

    @Test
    void doesNotFallbackOpsTokenToOpsAlertRoute() throws Exception {
        FakeStreamingTransport localTransport = new FakeStreamingTransport();
        GatewayProxyClient client = new GatewayProxyClient(
                Duration.ofSeconds(5),
                Duration.ofSeconds(2),
                Duration.ofMinutes(30),
                "api-gateway-proxy-test",
                false,
                5,
                Duration.ofSeconds(30),
                1,
                "api-gateway",
                "ops-secret",
                "ops:read ops:run",
                "",
                "ops-alert:read ops-alert:run",
                new ProxyRequestAuditWriter(false, null, null, null, "ircs-api-gateway"),
                provider(new OutboundStreamingHttpClient(
                        new OutboundUrlPolicy(new DefaultOutboundAddressResolver()),
                        localTransport)),
                provider(new OutboundUrlPolicy(new DefaultOutboundAddressResolver())));
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/ops-alert/incidents");
        MockHttpServletResponse response = new MockHttpServletResponse();
        GatewayRouteTable routes = new GatewayRouteTable(List.of(
                new GatewayRoute("/api/v1/ops-alert", "http://ops-alert-service", "/api/v1/ops-alert")));

        client.proxy(request, response, routes);

        assertThat(localTransport.requests.getFirst().headers())
                .doesNotContainKeys(
                        InternalServiceAuthHeaders.SERVICE_ID,
                        InternalServiceAuthHeaders.SERVICE_TOKEN,
                        InternalServiceAuthHeaders.SERVICE_SCOPES);
    }

    private static IrcsRequestPrincipal adminPrincipal() {
        return new IrcsRequestPrincipal(
                "admin",
                IrcsPermissions.ROLE_ADMIN,
                java.util.Set.of(IrcsPermissions.ALL),
                java.util.Set.of(IrcsPermissions.SCOPE_ADMIN_ALL, IrcsPermissions.SCOPE_DATA_ALL),
                java.util.Set.of(IrcsPermissions.ALL),
                java.util.Set.of(IrcsPermissions.ALL),
                java.util.Set.of(IrcsPermissions.ALL),
                java.util.Set.of(IrcsPermissions.ALL));
    }

    @Test
    void forwardsPatchBodyThroughSharedTransport() throws Exception {
        transport.response = response(200, Map.of("X-Upstream", List.of("content")), "{\"ok\":true}");
        MockHttpServletRequest request = new MockHttpServletRequest("PATCH", "/api/v1/raw-videos/42");
        request.setContentType("application/json");
        request.setContent("{\"title\":\"codex\"}".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse response = new MockHttpServletResponse();
        GatewayRouteTable routes = new GatewayRouteTable(List.of(
                new GatewayRoute("/api/v1/raw-videos", "http://content-service", "/api/v1/raw-videos")));

        proxyClient.proxy(request, response, routes);

        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("PATCH");
        assertThat(new String(sent.body(), StandardCharsets.UTF_8)).isEqualTo("{\"title\":\"codex\"}");
        assertThat(sent.headers()).containsEntry("Content-Type", "application/json");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void usesLongTimeoutForEventStreamProxyRequests() throws Exception {
        transport.response = response(200, Map.of("Content-Type", List.of("text/event-stream")), "data: {}\n\n");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dashboard/stream/metrics");
        request.addHeader("Accept", "text/event-stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        GatewayRouteTable routes = new GatewayRouteTable(List.of(
                new GatewayRoute("/api/v1/dashboard", "http://ops-service", "/api/v1/dashboard")));

        proxyClient.proxy(request, response, routes);

        assertThat(transport.requests.getFirst().policy().timeout()).isEqualTo(Duration.ofMinutes(30));
        assertThat(response.getContentAsString()).isEqualTo("data: {}\n\n");
    }

    @Test
    void stripsEventSourceQueryTokenBeforeProxying() throws Exception {
        transport.response = response(200, Map.of("Content-Type", List.of("text/event-stream")), "data: {}\n\n");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dashboard/stream/metrics");
        request.setQueryString("token=secret-jwt&limit=50");
        request.addHeader("Accept", "text/event-stream");
        MockHttpServletResponse response = new MockHttpServletResponse();
        GatewayRouteTable routes = new GatewayRouteTable(List.of(
                new GatewayRoute("/api/v1/dashboard", "http://ops-service", "/api/v1/dashboard")));

        proxyClient.proxy(request, response, routes);

        assertThat(transport.requests.getFirst().uri().toString())
                .isEqualTo("http://ops-service/api/v1/dashboard/stream/metrics?limit=50");
    }

    @Test
    void flushesBufferedJsonResponseOnlyAfterBodyCopyCompletes() throws Exception {
        String body = "x".repeat(140_000);
        transport.response = response(200, Map.of("Content-Type", List.of("application/json")), body);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/raw-videos/42");
        FlushCountingResponse response = new FlushCountingResponse();
        GatewayRouteTable routes = new GatewayRouteTable(List.of(
                new GatewayRoute("/api/v1/raw-videos", "http://content-service", "/api/v1/raw-videos")));

        proxyClient.proxy(request, response, routes);

        assertThat(response.flushCount()).isOne();
        assertThat(response.bodyAsString()).isEqualTo(body);
    }

    @Test
    void keepsChunkFlushForEventStreamResponses() throws Exception {
        String body = "data: " + "x".repeat(140_000) + "\n\n";
        transport.response = response(200, Map.of("Content-Type", List.of("text/event-stream")), body);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/dashboard/stream/metrics");
        request.addHeader("Accept", "text/event-stream");
        FlushCountingResponse response = new FlushCountingResponse();
        GatewayRouteTable routes = new GatewayRouteTable(List.of(
                new GatewayRoute("/api/v1/dashboard", "http://ops-service", "/api/v1/dashboard")));

        proxyClient.proxy(request, response, routes);

        assertThat(response.flushCount()).isGreaterThan(1);
        assertThat(response.bodyAsString()).isEqualTo(body);
    }

    @Test
    void preservesBusinessQueryTokenOnRegularRequests() throws Exception {
        transport.response = response(200, Map.of("X-Upstream", List.of("content")), "ok");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/raw-videos/42");
        request.setQueryString("token=business-token&limit=50");
        MockHttpServletResponse response = new MockHttpServletResponse();
        GatewayRouteTable routes = new GatewayRouteTable(List.of(
                new GatewayRoute("/api/v1/raw-videos", "http://content-service", "/api/v1/raw-videos")));

        proxyClient.proxy(request, response, routes);

        assertThat(transport.requests.getFirst().uri().toString())
                .isEqualTo("http://content-service/api/v1/raw-videos/42?token=business-token&limit=50");
    }

    @Test
    void rejectsUnknownRouteBeforeSendingRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/not-allowed");
        MockHttpServletResponse response = new MockHttpServletResponse();
        GatewayRouteTable routes = new GatewayRouteTable(List.of(
                new GatewayRoute("/api/v1/raw-videos", "http://content-service", "/api/v1/raw-videos")));

        assertThatThrownBy(() -> proxyClient.proxy(request, response, routes))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException status = (ResponseStatusException) ex;
                    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(status.getReason()).isEqualTo("Resource not found");
                });
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void rejectsInvalidTargetBaseUrlBeforeSendingRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/raw-videos/42");
        MockHttpServletResponse response = new MockHttpServletResponse();
        GatewayRouteTable routes = new GatewayRouteTable(List.of(
                new GatewayRoute("/api/v1/raw-videos", "http://token@content-service", "/api/v1/raw-videos")));

        assertThatThrownBy(() -> proxyClient.proxy(request, response, routes))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(ex -> {
                    ResponseStatusException status = (ResponseStatusException) ex;
                    assertThat(status.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
                    assertThat(status.getReason()).isEqualTo("Invalid API gateway target base URL");
                });
        assertThat(transport.requests).isEmpty();
    }

    private static OutboundStreamingHttpResponse response(
            int statusCode,
            Map<String, List<String>> headers,
            String body) {
        return new OutboundStreamingHttpResponse(
                statusCode,
                headers,
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> provider = org.mockito.Mockito.mock(ObjectProvider.class);
        org.mockito.Mockito.when(provider.getIfUnique()).thenReturn(value);
        return provider;
    }

    private static final class FakeStreamingTransport implements OutboundStreamingTransport {

        private final List<OutboundHttpRequest> requests = new ArrayList<>();
        private OutboundStreamingHttpResponse response = response(200, Map.of(), "ok");

        @Override
        public OutboundStreamingHttpResponse send(OutboundHttpRequest request) throws IOException {
            requests.add(request);
            return response;
        }
    }

    private static final class FlushCountingResponse extends MockHttpServletResponse {

        private final FlushCountingOutputStream outputStream = new FlushCountingOutputStream();

        @Override
        public ServletOutputStream getOutputStream() {
            return outputStream;
        }

        int flushCount() {
            return outputStream.flushCount;
        }

        String bodyAsString() {
            return outputStream.body.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class FlushCountingOutputStream extends ServletOutputStream {

        private final ByteArrayOutputStream body = new ByteArrayOutputStream();
        private int flushCount;

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setWriteListener(WriteListener writeListener) {
            // Tests write synchronously.
        }

        @Override
        public void write(int value) {
            body.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            body.write(bytes, offset, length);
        }

        @Override
        public void flush() {
            flushCount++;
        }
    }
}

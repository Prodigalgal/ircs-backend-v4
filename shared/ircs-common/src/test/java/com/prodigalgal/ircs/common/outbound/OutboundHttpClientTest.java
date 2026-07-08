package com.prodigalgal.ircs.common.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class OutboundHttpClientTest {

    private final FakeResolver resolver = new FakeResolver();
    private final FakeTransport transport = new FakeTransport();
    private final OutboundHttpClient client =
            new OutboundHttpClient(new OutboundUrlPolicy(resolver), transport);
    private final OutboundHttpPolicy policy = OutboundHttpPolicy.publicFetch(Duration.ofSeconds(10));

    @Test
    void blocksPrivateLoopbackMetadataAndUnknownHostsBeforeTransport() throws Exception {
        assertBlocked("http://localhost/a", null);
        assertBlocked("http://metadata.google.internal/a", null);
        assertBlocked("http://example.test/a", "127.0.0.1");
        assertBlocked("http://example.test/a", "0.0.0.0");
        assertBlocked("http://example.test/a", "10.0.0.8");
        assertBlocked("http://example.test/a", "172.16.1.1");
        assertBlocked("http://example.test/a", "192.168.1.1");
        assertBlocked("http://example.test/a", "100.64.0.1");
        assertBlocked("http://example.test/a", "169.254.169.254");
        assertBlocked("http://example.test/a", "198.18.0.1");
        assertBlocked("http://example.test/a", "::1");
        assertBlocked("http://example.test/a", "fc00::1");
        assertBlocked("http://example.test/a", "fe80::1");
        assertBlocked("http://missing.test/a", "unknown");

        assertThat(transport.requests).isEmpty();
    }

    @Test
    void sendsDefaultHeadersAndReturnsPublicResponse() throws Exception {
        resolver.host("example.test", "93.184.216.34");
        transport.enqueue(response(200, "{\"ok\":true}"));

        OutboundHttpResponse response = client.execute(OutboundHttpRequest.get("https://example.test/api", policy));

        assertThat(response.bodyAsUtf8()).isEqualTo("{\"ok\":true}");
        assertThat(transport.requests).hasSize(1);
        assertThat(transport.requests.getFirst().headers())
                .containsEntry("Accept", "application/json,text/plain,*/*")
                .containsKey("User-Agent")
                .containsEntry("Accept-Encoding", "gzip, deflate");
    }

    @Test
    void passesCustomHeadersUserAgentAndProxyPolicyToTransport() throws Exception {
        resolver.host("example.test", "93.184.216.34");
        transport.enqueue(response(200, "ok"));

        OutboundHttpPolicy proxiedPolicy = policy
                .withUserAgent("ircs-scraper-test")
                .withProxy(OutboundProxy.http("proxy.test", 8080, "user", "secret"));
        OutboundHttpRequest request = OutboundHttpRequest.get("https://example.test/api", proxiedPolicy)
                .withHeader("X-Source", "datasource")
                .withHeader("Accept", "application/custom");

        assertThat(client.execute(request).bodyAsUtf8()).isEqualTo("ok");

        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.headers())
                .containsEntry("User-Agent", "ircs-scraper-test")
                .containsEntry("X-Source", "datasource")
                .containsEntry("Accept", "application/custom");
        assertThat(sent.policy().proxy().enabled()).isTrue();
        assertThat(sent.policy().proxy().host()).isEqualTo("proxy.test");
        assertThat(sent.policy().proxy().port()).isEqualTo(8080);
        assertThat(sent.policy().proxy().username()).isEqualTo("user");
    }

    @Test
    void rejectsInvalidProxyBeforeTransport() throws Exception {
        resolver.host("example.test", "93.184.216.34");
        OutboundHttpPolicy invalidProxy = policy.withProxy(OutboundProxy.http("", 0, null, null));

        assertThatThrownBy(() -> client.execute(OutboundHttpRequest.get("https://example.test/api", invalidProxy)))
                .isInstanceOf(OutboundHttpException.class);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void internalServiceAllowsClusterDnsAndLocalhostButRejectsUnsafeUrlShape() throws Exception {
        OutboundHttpPolicy internal = OutboundHttpPolicy.internalService(Duration.ofSeconds(3));
        transport.enqueue(response(200, "cluster"));
        transport.enqueue(response(200, "local-dev"));

        assertThat(client.execute(OutboundHttpRequest.get("http://credential-service/internal", internal)).bodyAsUtf8())
                .isEqualTo("cluster");
        assertThat(client.execute(OutboundHttpRequest.get("http://localhost:8080/internal", internal)).bodyAsUtf8())
                .isEqualTo("local-dev");
        assertThat(transport.requests).hasSize(2);
        assertThat(transport.requests.getFirst().headers())
                .containsEntry("User-Agent", "IRCS-Internal-Service/0.1");

        assertThatThrownBy(() -> client.execute(OutboundHttpRequest.get("file:///tmp/secret", internal)))
                .isInstanceOf(OutboundHttpException.class);
        assertThatThrownBy(() -> client.execute(OutboundHttpRequest.get("http://user:pass@credential-service/internal", internal)))
                .isInstanceOf(OutboundHttpException.class);
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    void imageDownloadStrictUsesStoragePolicyAndBlocksPrivateAddressesByDefault() throws Exception {
        OutboundHttpPolicy strict = OutboundHttpPolicy.imageDownloadStrict(Duration.ofSeconds(3));
        resolver.host("example.test", "10.1.2.3");

        assertThat(strict.type()).isEqualTo(OutboundHttpPolicyType.IMAGE_DOWNLOAD_STRICT);
        assertThat(strict.userAgent()).isEqualTo("IRCS-Storage-Service/0.1");
        assertThatThrownBy(() -> client.execute(OutboundHttpRequest.get("https://example.test/cover.png", strict)))
                .isInstanceOf(OutboundHttpException.class);
        assertThat(transport.requests).isEmpty();
    }

    @Test
    void imageDownloadStrictCanExplicitlyAllowLocalDevAddressesButStillBlocksMetadataHost() throws Exception {
        OutboundHttpPolicy localDev = OutboundHttpPolicy.imageDownloadStrict(Duration.ofSeconds(3), true);
        resolver.host("example.test", "127.0.0.1");
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of("Content-Type", List.of("image/png")),
                new byte[] {1, 2, 3}));

        assertThat(client.execute(OutboundHttpRequest.get("http://example.test/cover.png", localDev)).statusCode())
                .isEqualTo(200);
        assertThat(transport.requests).hasSize(1);

        assertThatThrownBy(() -> client.execute(
                        OutboundHttpRequest.get("http://metadata.google.internal/cover.png", localDev)))
                .isInstanceOf(OutboundHttpException.class);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void apiGatewayProxyPolicyValidatesConfiguredTargetBaseUrlShape() throws Exception {
        OutboundHttpPolicy apiGatewayProxy = OutboundHttpPolicy.apiGatewayProxy(Duration.ofSeconds(3));
        transport.enqueue(response(200, "ok"));

        assertThat(client.execute(OutboundHttpRequest.get("http://portal-service/api/v1/home", apiGatewayProxy)).statusCode())
                .isEqualTo(200);

        assertThatThrownBy(() -> client.execute(OutboundHttpRequest.get("http://token@portal-service/api", apiGatewayProxy)))
                .isInstanceOf(OutboundHttpException.class);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void doesNotFollowRedirectResponse() throws Exception {
        resolver.host("example.test", "93.184.216.34");
        transport.enqueue(new OutboundHttpResponse(
                302,
                Map.of("Location", List.of("https://example.test/next")),
                new byte[0]));

        OutboundHttpResponse response = client.execute(OutboundHttpRequest.get("https://example.test/api", policy));

        assertThat(response.statusCode()).isEqualTo(302);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void retries429And5xxAndIoTimeoutButNotNormal4xx() throws Exception {
        resolver.host("example.test", "93.184.216.34");
        transport.enqueue(response(429, "rate"));
        transport.enqueue(new HttpTimeoutException("timeout"));
        transport.enqueue(response(200, "ok"));

        assertThat(client.execute(OutboundHttpRequest.get("https://example.test/api", policy)).bodyAsUtf8())
                .isEqualTo("ok");
        assertThat(transport.requests).hasSize(3);

        transport.requests.clear();
        transport.enqueue(response(404, "missing"));

        assertThat(client.execute(OutboundHttpRequest.get("https://example.test/not-found", policy)).statusCode())
                .isEqualTo(404);
        assertThat(transport.requests).hasSize(1);
    }

    @Test
    void opensCircuitAfterRetryableStatusAndConnectionRefused() throws Exception {
        OutboundHttpPolicy guarded = policy
                .withMaxRetries(0)
                .withCircuitBreaker(OutboundCircuitBreakerConfig.enabled(2, Duration.ofMinutes(5), 1))
                .withCircuitBreakerKey("catalog-sample");
        resolver.host("example.test", "93.184.216.34");
        transport.enqueue(response(503, "slow"));
        transport.enqueue(new IOException("connection refused"));

        assertThat(client.execute(OutboundHttpRequest.get("https://example.test/api", guarded)).statusCode())
                .isEqualTo(503);
        assertThatThrownBy(() -> client.execute(OutboundHttpRequest.get("https://example.test/api", guarded)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("connection refused");
        assertThatThrownBy(() -> client.execute(OutboundHttpRequest.get("https://example.test/api", guarded)))
                .isInstanceOf(OutboundCircuitOpenException.class)
                .hasMessageContaining("catalog-sample:example.test");
        assertThat(transport.requests).hasSize(2);
    }

    @Test
    void opensCircuitAfterTimeoutAndRecoversThroughHalfOpenProbe() throws Exception {
        MutableClock clock = new MutableClock();
        FakeTransport halfOpenTransport = new FakeTransport();
        OutboundHttpClient guardedClient = new OutboundHttpClient(
                new OutboundUrlPolicy(resolver),
                halfOpenTransport,
                new OutboundCircuitBreaker(clock));
        OutboundHttpPolicy guarded = policy
                .withMaxRetries(0)
                .withCircuitBreaker(OutboundCircuitBreakerConfig.enabled(1, Duration.ofSeconds(1), 1))
                .withCircuitBreakerKey("tmdb-provider");
        resolver.host("example.test", "93.184.216.34");
        halfOpenTransport.enqueue(new HttpTimeoutException("timeout"));

        assertThatThrownBy(() -> guardedClient.execute(OutboundHttpRequest.get("https://example.test/api", guarded)))
                .isInstanceOf(HttpTimeoutException.class);
        assertThatThrownBy(() -> guardedClient.execute(OutboundHttpRequest.get("https://example.test/api", guarded)))
                .isInstanceOf(OutboundCircuitOpenException.class);
        assertThat(halfOpenTransport.requests).hasSize(1);

        clock.advance(Duration.ofSeconds(1));
        halfOpenTransport.enqueue(response(200, "recovered"));
        halfOpenTransport.enqueue(response(200, "closed"));

        assertThat(guardedClient.execute(OutboundHttpRequest.get("https://example.test/api", guarded)).bodyAsUtf8())
                .isEqualTo("recovered");
        assertThat(guardedClient.execute(OutboundHttpRequest.get("https://example.test/api", guarded)).bodyAsUtf8())
                .isEqualTo("closed");
        assertThat(halfOpenTransport.requests).hasSize(3);
    }

    @Test
    void callerSpecificCircuitEnvOverridesGlobalEnvAndProperties() {
        Map<String, String> env = Map.of(
                "IRCS_OUTBOUND_CIRCUIT_ENABLED", "true",
                "IRCS_OUTBOUND_CIRCUIT_FAILURE_THRESHOLD", "9",
                "IRCS_OUTBOUND_CIRCUIT_OPEN_DURATION_MS", "30000",
                "IRCS_OUTBOUND_CIRCUIT_HALF_OPEN_MAX_CALLS", "1",
                "IRCS_OUTBOUND_CIRCUIT_CONTENT_REFETCH_SCRAPER_ENABLED", "false",
                "IRCS_OUTBOUND_CIRCUIT_CONTENT_REFETCH_SCRAPER_FAILURE_THRESHOLD", "2",
                "IRCS_OUTBOUND_CIRCUIT_CONTENT_REFETCH_SCRAPER_OPEN_DURATION", "PT2S",
                "IRCS_OUTBOUND_CIRCUIT_CONTENT_REFETCH_SCRAPER_HALF_OPEN_MAX_CALLS", "3");
        Map<String, String> properties = Map.of(
                "ircs.outbound.circuit.content-refetch-scraper.enabled", "true",
                "ircs.outbound.circuit.content-refetch-scraper.failure-threshold", "7",
                "ircs.outbound.circuit.content-refetch-scraper.open-duration-ms", "7000",
                "ircs.outbound.circuit.content-refetch-scraper.half-open-max-calls", "7");

        OutboundCircuitBreakerConfig config =
                OutboundCircuitBreakerConfig.fromSources("content-refetch-scraper", env::get, properties::get);

        assertThat(config.enabled()).isFalse();
        assertThat(config.failureThreshold()).isEqualTo(2);
        assertThat(config.openDuration()).isEqualTo(Duration.ofSeconds(2));
        assertThat(config.halfOpenMaxCalls()).isEqualTo(3);
    }

    @Test
    void globalCircuitEnvBeatsCallerPropertyFallback() {
        Map<String, String> env = Map.of(
                "IRCS_OUTBOUND_CIRCUIT_ENABLED", "false",
                "IRCS_OUTBOUND_CIRCUIT_FAILURE_THRESHOLD", "4",
                "IRCS_OUTBOUND_CIRCUIT_OPEN_DURATION_MS", "12000",
                "IRCS_OUTBOUND_CIRCUIT_HALF_OPEN_MAX_CALLS", "2");
        Map<String, String> properties = Map.of(
                "ircs.outbound.circuit.task-scraper-execution.enabled", "true",
                "ircs.outbound.circuit.task-scraper-execution.failure-threshold", "8",
                "ircs.outbound.circuit.task-scraper-execution.open-duration-ms", "8000",
                "ircs.outbound.circuit.task-scraper-execution.half-open-max-calls", "8");

        OutboundCircuitBreakerConfig config =
                OutboundCircuitBreakerConfig.fromSources("task-scraper-execution", env::get, properties::get);

        assertThat(config.enabled()).isFalse();
        assertThat(config.failureThreshold()).isEqualTo(4);
        assertThat(config.openDuration()).isEqualTo(Duration.ofSeconds(12));
        assertThat(config.halfOpenMaxCalls()).isEqualTo(2);
    }

    @Test
    void callerCircuitPropertyFallsBackWhenEnvIsAbsent() {
        Map<String, String> properties = Map.of(
                "ircs.outbound.circuit.identity-avatar-storage.enabled", "false",
                "ircs.outbound.circuit.identity-avatar-storage.failure-threshold", "6",
                "ircs.outbound.circuit.identity-avatar-storage.open-duration", "15s",
                "ircs.outbound.circuit.identity-avatar-storage.half-open-max-calls", "2");

        OutboundCircuitBreakerConfig config =
                OutboundCircuitBreakerConfig.fromSources("identity-avatar-storage", name -> null, properties::get);

        assertThat(config.enabled()).isFalse();
        assertThat(config.failureThreshold()).isEqualTo(6);
        assertThat(config.openDuration()).isEqualTo(Duration.ofSeconds(15));
        assertThat(config.halfOpenMaxCalls()).isEqualTo(2);
    }

    @Test
    void supportsPostWithoutBodyForInternalCallers() throws Exception {
        OutboundHttpPolicy internal = OutboundHttpPolicy.internalService(Duration.ofSeconds(3));
        transport.enqueue(response(204, ""));

        assertThat(client.execute(OutboundHttpRequest.post("http://scraper-service/internal/refetch", internal))
                        .statusCode())
                .isEqualTo(204);

        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.body()).isEmpty();
        assertThat(sent.policy().type()).isEqualTo(OutboundHttpPolicyType.INTERNAL_SERVICE);
    }

    @Test
    void supportsArbitraryMethodAndBodyForApiGatewayProxyCallers() throws Exception {
        OutboundHttpPolicy apiGatewayProxy = OutboundHttpPolicy.apiGatewayProxy(Duration.ofSeconds(3))
                .withCircuitBreakerKey("api-gateway-proxy");
        transport.enqueue(response(202, "accepted"));

        OutboundHttpRequest request = new OutboundHttpRequest(
                java.net.URI.create("http://content-service/api/v1/raw-videos/42"),
                "PATCH",
                Map.of("Content-Type", "application/json"),
                apiGatewayProxy,
                "{\"title\":\"codex\"}".getBytes(StandardCharsets.UTF_8));

        assertThat(client.execute(request).bodyAsUtf8()).isEqualTo("accepted");

        OutboundHttpRequest sent = transport.requests.getFirst();
        assertThat(sent.method()).isEqualTo("PATCH");
        assertThat(new String(sent.body(), StandardCharsets.UTF_8)).isEqualTo("{\"title\":\"codex\"}");
        assertThat(sent.headers()).containsEntry("Content-Type", "application/json");
        assertThat(sent.policy().circuitBreakerKey()).isEqualTo("api-gateway-proxy");
    }

    @Test
    void streamingClientPreservesBodyStreamAndCircuitKey() throws Exception {
        OutboundHttpPolicy apiGatewayProxy = OutboundHttpPolicy.apiGatewayProxy(Duration.ofSeconds(3))
                .withCircuitBreaker(OutboundCircuitBreakerConfig.enabled(1, Duration.ofMinutes(5), 1))
                .withCircuitBreakerKey("api-gateway-proxy");
        FakeStreamingTransport streamingTransport = new FakeStreamingTransport();
        streamingTransport.enqueue(new OutboundStreamingHttpResponse(
                200,
                Map.of("Content-Type", List.of("text/event-stream")),
                new ByteArrayInputStream("event: ping\n\n".getBytes(StandardCharsets.UTF_8))));
        OutboundStreamingHttpClient streamingClient = new OutboundStreamingHttpClient(
                new OutboundUrlPolicy(resolver),
                streamingTransport);

        try (OutboundStreamingHttpResponse response = streamingClient.execute(new OutboundHttpRequest(
                java.net.URI.create("http://portal-service/api/portal/events"),
                "GET",
                Map.of("Accept", "text/event-stream"),
                apiGatewayProxy,
                new byte[0]))) {
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.headers()).containsKey("Content-Type");
            assertThat(new String(response.body().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("event: ping\n\n");
        }

        OutboundHttpRequest sent = streamingTransport.requests.getFirst();
        assertThat(sent.headers()).containsEntry("Accept", "text/event-stream");
        assertThat(sent.policy().circuitBreakerKey()).isEqualTo("api-gateway-proxy");
    }

    @Test
    void streamingClientOpensCircuitAfterRetryableStatus() throws Exception {
        OutboundHttpPolicy guarded = OutboundHttpPolicy.apiGatewayProxy(Duration.ofSeconds(3))
                .withCircuitBreaker(OutboundCircuitBreakerConfig.enabled(1, Duration.ofMinutes(5), 1))
                .withCircuitBreakerKey("api-gateway-proxy");
        FakeStreamingTransport streamingTransport = new FakeStreamingTransport();
        streamingTransport.enqueue(new OutboundStreamingHttpResponse(
                503,
                Map.of(),
                new ByteArrayInputStream("slow".getBytes(StandardCharsets.UTF_8))));
        OutboundStreamingHttpClient streamingClient = new OutboundStreamingHttpClient(
                new OutboundUrlPolicy(resolver),
                streamingTransport);

        try (OutboundStreamingHttpResponse response = streamingClient.execute(
                OutboundHttpRequest.get("http://content-service/api", guarded))) {
            assertThat(response.statusCode()).isEqualTo(503);
        }
        assertThatThrownBy(() -> streamingClient.execute(
                        OutboundHttpRequest.get("http://content-service/api", guarded)))
                .isInstanceOf(OutboundCircuitOpenException.class)
                .hasMessageContaining("api-gateway-proxy:content-service");
        assertThat(streamingTransport.requests).hasSize(1);
    }

    @Test
    void decompressesGzipByHeaderOrMagicBytes() throws Exception {
        resolver.host("example.test", "93.184.216.34");
        transport.enqueue(new OutboundHttpResponse(
                200,
                Map.of("Content-Encoding", List.of("gzip")),
                gzip("压缩内容")));
        transport.enqueue(new OutboundHttpResponse(200, Map.of(), gzip("magic gzip")));

        assertThat(client.execute(OutboundHttpRequest.get("https://example.test/a", policy)).bodyAsUtf8())
                .isEqualTo("压缩内容");
        assertThat(client.execute(OutboundHttpRequest.get("https://example.test/b", policy)).bodyAsUtf8())
                .isEqualTo("magic gzip");
    }

    private void assertBlocked(String url, String address) throws Exception {
        resolver.host("example.test", address);
        if ("unknown".equals(address)) {
            resolver.unknownHost = true;
        }
        assertThatThrownBy(() -> client.execute(OutboundHttpRequest.get(url, policy)))
                .isInstanceOf(OutboundHttpException.class);
        resolver.unknownHost = false;
    }

    private OutboundHttpResponse response(int status, String body) {
        return new OutboundHttpResponse(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private byte[] gzip(String body) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private static final class FakeResolver implements OutboundAddressResolver {

        private String address = "93.184.216.34";
        private boolean unknownHost;

        void host(String host, String address) {
            this.address = address == null ? "93.184.216.34" : address;
        }

        @Override
        public List<InetAddress> resolve(String host) throws java.net.UnknownHostException {
            if (unknownHost) {
                throw new java.net.UnknownHostException(host);
            }
            return List.of(InetAddress.getByName(address));
        }
    }

    private static final class FakeTransport implements OutboundTransport {

        private final List<OutboundHttpRequest> requests = new ArrayList<>();
        private final Queue<Object> responses = new ArrayDeque<>();

        void enqueue(Object response) {
            responses.add(response);
        }

        @Override
        public OutboundHttpResponse send(OutboundHttpRequest request) throws IOException {
            requests.add(request);
            Object next = responses.remove();
            if (next instanceof IOException ex) {
                throw ex;
            }
            return (OutboundHttpResponse) next;
        }
    }

    private static final class FakeStreamingTransport implements OutboundStreamingTransport {

        private final List<OutboundHttpRequest> requests = new ArrayList<>();
        private final Queue<OutboundStreamingHttpResponse> responses = new ArrayDeque<>();

        void enqueue(OutboundStreamingHttpResponse response) {
            responses.add(response);
        }

        @Override
        public OutboundStreamingHttpResponse send(OutboundHttpRequest request) {
            requests.add(request);
            return responses.remove();
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant = Instant.parse("2026-06-09T00:00:00Z");

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
